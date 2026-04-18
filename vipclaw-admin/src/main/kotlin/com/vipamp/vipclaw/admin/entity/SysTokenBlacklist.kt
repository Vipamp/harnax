package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.*
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@TableName("sys_token_blacklist")
@Schema(description = "Token 黑名单实体")
class SysTokenBlacklist {
    @TableId(type = IdType.AUTO)
    var id: Long = 0

    @Schema(description = "JWT Token")
    var token: String = ""

    @Schema(description = "Token 的 SHA256 哈希值")
    var tokenHash: String = ""

    @Schema(description = "用户名")
    var username: String = ""

    @Schema(description = "用户 ID")
    var userId: Long = 0

    @Schema(description = "加入黑名单原因")
    var reason: String = "logout"

    @Schema(description = "Token 过期时间")
    var expireTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "操作 IP")
    var createIp: String = ""
}
