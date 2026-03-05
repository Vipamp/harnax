package com.vipclaw.admin.service.impl;

import com.vipclaw.admin.dto.LoginRequest;
import com.vipclaw.admin.dto.LoginResponse;
import com.vipclaw.admin.entity.SysUser;
import com.vipclaw.admin.exception.BizException;
import com.vipclaw.admin.service.AuthService;
import com.vipclaw.admin.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现类
 * 
 * TODO: 实际项目中需要集成 JWT、Redis 等实现真正的认证和令牌管理
 * 这里提供简化的实现用于演示
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserService sysUserService;

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
        
        // 2. 检查用户状态
        if (user.getStatus() == 0) {
            throw new BizException("用户已被禁用，请联系管理员");
        }
        
        // 3. 生成访问令牌（简化实现，实际应使用 JWT）
        String accessToken = generateToken(user);
        
        // 4. 构建响应
        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .userId(user.getUserId())
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
                .expiresIn(7200L) // 2 小时
                .userInfo(userInfo)
                .build();
        
        log.info("用户登录成功，userId: {}, username: {}", user.getUserId(), user.getUsername());
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
        log.info("用户退出登录");
        // TODO: 实际项目中需要使 Token 失效
    }

    /**
     * 生成访问令牌
     * TODO: 实际项目中应使用 JWT 生成令牌
     */
    private String generateToken(SysUser user) {
        // 简化实现，返回一个模拟的 token
        return "mock_token_" + user.getUserId() + "_" + System.currentTimeMillis();
    }
}
