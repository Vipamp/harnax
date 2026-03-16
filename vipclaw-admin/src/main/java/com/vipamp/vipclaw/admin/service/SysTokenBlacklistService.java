package com.vipamp.vipclaw.admin.service;

/**
 * Token 黑名单服务接口
 */
public interface SysTokenBlacklistService {

    /**
     * 将 Token 加入黑名单
     *
     * @param token      JWT Token
     * @param username   用户名
     * @param userId     用户 ID
     * @param expireTime 过期时间
     * @param reason     原因
     */
    void addToBlacklist(String token, String username, Long userId, java.time.LocalDateTime expireTime, String reason);

    /**
     * 检查 Token 是否在黑名单中
     *
     * @param token JWT Token
     * @return true-在黑名单中，false-不在黑名单
     */
    boolean isBlacklisted(String token);
}
