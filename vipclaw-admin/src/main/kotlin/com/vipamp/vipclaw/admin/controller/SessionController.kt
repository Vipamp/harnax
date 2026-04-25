package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.SessionCreateRequest
import com.vipamp.vipclaw.admin.dto.SessionResponse
import com.vipamp.vipclaw.admin.service.SessionService
import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.common.page.mapRecords
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * 会话管理控制器
 *
 * @author vipamp
 * @since 2026-03-25
 */
@RestController
@RequestMapping("/admin/sessions")
@Tag(name = "会话管理", description = "会话相关接口")
class SessionController(
    private val sessionService: SessionService
) {

    private val log = LoggerFactory.getLogger(SessionController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取会话列表", description = "分页查询会话信息")
    fun pageSession(
        @Parameter(description = "页码", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1"
        ) pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10"
        ) pageSize: Int?,
        @Parameter(description = "会话名称") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?
    ): ResultVo<Page<SessionResponse>> {
        return try {
            val page = sessionService.page(
                keyword,
                status,
                pageNum ?: 1,
                pageSize ?: 10
            )
            ResultVo.success(page.mapRecords { sessionService.convertToResponse(it) })
        } catch (e: Exception) {
            log.error("获取会话列表失败", e)
            ResultVo.error(e.message ?: "获取会话列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取会话详情", description = "根据会话 ID 获取会话信息")
    fun getSession(
        @Parameter(description = "会话 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<SessionResponse?> {
        return try {
            val session = sessionService.getSession(id)
            ResultVo.success(session?.let { sessionService.convertToResponse(it) })
        } catch (e: Exception) {
            log.error("获取会话详情失败", e)
            ResultVo.error(e.message ?: "获取会话详情失败")
        }
    }

    @GetMapping("/check-title")
    @Operation(summary = "检查会话名称是否存在", description = "检查会话名称是否已存在")
    fun checkSessionTitle(
        @Parameter(description = "会话名称") @RequestParam(name = "title") title: String
    ): ResultVo<Boolean> {
        return try {
            val exists = sessionService.existsByTitle(title)
            ResultVo.success(exists)
        } catch (e: Exception) {
            log.error("检查会话名称失败", e)
            ResultVo.error(e.message ?: "检查会话名称失败")
        }
    }

    @PostMapping
    @Operation(summary = "创建会话", description = "新增会话信息")
    fun createSession(
        @Validated @RequestBody request: SessionCreateRequest
    ): ResultVo<Void> {
        return try {
            if (sessionService.createSession(request)) ResultVo.success() else ResultVo.error("创建会话失败")
        } catch (e: Exception) {
            log.error("创建会话失败", e)
            ResultVo.error(e.message ?: "创建会话失败")
        }
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "切换会话状态", description = "根据会话 ID 切换会话状态")
    fun updateSession(
        @Parameter(description = "会话 ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "会话实体") @RequestBody request: SessionCreateRequest
    ): ResultVo<Void> {
        return try {
            if (sessionService.updateSession(
                    id,
                    request
                )
            ) ResultVo.success() else ResultVo.error("更新会话失败")
        } catch (e: Exception) {
            log.error("更新会话失败", e)
            ResultVo.error(e.message ?: "更新会话失败")
        }
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换会话状态", description = "根据会话 ID 切换会话状态")
    fun toggleSession(
        @Parameter(description = "会话 ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "会话状态") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        return try {
            if (sessionService.toggleSessionStatus(
                    id,
                    status
                )
            ) ResultVo.success() else ResultVo.error("更新会话失败")
        } catch (e: Exception) {
            log.error("更新会话失败", e)
            ResultVo.error(e.message ?: "更新会话失败")
        }
    }


    @DeleteMapping("/{id}")
    @Operation(summary = "删除会话", description = "根据会话 ID 删除会话")
    fun deleteSession(
        @Parameter(description = "会话 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> {
        return try {
            if (sessionService.deleteSession(id)) ResultVo.success() else ResultVo.error("删除会话失败")
        } catch (e: Exception) {
            log.error("删除会话失败", e)
            ResultVo.error(e.message ?: "删除会话失败")
        }
    }
}
