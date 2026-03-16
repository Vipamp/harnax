package com.vipamp.vipclaw.admin.controller;

import com.vipamp.vipclaw.admin.dto.CaptchaResponse;
import com.vipamp.vipclaw.admin.dto.LoginRequest;
import com.vipamp.vipclaw.admin.dto.LoginResponse;
import com.vipamp.vipclaw.admin.service.AuthService;
import com.vipamp.vipclaw.admin.service.CaptchaService;
import com.vipamp.vipclaw.admin.vo.Result;
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
    private final CaptchaService captchaService;

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
     * 退出登录
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "用户退出登录")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    /**
     * 获取验证码
     */
    @GetMapping("/captcha")
    @Operation(summary = "获取验证码", description = "获取图形验证码图片")
    public Result<CaptchaResponse> getCaptcha() {
        try {
            CaptchaResponse captchaResponse = captchaService.generateCaptcha();
            return Result.success(captchaResponse);
        } catch (Exception e) {
            log.error("获取验证码失败", e);
            return Result.error(e.getMessage());
        }
    }
}
