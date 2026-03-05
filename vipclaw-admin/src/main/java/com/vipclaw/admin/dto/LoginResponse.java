package com.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录响应对象")
public class LoginResponse {

    @Schema(description = "访问令牌", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    private String accessToken;

    @Schema(description = "令牌类型", example = "Bearer")
    private String tokenType;

    @Schema(description = "过期时间（秒）", example = "7200")
    private Long expiresIn;

    @Schema(description = "用户信息")
    private UserInfo userInfo;

    /**
     * 用户信息 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        @Schema(description = "用户 ID", example = "1")
        private Long userId;

        @Schema(description = "用户名", example = "admin")
        private String username;

        @Schema(description = "昵称", example = "管理员")
        private String nickname;

        @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
        private String avatar;

        @Schema(description = "邮箱", example = "admin@example.com")
        private String email;

        @Schema(description = "手机号", example = "13800138000")
        private String phone;

        @Schema(description = "性别 (0:女 1:男 2:保密)", example = "1")
        private Integer gender;
    }
}
