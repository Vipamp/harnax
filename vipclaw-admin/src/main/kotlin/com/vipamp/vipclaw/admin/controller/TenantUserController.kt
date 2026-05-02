package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.security.SecurityUtils
import com.vipamp.vipclaw.admin.service.UserTenantService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * 租户用户管理控制器
 * 管理租户与用户的关联关系
 * 仅公有云版可用(多租户功能)
 *
 * @author vipamp
 * @since 2026-04-28
 */
@RestController
@RequestMapping("/api/tenant")
@RequiresEdition("public")
class TenantUserController(
    private val userTenantService: UserTenantService
) {
    private val log = LoggerFactory.getLogger(TenantUserController::class.java)

    /**
     * 获取租户下的用户列表
     */
    @GetMapping("/{tenantId}/users")
    fun getTenantUsers(
        @PathVariable tenantId: Long,
        @RequestParam(defaultValue = "1") pageNum: Int,
        @RequestParam(defaultValue = "10") pageSize: Int
    ): ResultVo<Any> {
        return try {
            val result = userTenantService.getUsersByTenantId(tenantId, pageNum, pageSize)
            ResultVo.success(result)
        } catch (e: Exception) {
            log.error("获取租户用户列表失败: tenantId={}", tenantId, e)
            ResultVo.error(e.message ?: "获取租户用户列表失败")
        }
    }

    /**
     * 添加用户到租户
     */
    @PostMapping("/{tenantId}/users")
    fun addUserToTenant(
        @PathVariable tenantId: Long,
        @RequestBody request: AddUserToTenantRequest
    ): ResultVo<Any> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("用户未登录")

            val result = userTenantService.addUserToTenant(
                tenantId,
                request.userId,
                request.role ?: "member",
                currentUser.username
            )
            ResultVo.success(result)
        } catch (e: Exception) {
            log.error("添加用户到租户失败: tenantId={}, userId={}", tenantId, request.userId, e)
            ResultVo.error(e.message ?: "添加用户到租户失败")
        }
    }

    /**
     * 从租户移除用户
     */
    @DeleteMapping("/{tenantId}/users/{userId}")
    fun removeUserFromTenant(
        @PathVariable tenantId: Long,
        @PathVariable userId: Long
    ): ResultVo<Any> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("用户未登录")

            val result = userTenantService.removeUserFromTenant(
                tenantId,
                userId,
                currentUser.username
            )
            ResultVo.success(result)
        } catch (e: Exception) {
            log.error("从租户移除用户失败: tenantId={}, userId={}", tenantId, userId, e)
            ResultVo.error(e.message ?: "从租户移除用户失败")
        }
    }

    /**
     * 添加用户到租户请求
     */
    data class AddUserToTenantRequest(
        val userId: Long,
        val role: String? = "member"
    )
}
