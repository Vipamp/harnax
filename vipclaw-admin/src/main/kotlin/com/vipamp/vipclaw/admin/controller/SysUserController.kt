package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.EditionUtil
import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserResponse
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.dto.mapRecords
import com.vipamp.vipclaw.admin.service.SysUserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * User management controller
 * Available only for enterprise and public editions
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "User Management", description = "User related APIs")
@RequiresEdition("enterprise", "public")
class SysUserController(
    private val sysUserService: SysUserService,
    private val editionUtil: EditionUtil,
) {

    private val log = LoggerFactory.getLogger(SysUserController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get user list with pagination", description = "Paginated query for user information")
    fun pageSysUser(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Keyword for fuzzy search") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "Tenant ID filter") @RequestParam(name = "tenantId", required = false) tenantId: Long?,
    ): ResultVo<Page<SysUserResponse>> = try {
        val page = sysUserService.page(
            keyword,
            status,
            tenantId,
            pageNum ?: 1,
            pageSize ?: 10,
        )
        ResultVo.success(page.mapRecords { sysUserService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get user list", e)
        ResultVo.error(e.message ?: "Failed to get user list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user details", description = "Get user information by user ID")
    fun getSysUser(
        @Parameter(description = "User ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SysUserResponse?> = try {
        val user = sysUserService.getSysUser(id)
        ResultVo.success(user?.let { sysUserService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get user details", e)
        ResultVo.error(e.message ?: "Failed to get user details")
    }

    @PostMapping
    @Operation(summary = "Create user", description = "Add new user information")
    fun createUser(
        @Valid @RequestBody request: SysUserCreateRequest,
    ): ResultVo<Void> = try {
        // Pass edition information, personal edition does not require email and phone
        if (sysUserService.createUser(request, editionUtil.isPersonal())) ResultVo.success() else ResultVo.error("Failed to create user")
    } catch (e: Exception) {
        log.error("Failed to create user", e)
        ResultVo.error(e.message ?: "Failed to create user")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update user", description = "Update user information by user ID")
    fun updateSysUser(
        @Parameter(description = "User ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SysUserUpdateRequest,
    ): ResultVo<Void> = try {
        // Pass edition information, personal edition does not perform required field validation
        if (sysUserService.updateUser(id, request, editionUtil.isPersonal())) ResultVo.success() else ResultVo.error("Failed to update user")
    } catch (e: Exception) {
        log.error("Failed to update user", e)
        ResultVo.error(e.message ?: "Failed to update user")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle user status", description = "Toggle user status by user ID")
    fun toggleSysUser(
        @Parameter(description = "User ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "User status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (sysUserService.toggleUserStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle user status")
    } catch (e: Exception) {
        log.error("Failed to update user", e)
        ResultVo.error(e.message ?: "Failed to update user")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete user", description = "Delete user by user ID")
    fun deleteSysUser(
        @Parameter(description = "User ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (sysUserService.deleteUser(id)) ResultVo.success() else ResultVo.error("Failed to delete user")
    } catch (e: Exception) {
        log.error("Failed to delete user", e)
        ResultVo.error(e.message ?: "Failed to delete user")
    }

    @GetMapping("/check/username")
    @Operation(summary = "Check username", description = "Check if username is already registered")
    fun checkUsername(
        @Parameter(description = "Username") @RequestParam(name = "username") username: String,
    ): ResultVo<Boolean> = try {
        val exists = sysUserService.existsByUsername(username)
        ResultVo.success(exists)
    } catch (e: Exception) {
        log.error("Failed to check username", e)
        ResultVo.error(e.message ?: "Failed to check username")
    }

    @GetMapping("/check/phone")
    @Operation(summary = "Check phone", description = "Check if phone is already registered")
    fun checkPhone(
        @Parameter(description = "Phone number") @RequestParam(name = "phone") phone: String,
    ): ResultVo<Boolean> = try {
        val exists = sysUserService.existsByPhone(phone)
        ResultVo.success(exists)
    } catch (e: Exception) {
        log.error("Failed to check phone", e)
        ResultVo.error(e.message ?: "Failed to check phone")
    }

    @GetMapping("/check/email")
    @Operation(summary = "Check email", description = "Check if email is already registered")
    fun checkEmail(
        @Parameter(description = "Email") @RequestParam(name = "email") email: String,
    ): ResultVo<Boolean> = try {
        val exists = sysUserService.existsByEmail(email)
        ResultVo.success(exists)
    } catch (e: Exception) {
        log.error("Failed to check email", e)
        ResultVo.error(e.message ?: "Failed to check email")
    }
}
