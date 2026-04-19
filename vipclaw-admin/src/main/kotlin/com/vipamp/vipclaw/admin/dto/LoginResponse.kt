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
    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder {
        private var accessToken: String? = null
        private var tokenType: String? = null
        private var expiresIn: Long? = null
        private var expiresAt: Long? = null
        private var userInfo: UserInfo? = null

        fun accessToken(accessToken: String?) = apply { this.accessToken = accessToken }
        fun tokenType(tokenType: String?) = apply { this.tokenType = tokenType }
        fun expiresIn(expiresIn: Long?) = apply { this.expiresIn = expiresIn }
        fun expiresAt(expiresAt: Long?) = apply { this.expiresAt = expiresAt }
        fun userInfo(userInfo: UserInfo?) = apply { this.userInfo = userInfo }

        fun build() = LoginResponse(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresIn = expiresIn,
            expiresAt = expiresAt,
            userInfo = userInfo
        )
    }

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
    ) {
        companion object {
            @JvmStatic
            fun builder() = Builder()
        }

        class Builder {
            private var userId: Long? = null
            private var username: String? = null
            private var nickname: String? = null
            private var avatar: String? = null
            private var email: String? = null
            private var phone: String? = null
            private var gender: Int? = null
            private var isAdmin: Int? = null

            fun userId(userId: Long?) = apply { this.userId = userId }
            fun username(username: String?) = apply { this.username = username }
            fun nickname(nickname: String?) = apply { this.nickname = nickname }
            fun avatar(avatar: String?) = apply { this.avatar = avatar }
            fun email(email: String?) = apply { this.email = email }
            fun phone(phone: String?) = apply { this.phone = phone }
            fun gender(gender: Int?) = apply { this.gender = gender }
            fun isAdmin(isAdmin: Int?) = apply { this.isAdmin = isAdmin }

            fun build() = UserInfo(
                userId = userId,
                username = username,
                nickname = nickname,
                avatar = avatar,
                email = email,
                phone = phone,
                gender = gender,
                isAdmin = isAdmin
            )
        }
    }
}
