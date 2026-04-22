package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 用户创建请求对象
 * 
 * 根据 PRD 文档 3.2.1 定义:
 * - 必填字段: username, password, nickname, email, phone
 * - 可选字段: gender, avatar
 * - 不包含: status, isAdmin (由系统自动设置默认值)
 */
@Schema(description = "用户创建请求对象")
data class SysUserCreateRequest(
    @Schema(description = "用户名", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    val username: String,
    
    @Schema(description = "密码(明文)", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(min = 6, max = 100, message = "密码长度必须在 6-100 之间")
    val password: String,
    
    @Schema(description = "昵称", example = "张三", requiredMode = Schema.RequiredMode.REQUIRED)
    val nickname: String,
    
    @Schema(description = "邮箱", example = "zhangsan@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @Email(message = "邮箱格式不正确")
    val email: String,
    
    @Schema(description = "手机号", example = "13800138000", requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    val phone: String,
    
    @Schema(description = "性别 (0:女 1:男 2:未知)", example = "2")
    val gender: Int? = 2,
    
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    val avatar: String? = null
)
