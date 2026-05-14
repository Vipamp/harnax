package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 用户创建请求对象
 *
 * 根据 PRD 文档 3.2.1 定义:
 * - 必填字段: username, password, nickname
 * - 条件必填: email, phone (企业版和公网版必填，个人版可选)
 * - 可选字段: gender, avatar
 * - 不包含: status, isAdmin (由系统自动设置默认值)
 */
@Schema(description = "用户创建请求对象")
data class SysUserCreateRequest(
    @Schema(description = "Username", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username只能包含字母、数字和下划线")
    val username: String,

    @Schema(description = "Password (plaintext)", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(min = 6, max = 100, message = "Password length must be between 6-100")
    val password: String,

    @Schema(description = "Nickname", example = "张三", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(min = 1, max = 50, message = "Nickname长度必须在 1-50 之间")
    val nickname: String,

    @Schema(description = "Email", example = "zhangsan@example.com")
    val email: String? = null,

    @Schema(description = "Phone number", example = "13800138000")
    val phone: String? = null,

    @Schema(description = "Gender (0:female 1:male 2:unknown)", example = "2")
    val gender: Int? = 2,

    @Schema(description = "Avatar URL", example = "https://example.com/avatar.jpg")
    val avatar: String? = null,
)
