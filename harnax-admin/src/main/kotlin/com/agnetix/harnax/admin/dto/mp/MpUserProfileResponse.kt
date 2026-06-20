package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "User profile response")
data class MpUserProfileResponse(
    @Schema(description = "User ID")
    val userId: Long,

    @Schema(description = "Username")
    val username: String,

    @Schema(description = "Nickname")
    val nickname: String?,

    @Schema(description = "Avatar URL")
    val avatar: String?,

    @Schema(description = "Email")
    val email: String?,

    @Schema(description = "Phone")
    val phone: String?,

    @Schema(description = "Creation time")
    val createTime: LocalDateTime?,
)
