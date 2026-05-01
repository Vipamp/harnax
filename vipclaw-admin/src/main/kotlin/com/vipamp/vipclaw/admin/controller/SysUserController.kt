package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserResponse
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.config.EditionUtil
import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.service.SysUserService
import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.common.page.mapRecords
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * 用户管理控制器
 * 仅企业版和公有云版可用
 *
 * @author vipamp
 * @since 2026-03-06
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理", description = "用户相关接口")
@RequiresEdition("enterprise", "public")
class SysUserController(
    private val sysUserService: SysUserService,
    private val editionUtil: EditionUtil
) {

    private val log = LoggerFactory.getLogger(SysUserController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取用户列表", description = "分页查询用户信息")
    fun pageSysUser(
        @Parameter(description = "页码", example = "1") @RequestParam(
            name = "pageNum", defaultValue = "1"
        ) pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(
            name = "pageSize", defaultValue = "10"
        ) pageSize: Int?,
        @Parameter(description = "模糊查询字段") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "租户ID过滤") @RequestParam(name = "tenantId", required = false) tenantId: Long?
    ): ResultVo<Page<SysUserResponse>> {
        return try {
            val page = sysUserService.page(
                keyword, status, tenantId, pageNum ?: 1, pageSize ?: 10
            )
            ResultVo.success(page.mapRecords { sysUserService.convertToResponse(it) })
        } catch (e: Exception) {
            log.error("获取用户列表失败", e)
            ResultVo.error(e.message ?: "获取用户列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情", description = "根据用户 ID 获取用户信息")
    fun getSysUser(
        @Parameter(description = "用户 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<SysUserResponse?> {
        return try {
            val user = sysUserService.getSysUser(id)
            ResultVo.success(user?.let { sysUserService.convertToResponse(it) })
        } catch (e: Exception) {
            log.error("获取用户详情失败", e)
            ResultVo.error(e.message ?: "获取用户详情失败")
        }
    }

    @PostMapping
    @Operation(summary = "创建用户", description = "新增用户信息")
    fun createUser(
        @Valid @RequestBody request: SysUserCreateRequest
    ): ResultVo<Void> {
        return try {
            // 传递版本信息，个人版不强制要求 email 和 phone
            if (sysUserService.createUser(request, editionUtil.isPersonal())) ResultVo.success() else ResultVo.error("创建用户失败")
        } catch (e: Exception) {
            log.error("创建用户失败", e)
            ResultVo.error(e.message ?: "创建用户失败")
        }
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "更新用户", description = "根据用户 ID 更新用户信息")
    fun updateSysUser(
        @Parameter(description = "用户 ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SysUserUpdateRequest
    ): ResultVo<Void> {
        return try {
            // 传递版本信息，个人版不进行必填校验
            if (sysUserService.updateUser(id, request, editionUtil.isPersonal())) ResultVo.success() else ResultVo.error("更新用户失败")
        } catch (e: Exception) {
            log.error("更新用户失败", e)
            ResultVo.error(e.message ?: "更新用户失败")
        }
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换用户状态", description = "根据用户 ID 切换用户状态")
    fun toggleSysUser(
        @Parameter(description = "用户 ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "用户状态") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        return try {
            if (sysUserService.toggleUserStatus(id, status)) ResultVo.success() else ResultVo.error("更新用户失败")
        } catch (e: Exception) {
            log.error("更新用户失败", e)
            ResultVo.error(e.message ?: "更新用户失败")
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户", description = "根据用户 ID 删除用户")
    fun deleteSysUser(
        @Parameter(description = "用户 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> {
        return try {
            if (sysUserService.deleteUser(id)) ResultVo.success() else ResultVo.error("删除用户失败")
        } catch (e: Exception) {
            log.error("删除用户失败", e)
            ResultVo.error(e.message ?: "删除用户失败")
        }
    }

    @GetMapping("/check/username")
    @Operation(summary = "检查用户名是否存在", description = "检查用户名是否已被注册")
    fun checkUsername(
        @Parameter(description = "用户名") @RequestParam(name = "username") username: String
    ): ResultVo<Boolean> {
        return try {
            val exists = sysUserService.existsByUsername(username)
            ResultVo.success(exists)
        } catch (e: Exception) {
            log.error("检查用户名失败", e)
            ResultVo.error(e.message ?: "检查用户名失败")
        }
    }

    @GetMapping("/check/phone")
    @Operation(summary = "检查手机号是否存在", description = "检查手机号是否已被注册")
    fun checkPhone(
        @Parameter(description = "手机号") @RequestParam(name = "phone") phone: String
    ): ResultVo<Boolean> {
        return try {
            val exists = sysUserService.existsByPhone(phone)
            ResultVo.success(exists)
        } catch (e: Exception) {
            log.error("检查手机号失败", e)
            ResultVo.error(e.message ?: "检查手机号失败")
        }
    }

    @GetMapping("/check/email")
    @Operation(summary = "检查邮箱是否存在", description = "检查邮箱是否已被注册")
    fun checkEmail(
        @Parameter(description = "邮箱") @RequestParam(name = "email") email: String
    ): ResultVo<Boolean> {
        return try {
            val exists = sysUserService.existsByEmail(email)
            ResultVo.success(exists)
        } catch (e: Exception) {
            log.error("检查邮箱失败", e)
            ResultVo.error(e.message ?: "检查邮箱失败")
        }
    }
}
