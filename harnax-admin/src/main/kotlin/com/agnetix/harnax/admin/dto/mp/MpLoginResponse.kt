package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Mobile login response")
data class MpLoginResponse(
    @Schema(description = "JWT access token for admin API")
    val accessToken: String,

    @Schema(description = "Auto-generated API key for router SSE streaming")
    val routerApiKey: String,

    @Schema(description = "Router service URL")
    val routerUrl: String,

    @Schema(description = "Token expiration time in seconds")
    val expiresIn: Long,

    @Schema(description = "User info")
    val userInfo: UserInfo,
) {
    data class UserInfo(
        val userId: Long,
        val username: String,
        val nickname: String?,
    )
}
