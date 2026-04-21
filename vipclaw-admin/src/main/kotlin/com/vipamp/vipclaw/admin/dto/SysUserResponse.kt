package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SysUser
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 用户响应对象
 * 
 * 根据数据库表结构定义:
 * - NOT NULL 字段: id, username, nickname, email, phone, status, isAdmin, createTime, updateTime (非空)
 * - NULLABLE 字段: gender, avatar, lastLoginTime (可空)
 * - 排除字段: password, active (安全/内部使用)
 */
@Schema(description = "用户响应对象")
data class SysUserResponse(
    @Schema(description = "用户 ID", example = "1")
    val id: Long,
    
    @Schema(description = "用户名", example = "zhangsan")
    val username: String,
    
    @Schema(description = "昵称", example = "张三")
    val nickname: String,
    
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    val email: String,
    
    @Schema(description = "手机号", example = "13800138000")
    val phone: String,
    
    @Schema(description = "性别 (0:女 1:男 2:未知)", example = "2")
    val gender: Int? = null,
    
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    val avatar: String? = null,
    
    @Schema(description = "状态 (0:禁用 1:启用)", example = "1")
    val status: Int,
    
    @Schema(description = "是否是管理员(0:否,1:是)", example = "0")
    val isAdmin: Int,
    
    @Schema(description = "最近一次登录时间", example = "2026-03-05 12:00:00")
    val lastLoginTime: LocalDateTime? = null,
    
    @Schema(description = "创建时间", example = "2026-03-05 12:00:00")
    val createTime: LocalDateTime,
    
    @Schema(description = "更新时间", example = "2026-03-05 12:00:00")
    val updateTime: LocalDateTime
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: SysUser): SysUserResponse {
            return SysUserResponse(
                id = entity.id,
                username = entity.username,
                nickname = entity.nickname,
                email = entity.email,
                phone = entity.phone,
                gender = entity.gender,
                avatar = if (entity.avatar.isEmpty()) null else entity.avatar,
                status = entity.status,
                isAdmin = entity.isAdmin,
                lastLoginTime = entity.lastLoginTime,
                createTime = entity.createTime,
                updateTime = entity.updateTime
            )
        }
    }
}
