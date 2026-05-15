package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Model entity
 */
@Schema(description = "Model entity")
class Model : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Name")
    var name: String = ""

    @Schema(description = "Model name")
    var modelName: String = ""

    @Schema(description = "Model provider ID")
    var providerId: Long = 0

    @Schema(description = "Description")
    var description: String? = null

    @Schema(description = "Model type (chat/embedding)")
    var modelType: String = "chat"

    @Schema(description = "Internet access support (0:no, 1:yes)")
    var supportInternet: Int = 0

    @Schema(description = "Reasoning support (0:no, 1:yes)")
    var supportReasoning: Int = 0

    @Schema(description = "Tool support (0:no, 1:yes)")
    var supportTool: Int = 0

    @Schema(description = "MCP support (0:no, 1:yes)")
    var supportMcp: Int = 0

    @Schema(description = "Vision support (0:no, 1:yes)")
    var supportVision: Int = 0

    @Schema(description = "Price (CNY per million tokens)")
    var price: Double = 0.0

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 1

    @Schema(description = "Creator")
    var creator: String? = null

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
