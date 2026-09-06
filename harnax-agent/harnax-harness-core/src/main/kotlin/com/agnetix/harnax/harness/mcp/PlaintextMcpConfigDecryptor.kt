package com.agnetix.harnax.harness.mcp

import com.agnetix.harnax.common.mcp.McpConfigDecryptor
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Fallback [McpConfigDecryptor] for runtimes that hold no AES key.
 *
 * Admin decrypts `mcp_server.headers`, `mcp_server.envParams` and `agent_tool.http_headers` before
 * handing them over the internal API, so what reaches this class is already plain text — a flat JSON
 * object of key to value. That mirrors how binding-level `env_bindings` has always been delivered
 * (see `InternalApiController.resolveEnvBindingsJson`). This implementation parses; it never decrypts.
 *
 * The stored array shape is still accepted rather than rejected, because that is what a database row
 * looks like. Receiving it here means a delivery path skipped the decryption step, and the entries
 * marked `secret` are then ciphertext nobody in this process can turn back into a usable value. That
 * gets reported: an empty header map is otherwise indistinguishable from "no headers configured",
 * which is exactly how a missing decryptor went unnoticed.
 */
class PlaintextMcpConfigDecryptor(
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : McpConfigDecryptor {

    override fun decryptToMap(encryptedJson: String?): Map<String, String> = parse(encryptedJson, "key", "value")

    override fun decryptToolEnvParamsToMap(encryptedJson: String?): Map<String, String> = parse(encryptedJson, "envParamName", "defaultValue")

    private fun parse(json: String?, keyField: String, valueField: String): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val trimmed = json.trim()
        return try {
            if (!trimmed.startsWith("[")) {
                return objectMapper.readValue(trimmed, STRING_MAP)
            }
            // Legacy stored shape: [{"key","value","secret"}] or [{"envParamName","defaultValue","secret"}]
            val entries: List<Map<String, Any?>> = objectMapper.readValue(trimmed, ENTRY_LIST)
            if (entries.any { it["secret"] == true }) {
                log.warn(
                    "A config payload keyed by '{}' arrived still in stored form with secret entries; " +
                        "this runtime cannot decrypt them, so those values will be unusable ciphertext",
                    keyField,
                )
            }
            entries.mapNotNull { entry ->
                val key = entry[keyField]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to (entry[valueField]?.toString() ?: "")
            }.toMap()
        } catch (e: Exception) {
            // The payload itself is never logged: it carries credentials.
            log.warn(
                "Ignoring an unparseable config payload keyed by '{}' ({} chars, starts with '{}'): {}",
                keyField,
                json.length,
                trimmed.take(1),
                e.message ?: e.javaClass.simpleName,
            )
            emptyMap()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlaintextMcpConfigDecryptor::class.java)
        private val STRING_MAP = object : TypeReference<Map<String, String>>() {}
        private val ENTRY_LIST = object : TypeReference<List<Map<String, Any?>>>() {}
    }
}
