package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Model provider entity
 */
@Schema(description = "Model provider entity")
class ModelProvider : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * ID
     */
    @Schema(description = "ID")
    var id: Long = 1

    /**
     * Tenant ID
     */
    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    /**
     * Provider type (dashscope/openai/ollama)
     */
    @Schema(description = "Provider type (dashscope/openai/ollama)")
    var type: String = ""

    /**
     * Name
     */
    @Schema(description = "Name")
    var name: String = ""

    /**
     * Description
     */
    @Schema(description = "Description")
    var description: String? = null

    /**
     * API key
     */
    @Schema(description = "API key")
    var apiKey: String? = null

    /**
     * API base URL
     */
    @Schema(description = "API base URL")
    var baseUrl: String? = null

    /**
     * Status (0:disabled, 1:enabled)
     */
    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    /**
     * Public status (0:no, 1:yes)
     */
    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 1

    /**
     * Creator
     */
    @Schema(description = "Creator")
    var creator: String = ""

    /**
     * Active status (0:deleted, 1:active)
     */
    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    /**
     * Creation time
     */
    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    /**
     * Update time
     */
    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
