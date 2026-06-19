package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.LoginResponse

/**
 * Authentication service interface
 */
interface AuthService {

    /**
     * User login
     * @param request Login request
     * @return Login response
     */
    fun login(request: LoginRequest): LoginResponse

    /**
     * Mobile login (no captcha required)
     * @param request Login request with SHA-256 hashed password
     * @return Login response
     */
    fun mobileLogin(request: LoginRequest): LoginResponse

    /**
     * Logout
     */
    fun logout()
}
