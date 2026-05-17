package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.entity.SysUser
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * User response object
 *
 * According to database table structure:
 * - NOT NULL fields: id, username, nickname, email, phone, status, isAdmin, createTime, updateTime (non-null)
 * - NULLABLE fields: gender, avatar, lastLoginTime (nullable)
 * - Excluded fields: password, active (security/internal use)
 */
@Schema(description = "User response object")
data class SysUserResponse(
    @Schema(description = "User ID", example = "1")
    val id: Long,

    @Schema(description = "Username", example = "zhangsan")
    val username: String,

    @Schema(description = "Nickname", example = "Zhang San")
    val nickname: String,

    @Schema(description = "Email", example = "zhangsan@example.com")
    val email: String,

    @Schema(description = "Phone number", example = "13800138000")
    val phone: String,

    @Schema(description = "Gender (0:female 1:male 2:unknown)", example = "2")
    val gender: Int? = null,

    @Schema(description = "Avatar URL", example = "https://example.com/avatar.jpg")
    val avatar: String? = null,

    @Schema(description = "Status (0:disabled 1:enabled)", example = "1")
    val status: Int,

    @Schema(description = "Whether administrator (0:no, 1:yes)", example = "0")
    val isAdmin: Int,

    @Schema(description = "Last login time", example = "2026-03-05 12:00:00")
    val lastLoginTime: LocalDateTime?,

    @Schema(description = "Creation time", example = "2026-03-05 12:00:00")
    val createTime: LocalDateTime,

    @Schema(description = "Update time", example = "2026-03-05 12:00:00")
    val updateTime: LocalDateTime,

    @Schema(description = "Number of associated tenants", example = "2")
    val tenantCount: Int = 0,
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: SysUser): SysUserResponse = SysUserResponse(
            id = entity.id,
            username = entity.username,
            nickname = entity.nickname,
            email = entity.email,
            phone = entity.phone,
            gender = entity.gender,
            avatar = entity.avatar,
            status = entity.status,
            isAdmin = entity.isAdmin,
            lastLoginTime = entity.lastLoginTime,
            createTime = entity.createTime,
            updateTime = entity.updateTime,
        )
    }
}
