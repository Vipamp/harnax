package com.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户更新请求 DTO
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Data
@Schema(description = "用户更新请求对象")
public class SysUserUpdateRequest {

    /**
     * 用户 ID（更新时必须）
     */
    @Schema(description = "用户 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    /**
     * 用户名（更新时不可修改）
     */
    @Schema(description = "用户名", example = "zhangsan", accessMode = Schema.AccessMode.READ_ONLY)
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码", example = "123456")
    @Size(min = 6, max = 100, message = "密码长度必须在 6-100 之间")
    private String password;

    /**
     * 昵称
     */
    @Schema(description = "昵称", example = "张三")
    @Size(max = 50, message = "昵称长度不能超过 50 个字符")
    private String nickname;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号", example = "13800138000")
    @Pattern(regexp = "^1[3-9]\\d{9}$|^$", message = "手机号格式不正确")
    private String phone;

    /**
     * 性别 (0:女 1:男 2:未知)
     */
    @Schema(description = "性别 (0:女 1:男 2:未知)", example = "2")
    private Integer gender;

    /**
     * 头像 URL
     */
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    private String avatar;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    private Integer status;
}
