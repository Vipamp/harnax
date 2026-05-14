package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * User update request object
 *
 * Defined according to database table structure:
 * - All fields are optional (partial update mechanism)
 * - Username is read-only and cannot be modified
 * - Null fields will not be updated
 */
@Schema(description = "User update request object")
data class SysUserUpdateRequest(
    @Schema(description = "User ID", example = "1")
    val id: Long? = null,

    @Schema(description = "用户名", example = "zhangsan", accessMode = Schema.AccessMode.READ_ONLY)
    val username: String? = null,

    @Schema(description = "昵称")
    @Size(max = 50, message = "Nickname length cannot exceed 50 characters")
    val nickname: String? = null,

    @Schema(description = "邮箱")
    @Email(message = "Invalid email format")
    val email: String? = null,

    @Schema(description = "手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$|^$", message = "Invalid phone number format")
    val phone: String? = null,

    @Schema(description = "性别 (0:女 1:男 2:未知)")
    val gender: Int? = null,

    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    val avatar: String? = null,

    @Schema(description = "Whether is administrator (0:no, 1:yes)")
    val isAdmin: Int? = null,
)
