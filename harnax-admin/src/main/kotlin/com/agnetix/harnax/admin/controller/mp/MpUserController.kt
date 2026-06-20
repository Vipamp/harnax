package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpChangePasswordRequest
import com.agnetix.harnax.admin.dto.mp.MpUserProfileResponse
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpUserService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@Tag(name = "MP User", description = "Mobile user profile APIs")
@RestController
@RequestMapping("/api/mp/user")
class MpUserController(
    private val mpUserService: MpUserService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/profile")
    @Operation(summary = "Get profile", description = "Get current user's profile")
    fun getProfile(): ResultVo<MpUserProfileResponse> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        return ResultVo.success(mpUserService.getProfile(user.id))
    }

    @PutMapping("/password")
    @Operation(summary = "Change password", description = "Change current user's password")
    fun changePassword(
        @Valid @RequestBody request: MpChangePasswordRequest,
    ): ResultVo<Void> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        mpUserService.changePassword(user.id, request)
        return ResultVo.success()
    }
}
