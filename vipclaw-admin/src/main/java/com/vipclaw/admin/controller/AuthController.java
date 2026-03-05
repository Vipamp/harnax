package com.vipclaw.admin.controller;

import com.vipclaw.admin.dto.LoginRequest;
import com.vipclaw.admin.dto.LoginResponse;
import com.vipclaw.admin.service.AuthService;
import com.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理", description = "用户登录、登出等认证相关接口")
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "用户名密码登录")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return Result.success(response);
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/currentUser")
    @Operation(summary = "获取当前用户信息", description = "获取已登录用户的详细信息")
    public Result<LoginResponse.UserInfo> getCurrentUser() {
        LoginResponse.UserInfo userInfo = authService.getCurrentUser();
        return Result.success(userInfo);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "用户退出登录")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
