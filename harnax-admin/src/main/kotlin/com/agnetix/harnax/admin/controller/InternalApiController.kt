package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val apiKeyMapper: ApiKeyMapper,
    private val sessionMapper: SessionMapper,
    private val modelMapper: ModelMapper,
) {

    private val log = LoggerFactory.getLogger(InternalApiController::class.java)

    data class ApiKeyValidateRequest(val keyHash: String)

    data class ApiKeyValidateResponse(
        val name: String,
        val keyHash: String,
        val scopes: String,
        val tenantId: Long?,
        val rateLimit: Int,
        val enabled: Boolean,
        val expiresAt: String?,
    )

    data class SessionInfoResponse(
        val sessionId: String,
        val agentId: Long?,
        val agentName: String?,
        val modelId: Long?,
        val modelName: String?,
        val tenantId: Long?,
    )

    @PostMapping("/api-keys/validate")
    fun validateApiKey(@RequestBody request: ApiKeyValidateRequest): ResultVo<ApiKeyValidateResponse?> {
        val entity = apiKeyMapper.selectByKeyHash(request.keyHash)
        if (entity == null) {
            log.debug("API key not found for hash: ${request.keyHash.take(16)}...")
            return ResultVo.success(null)
        }

        val response = ApiKeyValidateResponse(
            name = entity.name,
            keyHash = entity.keyHash,
            scopes = entity.scopes,
            tenantId = entity.tenantId,
            rateLimit = entity.rateLimit,
            enabled = entity.enabled == 1,
            expiresAt = entity.expiresAt?.toString(),
        )
        return ResultVo.success(response)
    }

    @GetMapping("/sessions/{sessionId}/info")
    fun getSessionInfo(@PathVariable sessionId: String): ResultVo<SessionInfoResponse?> {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
        if (session == null) {
            log.debug("Session not found: $sessionId")
            return ResultVo.success(null)
        }

        val modelName = if (session.modelId > 0) {
            modelMapper.selectById(session.modelId)?.modelName
        } else null

        val response = SessionInfoResponse(
            sessionId = session.sessionId,
            agentId = session.agentId,
            agentName = session.name,
            modelId = session.modelId,
            modelName = modelName,
            tenantId = session.tenantId,
        )
        return ResultVo.success(response)
    }
}
