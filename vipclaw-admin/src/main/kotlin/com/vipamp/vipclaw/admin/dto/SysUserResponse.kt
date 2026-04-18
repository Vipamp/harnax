package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SysUser
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 用户响应对象
 */
@Schema(description = "用户响应对象")
data class SysUserResponse(
    @Schema(description = "用户 ID", example = "1")
    val id: Long? = null,
    @Schema(description = "用户名", example = "zhangsan")
    val username: String? = null,
    @Schema(description = "昵称", example = "张三")
    val nickname: String? = null,
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    val email: String? = null,
    @Schema(description = "手机号", example = "13800138000")
    val phone: String? = null,
    @Schema(description = "性别 (0:女 1:男 2:未知)")
    val gender: Int? = null,
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    val avatar: String? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    val status: Int? = null,
    @Schema(description = "是否是管理员（0:否，1:是）", example = "0")
    val isAdmin: Int? = null,
    @Schema(description = "创建时间", example = "2026-03-05 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "更新时间", example = "2026-03-05 12:00:00")
    val updateTime: LocalDateTime? = null
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: SysUser?): SysUserResponse {
            if (entity == null) return SysUserResponse()
            return SysUserResponse(
                id = entity.id,
                username = entity.username,
                nickname = entity.nickname,
                email = entity.email,
                phone = entity.phone,
                gender = entity.gender,
                avatar = entity.avatar,
                status = entity.status,
                isAdmin = entity.isAdmin,
                createTime = entity.createTime,
                updateTime = entity.updateTime
            )
        }
    }
}
