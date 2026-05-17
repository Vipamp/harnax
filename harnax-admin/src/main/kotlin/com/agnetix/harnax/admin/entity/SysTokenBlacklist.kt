package com.agnetix.harnax.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "Token blacklist entity")
class SysTokenBlacklist {
    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "JWT Token")
    var token: String = ""

    @Schema(description = "SHA256 hash of the token")
    var tokenHash: String = ""

    @Schema(description = "Username")
    var username: String = ""

    @Schema(description = "User ID")
    var userId: Long = 0

    @Schema(description = "Reason for blacklisting")
    var reason: String = "logout"

    @Schema(description = "Token expiration time")
    var expireTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Operation IP")
    var createIp: String = ""

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder {
        private var id: Long = 0
        private var token: String = ""
        private var tokenHash: String = ""
        private var username: String = ""
        private var userId: Long = 0
        private var reason: String = "logout"
        private var expireTime: LocalDateTime = LocalDateTime.now()
        private var createTime: LocalDateTime = LocalDateTime.now()
        private var createIp: String = ""

        fun id(id: Long) = apply { this.id = id }
        fun token(token: String) = apply { this.token = token }
        fun tokenHash(tokenHash: String) = apply { this.tokenHash = tokenHash }
        fun username(username: String) = apply { this.username = username }
        fun userId(userId: Long) = apply { this.userId = userId }
        fun reason(reason: String) = apply { this.reason = reason }
        fun expireTime(expireTime: LocalDateTime) = apply { this.expireTime = expireTime }
        fun createTime(createTime: LocalDateTime) = apply { this.createTime = createTime }
        fun createIp(createIp: String) = apply { this.createIp = createIp }

        fun build(): SysTokenBlacklist = SysTokenBlacklist().apply {
            this.id = this@Builder.id
            this.token = this@Builder.token
            this.tokenHash = this@Builder.tokenHash
            this.username = this@Builder.username
            this.userId = this@Builder.userId
            this.reason = this@Builder.reason
            this.expireTime = this@Builder.expireTime
            this.createTime = this@Builder.createTime
            this.createIp = this@Builder.createIp
        }
    }
}
