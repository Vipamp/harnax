package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
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
     */
    fun serializeWithEncryption(entries: List<McpConfigEntry>?): String? {
        if (entries.isNullOrEmpty()) return null
        val encryptedEntries = entries.map { entry ->
            if (entry.secret && entry.value.isNotBlank()) {
                entry.copy(value = aesUtil.encrypt(entry.value))
            } else {
                entry
            }
        }
        return objectMapper.writeValueAsString(encryptedEntries)
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
     * Encrypt a plain text value
     */
    fun encrypt(plainValue: String): String = aesUtil.encrypt(plainValue)

    /**
     * 解密单个加密字符串
     */
    fun decrypt(encryptedValue: String): String = aesUtil.decrypt(encryptedValue)

    /**
     * Serialize ToolEnvParamEntry list with encryption for secret defaultValue
     */
    fun serializeToolEnvParams(entries: List<ToolEnvParamEntry>?): String? {
        if (entries.isNullOrEmpty()) return null
        val encryptedEntries = entries.map { entry ->
            if (entry.secret && !entry.defaultValue.isNullOrBlank()) {
                entry.copy(defaultValue = aesUtil.encrypt(entry.defaultValue!!))
            } else {
                entry
            }
        }
        return objectMapper.writeValueAsString(encryptedEntries)
    }

    /**
     * Deserialize ToolEnvParamEntry JSON and decrypt secret defaultValue, return as Map<envParamName, defaultValue>
     */
    override fun decryptToolEnvParamsToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val entries = objectMapper.readValue(
                json,
                objectMapper.typeFactory.constructCollectionType(List::class.java, ToolEnvParamEntry::class.java),
            ) as List<ToolEnvParamEntry>
            entries.associate { entry ->
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
}
