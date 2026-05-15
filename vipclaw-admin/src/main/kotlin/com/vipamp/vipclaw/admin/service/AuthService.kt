package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.LoginRequest
import com.vipamp.vipclaw.admin.dto.LoginResponse

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
     * Logout
     */
    fun logout()
}
