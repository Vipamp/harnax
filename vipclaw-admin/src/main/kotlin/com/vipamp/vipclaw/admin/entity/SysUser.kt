package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "User entity")
class SysUser : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "User ID")
    var id: Long = 0

    @Schema(description = "Tenant ID (primary tenant)")
    var tenantId: Long? = null

    @Schema(description = "Username")
    var username: String = ""

    @Schema(description = "Password")
    var password: String = ""

    @Schema(description = "Nickname")
    var nickname: String = ""

    @Schema(description = "Email")
    var email: String = ""

    @Schema(description = "Phone number")
    var phone: String = ""

    @Schema(description = "Gender (0:female, 1:male, 2:unknown)")
    var gender: Int = 2

    @Schema(description = "Avatar URL")
    var avatar: String? = null

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Admin status (0:no, 1:yes)")
    var isAdmin: Int = 0

    @Schema(description = "Last login time")
    var lastLoginTime: LocalDateTime? = null

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
