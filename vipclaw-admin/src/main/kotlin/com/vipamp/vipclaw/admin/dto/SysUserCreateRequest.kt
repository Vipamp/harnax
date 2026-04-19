package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 用户创建请求对象
 */
@Schema(description = "用户创建请求对象")
data class SysUserCreateRequest(
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    val username: String = "",
    @Size(min = 6, max = 100, message = "密码长度必须在 6-100 之间")
    val password: String = "",
    @Schema(description = "昵称", example = "张三")
    val nickname: String = "",
    @Schema(description = "邮箱")
    @Email(message = "邮箱格式不正确")
    val email: String = "",
    @Schema(description = "手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    val phone: String = "",
    @Schema(description = "性别 (0:女 1:男 2:未知)")
    val gender: Int = 2,
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    val avatar: String = "",
    @Schema(description = "状态 (0:禁用 1:正常)")
    val status: Int = 1,
    @Schema(description = "是否是管理员（0:否，1:是）", example = "0")
    val isAdmin: Int = 0
)
