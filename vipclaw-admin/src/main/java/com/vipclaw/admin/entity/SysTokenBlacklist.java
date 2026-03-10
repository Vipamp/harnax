package com.vipclaw.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Token 黑名单实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_token_blacklist")
@Schema(description= "Token 黑名单实体")
public class SysTokenBlacklist {

    @TableId(type = IdType.AUTO)
   private Long id;

    @Schema(description = "JWT Token")
   private String token;

    @Schema(description = "Token 的 SHA256 哈希值")
   private String tokenHash;

    @Schema(description= "用户名")
   private String username;

    @Schema(description = "用户 ID")
   private Long userId;

    @Schema(description = "加入黑名单原因")
    @Builder.Default
   private String reason = "logout";

    @Schema(description = "Token 过期时间")
   private LocalDateTime expireTime;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
   private LocalDateTime createTime;

    @Schema(description = "操作 IP")
   private String createIp;
}
