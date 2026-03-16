package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.admin.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户响应 DTO
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Data
@Schema(description = "用户响应对象")
public class SysUserResponse {

    /**
     * 用户 ID
     */
    @Schema(description = "用户 ID", example = "1")
    private Long id;

    /**
     * 用户名
     */
    @Schema(description = "用户名", example = "zhangsan")
    private String username;

    /**
     * 昵称
     */
    @Schema(description = "昵称", example = "张三")
    private String nickname;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号", example = "13800138000")
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

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-03-05 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-05 12:00:00")
    private LocalDateTime updateTime;

    /**
     * 从实体对象转换
     *
     * @param user 用户实体
     * @return 用户响应对象
     */
    public static SysUserResponse fromEntity(SysUser user) {
        if (user == null) {
            return null;
        }
        SysUserResponse response = new SysUserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setGender(user.getGender());
        response.setAvatar(user.getAvatar());
        response.setStatus(user.getStatus());
        response.setCreateTime(user.getCreateTime());
        response.setUpdateTime(user.getUpdateTime());
        return response;
    }
}
