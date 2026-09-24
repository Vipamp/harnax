package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.common.mcp.McpConfigDecryptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Encrypts and decrypts the secret fields of an MCP configuration.
 * Reuses [AesUtil] for AES-256-GCM and covers the `secret = true` entries of an `McpConfigEntry` list.
 */
@Component
class SecretFieldEncryptor(
    private val aesUtil: AesUtil,
    private val objectMapper: ObjectMapper,
) : McpConfigDecryptor {

    private val log = LoggerFactory.getLogger(SecretFieldEncryptor::class.java)

    /**
     * Encrypts the `value` of every `secret = true` entry in the list, then serializes to JSON.
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
            if (!entry.secret) {
                rejectMask(entry.key, entry.value)
                entry
            } else if (entry.value.isBlank()) {
                // Typed-and-empty stays empty rather than becoming a ciphertext of nothing.
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
     * A field that is *not* marked secret has nothing carried over from the stored row, so a mask
     * coming back from the edit form would be written to the database as the value - and delivered to
     * the runtime as a header that looks like a key and authenticates as nothing. That happens on the
     * path where a user turns the "secret" toggle off an already-saved row: the form still holds the
     * masked text it was shown. Refused rather than stored, because the alternative is a credential
     * silently replaced by its own mask.
     *
     * The masked text itself never reaches the message: a real value may contain four asterisks, and
     * this one goes to the browser.
     */
    private fun rejectMask(
        key: String,
        value: String,
    ) {
        if (value.isNotBlank() && isMaskedValue(value)) {
            throw BizException(
                "\"$key\" carries a masked display value from the edit form, and this field is not marked as " +
                    "secret, so there is nothing stored to keep. Type the real value, or mark the field as secret",
            )
        }
    }

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
     * Deserializes the JSON configuration, decrypts the `value` of every `secret = true` entry and
     * returns the plaintext map. Satisfies the [McpConfigDecryptor] contract.
     */
    override fun decryptToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        // A column that will not parse answers as no headers at all; serializeDecrypted says so when
        // it delivers an empty object for a column that is not an empty array.
        val entries = deserializeEntries(json)
        var undecryptable = 0
        // Per entry, not one try around the batch: a single value this key cannot open used to drop
        // every header with it, so one bad row turned into "no headers configured" rather than one
        // missing header.
        val values = entries.mapNotNull { entry ->
            val value = if (entry.secret && entry.value.isNotBlank()) {
                runCatching { aesUtil.decrypt(entry.value) }
                    .onFailure {
                        undecryptable++
                        log.warn("MCP header \"{}\" could not be decrypted, skipping it", entry.key)
                    }
                    .getOrNull()
            } else {
                entry.value
            }
            value?.let { entry.key to it }
        }.toMap()
        // The row-level picture the per-key lines above cannot add up to: a config that is mostly
        // undecryptable used to announce itself as "no headers at all", and that much signal is worth
        // keeping after the failure became per entry.
        if (undecryptable > 0) {
            log.warn(
                "{} of the {} MCP header entries cannot be decrypted with the current key, {} are being delivered",
                undecryptable,
                entries.size,
                values.size,
            )
        }
        return values
    }

    /**
     * Decrypts a single stored ciphertext.
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
        if (!entry.secret) {
            rejectMask(entry.envParamName, value ?: "")
            return entry.defaultValue
        }
        if (value.isNullOrBlank()) return entry.defaultValue
        if (!isMaskedValue(value)) return aesUtil.encrypt(value)
        return storedSecrets[entry.envParamName]
            ?: throw BizException("Secret value of \"${entry.envParamName}\" cannot be kept, please re-enter it")
    }

    /**
     * Deserialize ToolEnvParamEntry JSON and decrypt secret defaultValue, return as Map<envParamName, defaultValue>
     */
    override fun decryptToolEnvParamsToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val entries = deserializeToolEnvEntries(json)
        var undecryptable = 0
        val values = entries.mapNotNull { entry ->
            val value = if (entry.secret && !entry.defaultValue.isNullOrBlank()) {
                runCatching { aesUtil.decrypt(entry.defaultValue!!) }
                    .onFailure {
                        undecryptable++
                        log.warn("Tool env param \"{}\" could not be decrypted, skipping it", entry.envParamName)
                    }
                    .getOrNull()
            } else {
                entry.defaultValue ?: ""
            }
            value?.let { entry.envParamName to it }
        }.toMap()
        if (undecryptable > 0) {
            log.warn(
                "{} of the {} tool env param entries cannot be decrypted with the current key, {} are being delivered",
                undecryptable,
                entries.size,
                values.size,
            )
        }
        return values
    }

    /**
     * Deserializes JSON into a list of [McpConfigEntry] without touching the values.
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
     * Deserializes JSON into a list of [ToolEnvParamEntry]; values stay exactly as stored, undecrypted.
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
