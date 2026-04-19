package com.vipamp.vipclaw.admin.service

import java.time.LocalDateTime


/**
 * Token 黑名单服务接口
 */
interface SysTokenBlacklistService {

    /**
     * 将 Token 加入黑名单
     *
     * @param token      JWT Token
     * @param username   用户名
     * @param userId     用户 ID
     * @param expireTime 过期时间
     * @param reason     原因
     */
    fun addToBlacklist(token: String, username: String, userId: Long, expireTime: LocalDateTime, reason: String): Unit

    /**
     * 检查 Token 是否在黑名单中
     *
     * @param token JWT Token
     * @return true-在黑名单中，false-不在黑名单
     */
    fun isBlacklisted(token: String): Boolean
}
