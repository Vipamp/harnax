package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.request.AddUserToTenantRequest
import com.vipamp.vipclaw.admin.dto.request.CreateTenantRequest
import com.vipamp.vipclaw.admin.dto.request.UpdateTenantRequest
import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.dto.response.UserTenantResponse
import com.vipamp.vipclaw.admin.security.SecurityUtils
import com.vipamp.vipclaw.admin.service.TenantService
import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.common.page.mapRecords
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * 租户管理控制器
 *
 * @author vipamp
 * @since 2026-04-28
 */
@RestController
@RequestMapping("/api/tenant")
@Tag(name = "租户管理", description = "租户相关接口")
class TenantController(
    private val tenantService: TenantService
) {

    private val log = LoggerFactory.getLogger(TenantController::class.java)

    @PostMapping
    @Operation(summary = "创建租户", description = "全局管理员创建新租户")
    fun createTenant(
        @Valid @RequestBody request: CreateTenantRequest
    ): ResultVo<TenantResponse> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("用户未登录")

            if (currentUser.isAdmin != 1) {
                return ResultVo.error("仅全局管理员可以创建租户")
            }

            val tenant = tenantService.createTenant(request, currentUser.username)
            ResultVo.success(tenant)
        } catch (e: Exception) {
            log.error("创建租户失败", e)
            ResultVo.error(e.message ?: "创建租户失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取租户详情", description = "根据ID获取租户信息")
    fun getTenant(
        @Parameter(description = "租户ID") @PathVariable id: Long
    ): ResultVo<TenantResponse> {
        return try {
            val tenant = tenantService.getTenantById(id)
            if (tenant != null) {
                ResultVo.success(tenant)
            } else {
                ResultVo.error("租户不存在")
            }
        } catch (e: Exception) {
            log.error("获取租户详情失败", e)
            ResultVo.error(e.message ?: "获取租户详情失败")
        }
    }

    @GetMapping
    @Operation(summary = "查询租户列表", description = "分页查询租户列表")
    fun getTenantList(
        @Parameter(description = "页码") @RequestParam(defaultValue = "1") pageNum: Int,
        @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") pageSize: Int,
        @Parameter(description = "租户名称") @RequestParam(required = false) name: String?,
        @Parameter(description = "状态") @RequestParam(required = false) status: Int?
    ): ResultVo<Page<TenantResponse>> {
        return try {
            val page = tenantService.getTenantList(name, status, pageNum, pageSize)
            ResultVo.success(page)
        } catch (e: Exception) {
            log.error("查询租户列表失败", e)
            ResultVo.error(e.message ?: "查询租户列表失败")
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新租户", description = "更新租户信息")
    fun updateTenant(
        @Parameter(description = "租户ID") @PathVariable id: Long,
        @Valid @RequestBody request: UpdateTenantRequest
    ): ResultVo<Boolean> {
        return try {
            val success = tenantService.updateTenant(id, request)
            ResultVo.success(success)
        } catch (e: Exception) {
            log.error("更新租户失败", e)
            ResultVo.error(e.message ?: "更新租户失败")
        }
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "切换租户状态", description = "启用/禁用租户")
    fun toggleStatus(
        @Parameter(description = "租户ID") @PathVariable id: Long
    ): ResultVo<Boolean> {
        return try {
            val success = tenantService.toggleStatus(id)
            ResultVo.success(success)
        } catch (e: Exception) {
            log.error("切换租户状态失败", e)
            ResultVo.error(e.message ?: "切换租户状态失败")
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除租户", description = "逻辑删除租户")
    fun deleteTenant(
        @Parameter(description = "租户ID") @PathVariable id: Long
    ): ResultVo<Boolean> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("用户未登录")

            if (currentUser.isAdmin != 1) {
                return ResultVo.error("仅全局管理员可以删除租户")
            }

            val success = tenantService.deleteTenant(id)
            ResultVo.success(success)
        } catch (e: Exception) {
            log.error("删除租户失败", e)
            ResultVo.error(e.message ?: "删除租户失败")
        }
    }

    @GetMapping("/{id}/users")
    @Operation(summary = "查询租户下用户", description = "分页查询租户下的用户列表")
    fun getTenantUsers(
        @Parameter(description = "租户ID") @PathVariable id: Long,
        @Parameter(description = "页码") @RequestParam(defaultValue = "1") pageNum: Int,
        @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") pageSize: Int
    ): ResultVo<Page<UserTenantResponse>> {
        return try {
            val page = tenantService.getTenantUsers(id, pageNum, pageSize)
            ResultVo.success(page)
        } catch (e: Exception) {
            log.error("查询租户下用户失败", e)
            ResultVo.error(e.message ?: "查询租户下用户失败")
        }
    }

    @PostMapping("/{id}/users")
    @Operation(summary = "添加用户到租户", description = "将用户添加到指定租户")
    fun addUserToTenant(
        @Parameter(description = "租户ID") @PathVariable id: Long,
        @Valid @RequestBody request: AddUserToTenantRequest
    ): ResultVo<Boolean> {
        return try {
            val success = tenantService.addUserToTenant(id, request.userId, request.role)
            ResultVo.success(success)
        } catch (e: Exception) {
            log.error("添加用户到租户失败", e)
            ResultVo.error(e.message ?: "添加用户到租户失败")
        }
    }

    @DeleteMapping("/{id}/users/{userId}")
    @Operation(summary = "从租户移除用户", description = "从指定租户中移除用户")
    fun removeUserFromTenant(
        @Parameter(description = "租户ID") @PathVariable id: Long,
        @Parameter(description = "用户ID") @PathVariable userId: Long
    ): ResultVo<Boolean> {
        return try {
            val success = tenantService.removeUserFromTenant(id, userId)
            ResultVo.success(success)
        } catch (e: Exception) {
            log.error("从租户移除用户失败", e)
            ResultVo.error(e.message ?: "从租户移除用户失败")
        }
    }
}
