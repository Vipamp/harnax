package com.vipamp.vipclaw.admin.service;

import com.vipamp.vipclaw.admin.dto.LoginRequest;
import com.vipamp.vipclaw.admin.dto.LoginResponse;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 用户登录
     * @param request 登录请求
     * @return 登录响应
     */
    LoginResponse login(LoginRequest request);

    /**
     * 退出登录
     */
    void logout();
}
