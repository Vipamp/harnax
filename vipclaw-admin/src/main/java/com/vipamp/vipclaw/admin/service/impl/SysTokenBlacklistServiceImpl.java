package com.vipamp.vipclaw.admin.service.impl;

import com.vipamp.vipclaw.admin.entity.SysTokenBlacklist;
import com.vipamp.vipclaw.admin.mapper.SysTokenBlacklistMapper;
import com.vipamp.vipclaw.admin.service.SysTokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * Token 黑名单服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysTokenBlacklistServiceImpl implements SysTokenBlacklistService {

    private final SysTokenBlacklistMapper tokenBlacklistMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addToBlacklist(String token, String username, Long userId, LocalDateTime expireTime, String reason) {
        try {
            // 计算 Token 的 SHA256 哈希值（避免存储完整 Token）
            String tokenHash = hashToken(token);

            // 获取客户端 IP
            String clientIp = getClientIp();

            // 构建黑名单记录
            SysTokenBlacklist blacklist = SysTokenBlacklist.builder()
                    .token(token)  // 存储完整 Token（可选，用于审计）
                    .tokenHash(tokenHash)
                    .username(username)
                    .userId(userId)
                    .reason(reason)
                    .expireTime(expireTime)
                    .createTime(LocalDateTime.now())
                    .createIp(clientIp)
                    .build();

            // 保存到数据库
            tokenBlacklistMapper.insert(blacklist);

            log.info("Token 已加入黑名单，userId: {}, username: {}, reason: {}", userId, username, reason);
        } catch (Exception e) {
            log.error("添加 Token 到黑名单失败：{}", e.getMessage(), e);
            // 不抛出异常，避免影响退出流程
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        try {
            // 计算 Token 的 SHA256 哈希值
            String tokenHash = hashToken(token);

            // 查询是否在黑名单中且未过期
            SysTokenBlacklist blacklist = tokenBlacklistMapper.selectByTokenHash(tokenHash);

            if (blacklist != null) {
                log.debug("Token 在黑名单中，userId: {}", blacklist.getUserId());
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("检查 Token 黑名单失败：{}", e.getMessage());
            // 如果查询失败，默认不在黑名单（允许访问）
            return false;
        }
    }

    /**
     * 计算 Token 的 SHA256 哈希值
     *
     * @param token JWT Token
     * @return 哈希值（小写十六进制）
     */
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // 转换为小写十六进制
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 获取客户端 IP 地址
     */
    private String getClientIp() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();

                // 尝试从 X-Forwarded-For 获取
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getHeader("X-Real-IP");
                }
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getRemoteAddr();
                }
                return ip;
            }
        } catch (Exception e) {
            log.warn("获取客户端 IP 失败：{}", e.getMessage());
        }
        return "unknown";
    }
}
