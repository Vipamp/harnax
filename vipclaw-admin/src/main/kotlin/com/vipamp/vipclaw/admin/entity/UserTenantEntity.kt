package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "用户-租户关联实体类")
class UserTenantEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "用户ID")
    var userId: Long = 0

    @Schema(description = "租户ID")
    var tenantId: Long = 0

    @Schema(description = "角色（admin/member）")
    var role: String = "member"

    @Schema(description = "状态（0:禁用 1:启用）")
    var status: Int = 1

    @Schema(description = "加入时间")
    var joinedAt: LocalDateTime? = null
}
