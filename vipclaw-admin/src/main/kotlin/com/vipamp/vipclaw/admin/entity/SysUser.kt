package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "用户实体类")
class SysUser : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "用户 ID")
    var id: Long = 0

    @Schema(description = "所属租户ID（主要租户）")
    var tenantId: Long? = null

    @Schema(description = "用户名")
    var username: String = ""

    @Schema(description = "密码")
    var password: String = ""

    @Schema(description = "昵称")
    var nickname: String = ""

    @Schema(description = "邮箱")
    var email: String = ""

    @Schema(description = "手机号")
    var phone: String = ""

    @Schema(description = "性别 (0:女 1:男 2:未知)")
    var gender: Int = 2

    @Schema(description = "头像 URL")
    var avatar: String? = null

    @Schema(description = "是否启用（0:禁用，1:启用）")
    var status: Int = 1

    @Schema(description = "是否是管理员(0:否,1:是)")
    var isAdmin: Int = 0

    @Schema(description = "最近一次登录时间")
    var lastLoginTime: LocalDateTime? = null

    @Schema(description = "是否可用(0:被删除,1:可用)")
    var active: Int = 1

    @Schema(description = "创建时间")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    var updateTime: LocalDateTime = LocalDateTime.now()

}
