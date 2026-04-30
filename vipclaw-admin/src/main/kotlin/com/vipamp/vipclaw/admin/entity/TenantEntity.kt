package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "租户实体类")
class TenantEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "租户ID")
    var id: Long = 0

    @Schema(description = "租户名称")
    var name: String = ""

    @Schema(description = "状态（0:禁用 1:启用）")
    var status: Int = 1

    @Schema(description = "创建人")
    var creator: String = ""

    @Schema(description = "是否可用（0:被删除，1:可用）")
    var active: Int = 1

    @Schema(description = "创建时间")
    var createTime: LocalDateTime? = null

    @Schema(description = "更新时间")
    var updateTime: LocalDateTime? = null
}
