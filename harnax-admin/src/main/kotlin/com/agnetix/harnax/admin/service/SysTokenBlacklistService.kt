package com.agnetix.harnax.admin.service

import java.time.LocalDateTime

/**
 * Token blacklist service interface
 */
interface SysTokenBlacklistService {

    /**
     * Add token to blacklist
     *
     * @param token      JWT Token
     * @param username   Username
     * @param userId     User ID
     * @param expireTime Expire time
     * @param reason     Reason
     */
    fun addToBlacklist(token: String, username: String, userId: Long, expireTime: LocalDateTime, reason: String): Unit

    /**
     * Check if token is in blacklist
     *
     * @param token JWT Token
     * @return true-in blacklist, false-not in blacklist
     */
    fun isBlacklisted(token: String): Boolean
}
