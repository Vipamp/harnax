package com.vipamp.vipclaw.admin.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 用户上下文工具类
 * 用于获取当前登录用户信息
 *
 * @author vipamp
 * @since 2026-03-19
 */
@Slf4j
@Component
public class UserContextUtil {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 获取当前请求
     *
     * @return 当前 HttpServletRequest
     */
    public static HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                return attributes.getRequest();
            }
        } catch (Exception e) {
            log.error("获取当前请求失败：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 从请求头中获取 Token
     *
     * @return JWT Token
     */
    public static String getToken() {
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
            if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
                return bearerToken.substring(BEARER_PREFIX.length());
            }
        }
        return null;
    }

    /**
     * 获取当前登录用户名
     *
     * @param jwtUtil JWT 工具类
     * @return 用户名，未登录返回 null
     */
    public static String getCurrentUsername(JwtUtil jwtUtil) {
        try {
            String token = getToken();
            if (token != null && jwtUtil.validateToken(token)) {
                return jwtUtil.getUsernameFromToken(token);
            }
        } catch (Exception e) {
            log.error("获取当前用户名失败：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取当前登录用户 ID
     *
     * @param jwtUtil JWT 工具类
     * @return 用户 ID，未登录返回 null
     */
    public static Long getCurrentUserId(JwtUtil jwtUtil) {
        try {
            String token = getToken();
            if (token != null && jwtUtil.validateToken(token)) {
                return jwtUtil.getUserIdFromToken(token);
            }
        } catch (Exception e) {
            log.error("获取当前用户ID失败：{}", e.getMessage());
        }
        return null;
    }
}
