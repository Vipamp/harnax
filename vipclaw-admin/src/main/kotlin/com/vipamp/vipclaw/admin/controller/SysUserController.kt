package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserResponse
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.service.SysUserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * 用户管理控制器
 *
 * @author vipamp
 * @since 2026-03-06
 */
@RestController
@RequestMapping("/admin/users")
@Tag(name = "用户管理", description = "用户相关接口")
class SysUserController(
    private val sysUserService: SysUserService
) {

    private val log = LoggerFactory.getLogger(SysUserController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取用户列表", description = "分页查询用户信息")
    fun getUserPage(
        @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "模糊查询字段") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?
    ): ResultVo<Page<SysUserResponse>> {
        return try {
            val page = sysUserService.getUserPage(keyword, status, pageNum ?: 1, pageSize ?: 10)
            val responsePage = convertToResponsePage(page)
            ResultVo.success(responsePage)
        } catch (e: Exception) {
            log.error("获取用户列表失败", e)
            ResultVo.error(e.message ?: "获取用户列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情", description = "根据用户 ID 获取用户信息")
    fun getUserById(
        @Parameter(description = "用户 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<SysUserResponse> {
        return try {
            val user = sysUserService.getUserById(id)
            ResultVo.success(SysUserResponse.fromEntity(user))
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
            if (sysUserService.createUser(request)) ResultVo.success() else ResultVo.error("创建用户失败")
        } catch (e: Exception) {
            log.error("创建用户失败", e)
            ResultVo.error(e.message ?: "创建用户失败")
        }
    }

    @PutMapping("/update/{userId}")
    @Operation(summary = "更新用户", description = "根据用户 ID 更新用户信息")
    fun updateUser(
        @Parameter(description = "用户 ID") @PathVariable(name = "userId") userId: Long,
        @Valid @RequestBody request: SysUserUpdateRequest
    ): ResultVo<Void> {
        return try {
            if (sysUserService.updateUser(userId, request)) ResultVo.success() else ResultVo.error("更新用户失败")
        } catch (e: Exception) {
            log.error("更新用户失败", e)
            ResultVo.error(e.message ?: "更新用户失败")
        }
    }

    @PutMapping("/toggle/{userId}")
    @Operation(summary = "切换用户状态", description = "根据用户 ID 切换用户状态")
    fun toggleUser(
        @Parameter(description = "用户 ID") @PathVariable(name = "userId") userId: Long,
        @Parameter(description = "用户状态") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        return try {
            if (sysUserService.toggleUserStatus(userId, status)) ResultVo.success() else ResultVo.error("更新用户失败")
        } catch (e: Exception) {
            log.error("更新用户失败", e)
            ResultVo.error(e.message ?: "更新用户失败")
        }
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "删除用户", description = "根据用户 ID 删除用户")
    fun deleteUser(
        @Parameter(description = "用户 ID") @PathVariable(name = "userId") userId: Long
    ): ResultVo<Void> {
        return try {
            if (sysUserService.deleteUser(userId)) ResultVo.success() else ResultVo.error("删除用户失败")
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

    /**
     * 分页结果转换
     */
    private fun convertToResponsePage(page: Page<com.vipamp.vipclaw.admin.entity.SysUser>): Page<SysUserResponse> {
        val responsePage = Page<SysUserResponse>(page.current, page.size)
        responsePage.total = page.total
        responsePage.size = page.size
        responsePage.current = page.current
        responsePage.pages = page.pages
        responsePage.records = page.records.map { SysUserResponse.fromEntity(it) }
        return responsePage
    }
}
