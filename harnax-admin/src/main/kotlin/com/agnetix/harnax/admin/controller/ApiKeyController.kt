package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ApiKeyCreateRequest
import com.agnetix.harnax.admin.dto.ApiKeyCreatedResponse
import com.agnetix.harnax.admin.dto.ApiKeyResponse
import com.agnetix.harnax.admin.dto.ApiKeyUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/api-keys")
@Tag(name = "API Key Management", description = "External API Key management APIs")
class ApiKeyController(
    private val apiKeyService: ApiKeyService,
) {

    private val log = LoggerFactory.getLogger(ApiKeyController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get API Key list with pagination")
    fun page(
        @Parameter(description = "Page number") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "Page size") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "Search keyword") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "Enabled filter") @RequestParam(name = "enabled", required = false) enabled: Int?,
    ): ResultVo<Page<ApiKeyResponse>> = try {
        val currentUser = SecurityUtils.getCurrentUser()
        val admin = currentUser != null && currentUser.isAdmin == 1
        val creator = if (admin) null else currentUser?.username
        val tenantId = if (admin) null else (TenantContext.getTenantId() ?: currentUser?.tenantId)
        val page = apiKeyService.page(keyword, enabled, creator, tenantId, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { apiKeyService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get API Key list", e)
        ResultVo.error(e.message ?: "Failed to get API Key list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get API Key details")
    fun get(
        @Parameter(description = "API Key ID") @PathVariable("id") id: Long,
    ): ResultVo<ApiKeyResponse?> = try {
        val entity = apiKeyService.getApiKey(id)
        ResultVo.success(entity?.let { apiKeyService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get API Key details", e)
        ResultVo.error(e.message ?: "Failed to get API Key details")
    }

    @PostMapping
    @Operation(summary = "Create API Key", description = "Returns the raw key only once. Save it immediately.")
    fun create(
        @Validated @RequestBody request: ApiKeyCreateRequest,
    ): ResultVo<ApiKeyCreatedResponse> = try {
        val result = apiKeyService.createApiKey(request)
        ResultVo.success(result)
    } catch (e: Exception) {
        log.error("Failed to create API Key", e)
        ResultVo.error(e.message ?: "Failed to create API Key")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update API Key")
    fun update(
        @Parameter(description = "API Key ID") @PathVariable("id") id: Long,
        @Validated @RequestBody request: ApiKeyUpdateRequest,
    ): ResultVo<Void> = try {
        if (apiKeyService.updateApiKey(id, request)) ResultVo.success() else ResultVo.error("Failed to update API Key")
    } catch (e: Exception) {
        log.error("Failed to update API Key", e)
        ResultVo.error(e.message ?: "Failed to update API Key")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle API Key enabled status")
    fun toggle(
        @Parameter(description = "API Key ID") @PathVariable("id") id: Long,
        @Parameter(description = "Enabled status") @RequestParam("enabled") enabled: Int,
    ): ResultVo<Void> = try {
        if (apiKeyService.toggleEnabled(id, enabled)) ResultVo.success() else ResultVo.error("Failed to toggle API Key")
    } catch (e: Exception) {
        log.error("Failed to toggle API Key", e)
        ResultVo.error(e.message ?: "Failed to toggle API Key")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete API Key")
    fun delete(
        @Parameter(description = "API Key ID") @PathVariable("id") id: Long,
    ): ResultVo<Void> = try {
        if (apiKeyService.deleteApiKey(id)) ResultVo.success() else ResultVo.error("Failed to delete API Key")
    } catch (e: Exception) {
        log.error("Failed to delete API Key", e)
        ResultVo.error(e.message ?: "Failed to delete API Key")
    }

    @PostMapping("/{id}/regenerate")
    @Operation(summary = "Regenerate API Key", description = "Generates a new raw key. The old key is invalidated.")
    fun regenerate(
        @Parameter(description = "API Key ID") @PathVariable("id") id: Long,
    ): ResultVo<ApiKeyCreatedResponse> = try {
        val result = apiKeyService.regenerateApiKey(id)
        ResultVo.success(result)
    } catch (e: Exception) {
        log.error("Failed to regenerate API Key", e)
        ResultVo.error(e.message ?: "Failed to regenerate API Key")
    }
}
