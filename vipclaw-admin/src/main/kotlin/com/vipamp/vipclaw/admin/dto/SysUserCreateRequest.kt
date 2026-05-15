package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * User creation request object
 *
 * According to PRD document 3.2.1:
 * - Required fields: username, password, nickname
 * - Conditional required: email, phone (required for enterprise and public editions, optional for personal edition)
 * - Optional fields: gender, avatar
 * - Excluded: status, isAdmin (automatically set by system with default values)
 */
@Schema(description = "User creation request object")
data class SysUserCreateRequest(
    @Schema(description = "Username", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers and underscores")
    val username: String,

    @Schema(description = "Password (plaintext)", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(min = 6, max = 100, message = "Password length must be between 6-100")
    val password: String,

    @Schema(description = "Nickname", example = "John Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(min = 1, max = 50, message = "Nickname length must be between 1-50")
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
