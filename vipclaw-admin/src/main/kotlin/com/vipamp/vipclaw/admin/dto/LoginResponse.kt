package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 登录响应 DTO
 */
@Schema(description = "登录响应对象")
data class LoginResponse(
    @Schema(description = "访问令牌", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    val accessToken: String? = null,

    @Schema(description = "令牌类型", example = "Bearer")
    val tokenType: String? = null,

    @Schema(description = "过期时间（秒）", example = "7200")
    val expiresIn: Long? = null,

    @Schema(description = "过期时间戳（毫秒）", example = "1717020800000")
    val expiresAt: Long? = null,

    @Schema(description = "用户信息")
    val userInfo: UserInfo? = null
) {
    /**
     * 用户信息 DTO
     */
    @Schema(description = "用户信息")
    data class UserInfo(
        @Schema(description = "用户 ID", example = "1")
        val userId: Long? = null,

        @Schema(description = "用户名", example = "admin")
        val username: String? = null,

        @Schema(description = "昵称", example = "管理员")
        val nickname: String? = null,

        @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
        val avatar: String? = null,

        @Schema(description = "邮箱", example = "admin@example.com")
        val email: String? = null,

        @Schema(description = "手机号", example = "13800138000")
        val phone: String? = null,

        @Schema(description = "性别 (0:女 1:男 2:保密)", example = "1")
        val gender: Int? = null,

        @Schema(description = "是否是管理员（0:否，1:是）", example = "0")
        val isAdmin: Int? = null
    )
}
