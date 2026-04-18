package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.*
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * 模型供应商实体类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@TableName("model_provider")
@Schema(description = "模型供应商实体类")
class ModelProvider : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    var id: Long = 1

    /**
     * 服务商名称(dashscope/openai/ollama)
     */
    @Schema(description = "服务商名称(dashscope/openai/ollama)")
    var name: String = "dashscope"

    /**
     * 显示名称
     */
    @Schema(description = "显示名称")
    var displayName: String = ""

    /**
     * API 密钥
     */
    @Schema(description = "API 密钥")
    var apiKey: String = ""

    /**
     * API 地址
     */
    @Schema(description = "API 地址")
    var baseUrl: String = ""

    /**
     * 是否启用(0:禁用,1:启用)
     */
    @Schema(description = "是否启用(0:禁用,1:启用)")
    var status: Int = 1

    /**
     * 是否公开(0:否,1:是)
     */
    @Schema(description = "是否公开(0:否,1:是)")
    var isPublic: Int = 1

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    var creator: String = ""

    /**
     * 是否可用(0:被删除,1:可用)
     */
    @Schema(description = "是否可用(0:被删除,1:可用)")
    @TableLogic(value = "1", delval = "0")
    var active: Int = 1

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    var updateTime: LocalDateTime = LocalDateTime.now()
}
