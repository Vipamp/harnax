package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.dto.response.TenantResponse
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Login response DTO
 */
@Schema(description = "Login response object")
data class LoginResponse(
    @Schema(description = "Access token", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    val accessToken: String? = null,

    @Schema(description = "Token type", example = "Bearer")
    val tokenType: String? = null,

    @Schema(description = "Expiration time (seconds)", example = "7200")
    val expiresIn: Long? = null,

    @Schema(description = "Expiration timestamp (milliseconds)", example = "1717020800000")
    val expiresAt: Long? = null,

    @Schema(description = "User information")
    val userInfo: UserInfo? = null,

    @Schema(description = "User's tenant list")
    val tenants: List<TenantResponse>? = null,

    @Schema(description = "Current tenant ID")
    val currentTenantId: Long? = null,

    @Schema(description = "Router API Key for calling router service via X-Api-Key header")
    val routerApiKey: String? = null,
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
        private var tenants: List<TenantResponse>? = null
        private var currentTenantId: Long? = null
        private var routerApiKey: String? = null

        fun accessToken(accessToken: String?) = apply { this.accessToken = accessToken }
        fun tokenType(tokenType: String?) = apply { this.tokenType = tokenType }
        fun expiresIn(expiresIn: Long?) = apply { this.expiresIn = expiresIn }
        fun expiresAt(expiresAt: Long?) = apply { this.expiresAt = expiresAt }
        fun userInfo(userInfo: UserInfo?) = apply { this.userInfo = userInfo }
        fun tenants(tenants: List<TenantResponse>?) = apply { this.tenants = tenants }
        fun currentTenantId(currentTenantId: Long?) = apply { this.currentTenantId = currentTenantId }
        fun routerApiKey(routerApiKey: String?) = apply { this.routerApiKey = routerApiKey }

        fun build() = LoginResponse(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresIn = expiresIn,
            expiresAt = expiresAt,
            userInfo = userInfo,
            tenants = tenants,
            currentTenantId = currentTenantId,
            routerApiKey = routerApiKey,
        )
    }

    /**
     * User information DTO
     */
    @Schema(description = "User information")
    data class UserInfo(
        @Schema(description = "User ID", example = "1")
        val userId: Long? = null,

        @Schema(description = "Username", example = "admin")
        val username: String? = null,

        @Schema(description = "Nickname", example = "Administrator")
        val nickname: String? = null,

        @Schema(description = "Avatar URL", example = "https://example.com/avatar.jpg")
        val avatar: String? = null,

        @Schema(description = "Email", example = "admin@example.com")
        val email: String? = null,

        @Schema(description = "Phone number", example = "13800138000")
        val phone: String? = null,

        @Schema(description = "Gender (0:female 1:male 2:unknown)", example = "1")
        val gender: Int? = null,

        @Schema(description = "Whether administrator (0:no, 1:yes)", example = "0")
        val isAdmin: Int? = null,
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
                isAdmin = isAdmin,
            )
        }
    }
}
