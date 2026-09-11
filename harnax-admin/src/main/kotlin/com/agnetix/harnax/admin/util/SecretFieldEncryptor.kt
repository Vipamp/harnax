package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.common.mcp.McpConfigDecryptor
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * MCP 配置敏感字段加密/解密工具
 * 复用 AesUtil 进行 AES-256-GCM 加解密，处理 McpConfigEntry 列表中 secret=true 的条目
 */
@Component
class SecretFieldEncryptor(
    private val aesUtil: AesUtil,
    private val objectMapper: ObjectMapper,
) : McpConfigDecryptor {

    /**
     * 对配置列表中 secret=true 的条目加密 value，然后序列化为 JSON
     *
     * `storedJson` is the row's current column value. The detail API masks secrets, so an edit that
     * leaves such a field untouched sends the mask straight back; encrypting it would store the mask
     * in place of the real credential. A masked value therefore carries over the stored ciphertext
     * for the same key.
     */
    fun serializeWithEncryption(
        entries: List<McpConfigEntry>?,
        storedJson: String? = null,
    ): String? {
        if (entries.isNullOrEmpty()) return null
        val stored = deserializeEntries(storedJson)
            .filter { it.secret && it.value.isNotBlank() }
            .associate { it.key to it.value }
        val encryptedEntries = entries.map { entry ->
            if (!entry.secret || entry.value.isBlank()) {
                entry
            } else if (isMaskedValue(entry.value)) {
                val carried = stored[entry.key]
                    ?: throw BizException("Secret value of \"${entry.key}\" cannot be kept, please re-enter it")
                entry.copy(value = carried)
            } else {
                entry.copy(value = aesUtil.encrypt(entry.value))
            }
        }
        return objectMapper.writeValueAsString(encryptedEntries)
    }

    /**
     * Both mask shapes put "****" in the middle, and the fixed `******` contains it as well. A real
     * value that happens to contain four consecutive asterisks reads as unchanged, which is the
     * convention already used for model provider api keys.
     */
    private fun isMaskedValue(value: String): Boolean = value.contains("****")

    /**
     * Resolve one stand-alone secret (a column, not a JSON entry) against what is already stored.
     *
     * Same rule as [serializeWithEncryption]: omitted or masked keeps the stored ciphertext, anything
     * else is a fresh value and gets encrypted. Blank is the one addition - there is no other way to
     * say "this client has no secret", so an empty string clears it.
     */
    fun resolveSecret(
        provided: String?,
        storedEncrypted: String?,
    ): String? = when {
        provided == null -> storedEncrypted
        isMaskedValue(provided) ->
            storedEncrypted
                ?: throw BizException("Masked secret value cannot be kept, please re-enter it")
        provided.isBlank() -> null
        else -> aesUtil.encrypt(provided.trim())
    }

    /**
     * 反序列化 JSON 配置，对 secret=true 的条目解密 value，返回明文 Map
     * 实现 McpConfigDecryptor 接口
     */
    override fun decryptToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val entries = deserializeEntries(json)
            entries.associate { entry ->
                val value = if (entry.secret && entry.value.isNotBlank()) {
                    aesUtil.decrypt(entry.value)
                } else {
                    entry.value
                }
                entry.key to value
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 解密单个加密字符串
     */
    fun decrypt(encryptedValue: String): String = aesUtil.decrypt(encryptedValue)

    /**
     * Serialize ToolEnvParamEntry list with encryption for secret defaultValue
     *
     * `storedJson` follows the same rule as [serializeWithEncryption]: a masked defaultValue coming
     * back from the edit form means "unchanged" and keeps the stored ciphertext for that parameter.
     */
    fun serializeToolEnvParams(
        entries: List<ToolEnvParamEntry>?,
        storedJson: String? = null,
    ): String? {
        if (entries.isNullOrEmpty()) return null
        val stored = storedEnvSecrets(deserializeToolEnvEntries(storedJson))
        return objectMapper.writeValueAsString(
            entries.map { entry -> entry.copy(defaultValue = resolveEnvParamValue(entry, stored)) },
        )
    }

    /**
     * The ciphertexts already stored for a batch of env params, keyed by parameter name.
     */
    private fun storedEnvSecrets(entries: List<ToolEnvParamEntry>): Map<String, String> = entries
        .filter { it.secret && !it.defaultValue.isNullOrBlank() }
        .associate { it.envParamName to it.defaultValue!! }

    /**
     * Resolve one env param's stored value: a blank or non-secret value goes through as typed, a
     * fresh secret value is encrypted, and a masked one keeps [storedSecrets] for that name.
     *
     * Callers that keep these entries as table rows rather than a JSON column need the same rule,
     * which is why it lives here instead of inside [serializeToolEnvParams].
     */
    fun resolveEnvParamValue(
        entry: ToolEnvParamEntry,
        storedSecrets: Map<String, String>,
    ): String? {
        val value = entry.defaultValue
        if (!entry.secret || value.isNullOrBlank()) return entry.defaultValue
        if (!isMaskedValue(value)) return aesUtil.encrypt(value)
        return storedSecrets[entry.envParamName]
            ?: throw BizException("Secret value of \"${entry.envParamName}\" cannot be kept, please re-enter it")
    }

    /**
     * Deserialize ToolEnvParamEntry JSON and decrypt secret defaultValue, return as Map<envParamName, defaultValue>
     */
    override fun decryptToolEnvParamsToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            deserializeToolEnvEntries(json).associate { entry ->
                val value = if (entry.secret && !entry.defaultValue.isNullOrBlank()) {
                    aesUtil.decrypt(entry.defaultValue!!)
                } else {
                    entry.defaultValue ?: ""
                }
                entry.envParamName to value
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 反序列化 JSON 为 McpConfigEntry 列表
     */
    fun deserializeEntries(json: String?): List<McpConfigEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(
                json,
                objectMapper.typeFactory.constructCollectionType(List::class.java, McpConfigEntry::class.java),
            ) as List<McpConfigEntry>
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 反序列化 JSON 为 ToolEnvParamEntry 列表（值保持库中原样，不解密）
     */
    fun deserializeToolEnvEntries(json: String?): List<ToolEnvParamEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(
                json,
                objectMapper.typeFactory.constructCollectionType(List::class.java, ToolEnvParamEntry::class.java),
            ) as List<ToolEnvParamEntry>
        } catch (e: Exception) {
            emptyList()
        }
    }
}
