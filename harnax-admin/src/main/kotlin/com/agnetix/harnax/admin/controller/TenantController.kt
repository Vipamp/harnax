package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.request.AddUserToTenantRequest
import com.agnetix.harnax.admin.dto.request.CreateTenantRequest
import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.dto.response.UserTenantResponse
import com.agnetix.harnax.admin.service.TenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.mapper.SysUserMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Tenant management controller
 * Available only for public edition
 */
@RestController
@RequestMapping("/api/tenant")
@Tag(name = "Tenant Management", description = "Tenant related APIs")
class TenantController(
    private val tenantService: TenantService,
    private val jwtUtil: JwtUtil,
    private val sysUserMapper: SysUserMapper,
) {

    private val log = LoggerFactory.getLogger(TenantController::class.java)

    @PostMapping
    @Operation(summary = "Create tenant", description = "Global admin creates a new tenant")
    fun createTenant(
        @Valid @RequestBody request: CreateTenantRequest,
    ): ResultVo<TenantResponse> {
        return try {
            log.info("[TenantController] Starting to create tenant, request: {}", request)

            // Get current logged-in username
            val username = UserContextUtil.getCurrentUsername(jwtUtil)
            log.info("[TenantController] Current logged-in user: {}", username)

            // Query user information
            val currentUser = sysUserMapper.selectByUsername(username)
                ?: return ResultVo.error("User not found")

            if (currentUser.isAdmin != 1) {
                log.error("[TenantController] User is not global admin, userId: {}, isAdmin: {}", currentUser.id, currentUser.isAdmin)
                return ResultVo.error("Only global admin can create tenants")
            }

            val tenant = tenantService.createTenant(request, currentUser.username)
            ResultVo.success(tenant)
        } catch (e: Exception) {
            log.error("Failed to create tenant", e)
            ResultVo.error(e.message ?: "Failed to create tenant")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get tenant details", description = "Get tenant information by ID")
    fun getTenant(
        @Parameter(description = "Tenant ID") @PathVariable id: Long,
    ): ResultVo<TenantResponse> = try {
        val tenant = tenantService.getTenantById(id)
        if (tenant != null) {
            ResultVo.success(tenant)
        } else {
            ResultVo.error("Tenant not found")
        }
    } catch (e: Exception) {
        log.error("Failed to get tenant details", e)
        ResultVo.error(e.message ?: "Failed to get tenant details")
    }

    @GetMapping
    @Operation(summary = "Query tenant list", description = "Paginated query for tenant list")
    fun getTenantList(
        @Parameter(description = "Page number") @RequestParam(defaultValue = "1") pageNum: Int,
        @Parameter(description = "Page size") @RequestParam(defaultValue = "10") pageSize: Int,
        @Parameter(description = "Tenant name") @RequestParam(required = false) name: String?,
        @Parameter(description = "Status") @RequestParam(required = false) status: Int?,
    ): ResultVo<Page<TenantResponse>> = try {
        val page = tenantService.getTenantList(name, status, pageNum, pageSize)
        ResultVo.success(page)
    } catch (e: Exception) {
        log.error("Failed to query tenant list", e)
        ResultVo.error(e.message ?: "Failed to query tenant list")
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Toggle tenant status", description = "Enable/disable tenant")
    fun toggleStatus(
        @Parameter(description = "Tenant ID") @PathVariable id: Long,
    ): ResultVo<Boolean> = try {
        val success = tenantService.toggleStatus(id)
        ResultVo.success(success)
    } catch (e: Exception) {
        log.error("Failed to toggle tenant status", e)
        ResultVo.error(e.message ?: "Failed to toggle tenant status")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete tenant", description = "Logical delete tenant")
    fun deleteTenant(
        @Parameter(description = "Tenant ID") @PathVariable id: Long,
    ): ResultVo<Boolean> {
        return try {
            // Get current logged-in username
            val username = UserContextUtil.getCurrentUsername(jwtUtil)

            // Query user information
            val currentUser = sysUserMapper.selectByUsername(username)
                ?: return ResultVo.error("User not found")

            if (currentUser.isAdmin != 1) {
                return ResultVo.error("Only global admin can delete tenants")
            }

            val success = tenantService.deleteTenant(id)
            ResultVo.success(success)
        } catch (e: Exception) {
            log.error("Failed to delete tenant", e)
            ResultVo.error(e.message ?: "Failed to delete tenant")
        }
    }

    @GetMapping("/{id}/users")
    @Operation(summary = "Query tenant users", description = "Paginated query for user list under tenant")
    fun getTenantUsers(
        @Parameter(description = "Tenant ID") @PathVariable id: Long,
        @Parameter(description = "Page number") @RequestParam(defaultValue = "1") pageNum: Int,
        @Parameter(description = "Page size") @RequestParam(defaultValue = "10") pageSize: Int,
    ): ResultVo<Page<UserTenantResponse>> = try {
        val page = tenantService.getTenantUsers(id, pageNum, pageSize)
        ResultVo.success(page)
    } catch (e: Exception) {
        log.error("Failed to query tenant users", e)
        ResultVo.error(e.message ?: "Failed to query tenant users")
    }

    @PostMapping("/{id}/users")
    @Operation(summary = "Add user to tenant", description = "Add user to specified tenant")
    fun addUserToTenant(
        @Parameter(description = "Tenant ID") @PathVariable id: Long,
        @Valid @RequestBody request: AddUserToTenantRequest,
    ): ResultVo<Boolean> = try {
        val success = tenantService.addUserToTenant(id, request.userId, request.role)
        ResultVo.success(success)
    } catch (e: Exception) {
        log.error("Failed to add user to tenant", e)
        ResultVo.error(e.message ?: "Failed to add user to tenant")
    }

    @DeleteMapping("/{id}/users/{userId}")
    @Operation(summary = "Remove user from tenant", description = "Remove user from specified tenant")
    fun removeUserFromTenant(
        @Parameter(description = "Tenant ID") @PathVariable id: Long,
        @Parameter(description = "User ID") @PathVariable userId: Long,
    ): ResultVo<Boolean> = try {
        val success = tenantService.removeUserFromTenant(id, userId)
        ResultVo.success(success)
    } catch (e: Exception) {
        log.error("Failed to remove user from tenant", e)
        ResultVo.error(e.message ?: "Failed to remove user from tenant")
    }

    @PutMapping("/{id}/users/{userId}/role")
    @Operation(summary = "Update user role", description = "Update user role in tenant")
    fun updateUserRole(
        @Parameter(description = "Tenant ID") @PathVariable id: Long,
        @Parameter(description = "User ID") @PathVariable userId: Long,
        @RequestBody request: UpdateUserRoleRequest,
    ): ResultVo<Boolean> = try {
        val success = tenantService.updateUserRole(id, userId, request.role)
        ResultVo.success(success)
    } catch (e: Exception) {
        log.error("Failed to update user role", e)
        ResultVo.error(e.message ?: "Failed to update user role")
    }

    data class UpdateUserRoleRequest(
        val role: String,
    )
}
