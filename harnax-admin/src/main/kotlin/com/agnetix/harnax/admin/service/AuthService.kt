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
     * Logout
     */
    fun logout()
}
