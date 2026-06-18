package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ApiKeyCreateRequest
import com.agnetix.harnax.admin.dto.ApiKeyCreatedResponse
import com.agnetix.harnax.admin.dto.ApiKeyResponse
import com.agnetix.harnax.admin.dto.ApiKeyUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

@Service
class ApiKeyServiceImpl(
    private val apiKeyMapper: ApiKeyMapper,
) : ApiKeyService {

    private val log = LoggerFactory.getLogger(ApiKeyServiceImpl::class.java)
    private val secureRandom = SecureRandom()
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun page(keyword: String?, enabled: Int?, pageNum: Int, pageSize: Int): Page<ApiKeyEntity> {
        PageHelper.startPage<ApiKeyEntity>(pageNum, pageSize)
        return Page.fromPageInfo(apiKeyMapper.selectApiKeyList(keyword, enabled))
    }

    override fun getApiKey(id: Long): ApiKeyEntity? = apiKeyMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createApiKey(request: ApiKeyCreateRequest): ApiKeyCreatedResponse {
        apiKeyMapper.selectByName(request.name!!)?.let {
            throw RuntimeException("API Key name already exists: ${request.name}")
        }

        val rawKey = generateRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)

        val entity = ApiKeyEntity().apply {
            name = request.name
            this.keyHash = keyHash
            this.keyPrefix = keyPrefix
            scopes = request.scopes ?: "api:chat"
            tenantId = request.tenantId ?: TenantContext.getTenantId()
            rateLimit = request.rateLimit ?: 60
            enabled = 1
            expiresAt = request.expiresAt?.let { LocalDateTime.parse(it, isoFormatter) }
            creator = "system"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        apiKeyMapper.insert(entity)
        log.info("Created API Key: ${entity.name} (id=${entity.id})")

        return ApiKeyCreatedResponse(
            id = entity.id,
            name = entity.name,
            rawKey = rawKey,
            keyPrefix = keyPrefix,
        )
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateApiKey(id: Long, request: ApiKeyUpdateRequest): Boolean {
        val entity = apiKeyMapper.selectById(id)
            ?: throw RuntimeException("API Key not found")

        request.scopes?.let { entity.scopes = it }
        request.tenantId?.let { entity.tenantId = it }
        request.rateLimit?.let { entity.rateLimit = it }
        request.enabled?.let { entity.enabled = it }
        request.expiresAt?.let {
            entity.expiresAt = if (it.isBlank()) null else LocalDateTime.parse(it, isoFormatter)
        }

        entity.updateTime = LocalDateTime.now()
        apiKeyMapper.updateById(entity)
        return true
    }

    override fun deleteApiKey(id: Long): Boolean {
        apiKeyMapper.selectById(id) ?: throw RuntimeException("API Key not found")
        return apiKeyMapper.deleteById(id) > 0
    }

    override fun toggleEnabled(id: Long, enabled: Int): Boolean {
        apiKeyMapper.selectById(id) ?: throw RuntimeException("API Key not found")
        return apiKeyMapper.updateEnabled(id, enabled) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun regenerateApiKey(id: Long): ApiKeyCreatedResponse {
        val entity = apiKeyMapper.selectById(id)
            ?: throw RuntimeException("API Key not found")

        val rawKey = generateRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)

        entity.keyHash = keyHash
        entity.keyPrefix = keyPrefix
        entity.updateTime = LocalDateTime.now()
        apiKeyMapper.updateById(entity)

        log.info("Regenerated API Key: ${entity.name} (id=${entity.id})")

        return ApiKeyCreatedResponse(
            id = entity.id,
            name = entity.name,
            rawKey = rawKey,
            keyPrefix = keyPrefix,
        )
    }

    override fun convertToResponse(entity: ApiKeyEntity): ApiKeyResponse =
        ApiKeyResponse.fromEntity(entity)

    private fun generateRawKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "hnx_sk_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
