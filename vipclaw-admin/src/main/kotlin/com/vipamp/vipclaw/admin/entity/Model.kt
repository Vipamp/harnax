package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * 模型实体类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Schema(description = "模型实体类")
class Model : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "所属租户ID")
    var tenantId: Long = 1

    @Schema(description = "名称")
    var name: String = ""

    @Schema(description = "模型名称")
    var modelName: String = ""

    @Schema(description = "模型供应商ID")
    var providerId: Long = 0

    @Schema(description = "描述")
    var description: String? = null

    @Schema(description = "模型类型（chat/embedding）")
    var modelType: String = "chat"

    @Schema(description = "是否支持联网（0:否，1:是）")
    var supportInternet: Int = 0

    @Schema(description = "是否支持推理（0:否，1:是）")
    var supportReasoning: Int = 0

    @Schema(description = "是否支持工具（0:否，1:是）")
    var supportTool: Int = 0

    @Schema(description = "是否支持MCP（0:否，1:是）")
    var supportMcp: Int = 0

    @Schema(description = "是否支持视觉（0:否，1:是）")
    var supportVision: Int = 0

    @Schema(description = "价格（元/百万token）")
    var price: Double = 0.0

    @Schema(description = "是否启用（0:禁用，1:启用）")
    var status: Int = 1

    @Schema(description = "是否公开（0:否，1:是）")
    var isPublic: Int = 1

    @Schema(description = "创建人")
    var creator: String? = null

    @Schema(description = "是否可用（0:被删除，1:可用）")
    var active: Int = 1

    @Schema(description = "创建时间")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
