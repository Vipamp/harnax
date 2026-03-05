package com.vipclaw.admin.service;

import com.vipclaw.admin.dto.LoginRequest;
import com.vipclaw.admin.dto.LoginResponse;

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
     * 获取当前登录用户信息
     * @return 用户信息
     */
    LoginResponse.UserInfo getCurrentUser();

    /**
     * 退出登录
     */
    void logout();
}
