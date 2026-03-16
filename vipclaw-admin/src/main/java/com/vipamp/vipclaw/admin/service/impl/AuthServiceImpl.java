package com.vipamp.vipclaw.admin.service.impl;

import com.vipamp.vipclaw.admin.dto.LoginRequest;
import com.vipamp.vipclaw.admin.dto.LoginResponse;
import com.vipamp.vipclaw.admin.entity.SysUser;
import com.vipamp.vipclaw.admin.exception.BizException;
import com.vipamp.vipclaw.admin.service.AuthService;
import com.vipamp.vipclaw.admin.service.CaptchaService;
import com.vipamp.vipclaw.admin.service.SysTokenBlacklistService;
import com.vipamp.vipclaw.admin.service.SysUserService;
import com.vipamp.vipclaw.admin.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现类
 * <p>
 * TODO: 实际项目中需要集成 JWT、Redis 等实现真正的认证和令牌管理
 * 这里提供简化的实现用于演示
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserService sysUserService;
    private final CaptchaService captchaService;
    private final JwtUtil jwtUtil;
    private final SysTokenBlacklistService tokenBlacklistService;

    @Override
    public LoginResponse login(LoginRequest request) {
        log.info("用户登录，username: {}", request.getUsername());

        // 1. 验证用户名和密码
        SysUser user = sysUserService.getByUsername(request.getUsername());
        if (user == null) {
            throw new BizException("用户名或密码错误");
        }

        // TODO: 实际项目中应该使用加密后的密码进行比对
        if (!request.getPassword().equals(user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }

        // 2. 校验验证码
        String captcha = request.getCaptcha();
        String captchaKey = request.getCaptchaKey();
        if (captcha == null || captcha.trim().isEmpty()) {
            throw new BizException("请输入验证码");
        }
        if (captchaKey == null || captchaKey.trim().isEmpty()) {
            throw new BizException("验证码 key 不能为空");
        }
        if (!captchaService.validateCaptcha(captchaKey, captcha)) {
            throw new BizException("验证码错误，请重新输入");
        }

        // 3. 检查用户状态
        if (user.getStatus() == 0) {
            throw new BizException("用户已被禁用，请联系管理员");
        }

        // 4. 生成 JWT Token
        String accessToken = jwtUtil.generateToken(user.getId(), user.getUsername());

        // 5. 计算过期时间戳
        long expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationTime();

        // 6. 构建响应
        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .email(user.getEmail())
                .phone(user.getPhone())
                .gender(user.getGender())
                .build();

        LoginResponse response = LoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getExpirationTime() / 1000) // 转换为秒
                .expiresAt(expiresAt)
                .userInfo(userInfo)
                .build();

        log.info("用户登录成功，userId: {}, username: {}", user.getId(), user.getUsername());
        return response;
    }

    @Override
    public LoginResponse.UserInfo getCurrentUser() {
        // TODO: 实际项目中应从 Token 中解析用户信息
        // 这里返回一个示例用户
        return LoginResponse.UserInfo.builder()
                .userId(1L)
                .username("admin")
                .nickname("管理员")
                .avatar("https://example.com/avatar.jpg")
                .email("admin@example.com")
                .phone("13800138000")
                .gender(1)
                .build();
    }

    @Override
    public void logout() {
        // 获取当前请求（需要从 RequestContextHolder 中获取）
        String token = getCurrentToken();
        if (token != null) {
            try {
                // 解析 Token 获取用户信息
                Long userId = jwtUtil.getUserIdFromToken(token);
                String username = jwtUtil.getUsernameFromToken(token);

                // 计算过期时间
                LocalDateTime expireTime = LocalDateTime.now().plusNanos(jwtUtil.getExpirationTime() * 1000000);

                // 将 Token 加入 MySQL 黑名单
                tokenBlacklistService.addToBlacklist(token, username, userId, expireTime, "logout");
                log.info("用户退出登录，userId: {}, username: {}", userId, username);
            } catch (Exception e) {
                // Token 无效或已过期，直接记录退出
                log.warn("退出登录时 Token 无效或已过期：{}", e.getMessage());
            }
        } else {
            log.info("用户退出登录（未携带 Token）");
        }
    }

    /**
     * 从当前请求中获取 Token
     */
    private String getCurrentToken() {
        try {
            jakarta.servlet.http.HttpServletRequest request = ((org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).getRequest();
            String bearerToken = request.getHeader("Authorization");
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                return bearerToken.substring(7);
            }
        } catch (Exception e) {
            log.error("获取当前 Token 失败：{}", e.getMessage());
        }
        return null;
    }
}
