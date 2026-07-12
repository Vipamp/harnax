package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.McpServer
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * MCP server response object
 */
@Schema(description = "MCP server response object")
data class McpServerResponse(
    @Schema(description = "MCP ID", example = "1")
    val id: Long? = null,
    @Schema(description = "MCP name", example = "my-mcp-server")
    val name: String? = null,
    @Schema(description = "MCP description", example = "This is an MCP server")
    val description: String? = null,
    @Schema(description = "MCP type (stdio/sse/streamablehttp)", example = "stdio")
    val type: String? = null,
    @Schema(description = "Execute command (only for stdio type)")
    val command: String? = null,
    @Schema(description = "Service URL (for sse/streamablehttp type)")
    val url: String? = null,
    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-12 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-12 12:00:00")
    val updateTime: LocalDateTime? = null,
    @Schema(description = "HTTP headers configuration (masked for secret values)")
    val headers: List<McpConfigEntry>? = null,
    @Schema(description = "Environment parameters configuration (masked for secret values)")
    val envParams: List<ToolEnvParamEntry>? = null,
) {
    companion object {
        @JvmStatic
        fun fromEntity(
            entity: McpServer,
            objectMapper: ObjectMapper? = null,
            encryptor: SecretFieldEncryptor? = null,
        ): McpServerResponse {
            val headers = deserializeAndMaskConfig(entity.headers, objectMapper, encryptor, true)
            val envParams = deserializeAndMaskToolEnvParams(entity.envParams, objectMapper, encryptor, true)
            return McpServerResponse(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                type = entity.type,
                command = entity.command,
                url = entity.url,
                status = entity.status,
                isPublic = entity.isPublic,
                creator = entity.creator,
                createTime = entity.createTime,
                updateTime = entity.updateTime,
                headers = headers,
                envParams = envParams,
            )
        }

        /**
         * 反序列化 JSON 配置并对敏感值做掩码处理
         */
        private fun deserializeAndMaskConfig(
            json: String?,
            objectMapper: ObjectMapper?,
            encryptor: SecretFieldEncryptor?,
            maskSecret: Boolean,
        ): List<McpConfigEntry>? {
            if (json.isNullOrBlank() || objectMapper == null) return null
            return try {
                val entries = objectMapper.readValue(json, objectMapper.typeFactory.constructCollectionType(List::class.java, McpConfigEntry::class.java)) as List<McpConfigEntry>
                if (maskSecret) {
                    entries.map { entry ->
                        if (entry.secret) {
                            entry.copy(value = maskValue(entry.value, encryptor))
                        } else {
                            entry
                        }
                    }
                } else {
                    entries
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 反序列化 ToolEnvParamEntry JSON 并对敏感值做掩码处理
         */
        private fun deserializeAndMaskToolEnvParams(
            json: String?,
            objectMapper: ObjectMapper?,
            encryptor: SecretFieldEncryptor?,
            maskSecret: Boolean,
        ): List<ToolEnvParamEntry>? {
            if (json.isNullOrBlank() || objectMapper == null) return null
            return try {
                val entries = objectMapper.readValue(json, objectMapper.typeFactory.constructCollectionType(List::class.java, ToolEnvParamEntry::class.java)) as List<ToolEnvParamEntry>
                if (maskSecret) {
                    entries.map { entry ->
                        if (entry.secret && entry.defaultValue != null) {
                            entry.copy(defaultValue = maskValue(entry.defaultValue!!, encryptor))
                        } else {
                            entry
                        }
                    }
                } else {
                    entries
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 对敏感值做掩码处理，保留前 3 后 4 字符
         */
        private fun maskValue(encryptedValue: String, encryptor: SecretFieldEncryptor?): String {
            if (encryptor == null) return "******"
            return try {
                val plain = encryptor.decrypt(encryptedValue)
                if (plain.length <= 7) {
                    "******"
                } else {
                    "${plain.take(3)}****${plain.takeLast(4)}"
                }
            } catch (e: Exception) {
                "******"
            }
        }
    }
}
