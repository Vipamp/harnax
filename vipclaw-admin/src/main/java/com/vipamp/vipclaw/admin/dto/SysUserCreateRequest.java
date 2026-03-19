package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户创建请求 DTO
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Data
@Schema(description = "用户创建请求对象")
public class SysUserCreateRequest {

    /**
     * 用户名
     */
    @Schema(description = "用户名", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度必须在 3-50 之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度必须在 6-100 之间")
    private String password;

    /**
     * 昵称
     */
    @Schema(description = "昵称", example = "张三")
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
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
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
     * 状态 (0:禁用 1:正常)
     **/
    @Schema(description = "状态 (0:禁用 1:正常)", example = "1")
    private Integer status;

    /**
     * 是否是管理员（0:否，1:是）
     */
    @Schema(description = "是否是管理员（0:否，1:是）", example = "0")
    private Integer isAdmin;
}
