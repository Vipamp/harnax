package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ApiKeyCreateRequest
import com.agnetix.harnax.admin.dto.ApiKeyCreatedResponse
import com.agnetix.harnax.admin.dto.ApiKeyResponse
import com.agnetix.harnax.admin.dto.ApiKeyUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
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
    private val jwtUtil: JwtUtil,
    private val aesUtil: AesUtil,
) : ApiKeyService {

    private val log = LoggerFactory.getLogger(ApiKeyServiceImpl::class.java)
    private val secureRandom = SecureRandom()
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private val protectedKeyTypes = setOf("PERMANENT", "SYSTEM")

    override fun page(
        keyword: String?,
        enabled: Int?,
        creator: String?,
        tenantId: Long?,
        pageNum: Int,
        pageSize: Int,
    ): Page<ApiKeyEntity> {
        PageHelper.startPage<ApiKeyEntity>(pageNum, pageSize)
        return Page.fromPageInfo(apiKeyMapper.selectTemporaryKeys(keyword, enabled, creator, tenantId))
    }

    override fun getApiKey(id: Long): ApiKeyEntity? {
        val entity = apiKeyMapper.selectById(id) ?: return null
        checkAccess(entity)
        return entity
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createApiKey(request: ApiKeyCreateRequest): ApiKeyCreatedResponse {
        apiKeyMapper.selectByName(request.name!!)?.let {
            throw RuntimeException("API Key name already exists: ${request.name}")
        }

        val username = currentUsername()
        val admin = isAdmin()

        // Non-admin users must use their own tenant; ignore request.tenantId
        val tenantId = if (admin) {
            request.tenantId ?: TenantContext.getTenantId()
        } else {
            TenantContext.getTenantId()
                ?: throw RuntimeException("Tenant context is required to create API Key")
        }

        val rawKey = generateRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)

        val entity = ApiKeyEntity().apply {
            name = request.name
            keyType = "TEMPORARY"
            userId = null
            serviceName = null
            this.keyHash = keyHash
            this.keyPrefix = keyPrefix
            scopes = request.scopes ?: "chat"
            this.tenantId = tenantId
            rateLimit = request.rateLimit ?: 60
            enabled = 1
            expiresAt = request.expiresAt?.let { LocalDateTime.parse(it, isoFormatter) }
            creator = username
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        apiKeyMapper.insert(entity)
        log.info("API Key created: name={}, id={}, creator={}, tenantId={}", entity.name, entity.id, username, tenantId)

        return ApiKeyCreatedResponse(
            id = entity.id,
            name = entity.name,
            rawKey = rawKey,
            keyPrefix = keyPrefix,
        )
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateApiKey(id: Long, request: ApiKeyUpdateRequest): Boolean {
        val entity = loadAndCheckAccess(id)
        if (entity.keyType in protectedKeyTypes) {
            throw RuntimeException("${entity.keyType} API Key cannot be modified, use regenerate instead")
        }
        val admin = isAdmin()

        // Non-admin users cannot change tenantId
        if (!admin && request.tenantId != null && request.tenantId != entity.tenantId) {
            throw RuntimeException("No permission to change tenant")
        }

        request.scopes?.let { entity.scopes = it }
        if (admin) request.tenantId?.let { entity.tenantId = it }
        request.rateLimit?.let { entity.rateLimit = it }
        request.enabled?.let { entity.enabled = it }
        request.expiresAt?.let {
            entity.expiresAt = if (it.isBlank()) null else LocalDateTime.parse(it, isoFormatter)
        }

        entity.updateTime = LocalDateTime.now()
        apiKeyMapper.updateById(entity)
        log.info("API Key updated: id={}, operator={}", id, currentUsername())
        return true
    }

    override fun deleteApiKey(id: Long): Boolean {
        val entity = loadAndCheckAccess(id)
        if (entity.keyType in protectedKeyTypes) {
            throw RuntimeException("${entity.keyType} API Key cannot be deleted, use regenerate instead")
        }
        val username = currentUsername()
        val result = apiKeyMapper.deleteById(id) > 0
        if (result) log.info("API Key deleted: id={}, operator={}", id, username)
        return result
    }

    override fun toggleEnabled(id: Long, enabled: Int): Boolean {
        val entity = loadAndCheckAccess(id)
        if (entity.keyType in protectedKeyTypes) {
            throw RuntimeException("${entity.keyType} API Key cannot be disabled")
        }
        val username = currentUsername()
        val result = apiKeyMapper.updateEnabled(id, enabled) > 0
        if (result) log.info("API Key toggled: id={}, enabled={}, operator={}", id, enabled, username)
        return result
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun regenerateApiKey(id: Long): ApiKeyCreatedResponse {
        val entity = loadAndCheckAccess(id)

        val rawKey = generateRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)

        entity.keyHash = keyHash
        entity.keyPrefix = keyPrefix
        entity.updateTime = LocalDateTime.now()
        apiKeyMapper.updateById(entity)

        log.info("API Key regenerated: name={}, id={}, operator={}", entity.name, entity.id, currentUsername())

        return ApiKeyCreatedResponse(
            id = entity.id,
            name = entity.name,
            rawKey = rawKey,
            keyPrefix = keyPrefix,
        )
    }

    override fun convertToResponse(entity: ApiKeyEntity): ApiKeyResponse = ApiKeyResponse.fromEntity(entity)

    // ==================== Permanent Key Methods ====================

    @Transactional(rollbackFor = [Exception::class])
    override fun createPermanentKeyForUser(userId: Long, username: String, tenantId: Long?): ApiKeyCreatedResponse {
        val rawKey = generateRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
        val encrypted = aesUtil.encrypt(rawKey)

        val entity = ApiKeyEntity().apply {
            name = "permanent_$username"
            keyType = "PERMANENT"
            this.userId = userId
            rawKeyEncrypted = encrypted
            serviceName = null
            this.keyHash = keyHash
            this.keyPrefix = keyPrefix
            scopes = "chat"
            this.tenantId = tenantId
            rateLimit = 300
            enabled = 1
            expiresAt = null
            creator = "system"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        apiKeyMapper.insert(entity)
        log.info("Permanent API Key created for user: userId={}, username={}", userId, username)

        return ApiKeyCreatedResponse(
            id = entity.id,
            name = entity.name,
            rawKey = rawKey,
            keyPrefix = keyPrefix,
        )
    }

    override fun getPermanentRawKey(userId: Long): String? {
        val entity = apiKeyMapper.selectPermanentKeyByUserId(userId) ?: return null
        if (entity.enabled != 1) {
            throw BizException("Your API Key has been disabled, please contact administrator")
        }
        val encrypted = entity.rawKeyEncrypted ?: return null
        return aesUtil.decrypt(encrypted)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun regeneratePermanentKey(userId: Long): ApiKeyCreatedResponse {
        val entity = apiKeyMapper.selectPermanentKeyByUserId(userId)
            ?: throw RuntimeException("Permanent API Key not found for user: $userId")

        val rawKey = generateRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
        val encrypted = aesUtil.encrypt(rawKey)

        entity.keyHash = keyHash
        entity.keyPrefix = keyPrefix
        entity.rawKeyEncrypted = encrypted
        entity.updateTime = LocalDateTime.now()
        apiKeyMapper.updateById(entity)

        log.info("Permanent API Key regenerated for userId={}", userId)

        return ApiKeyCreatedResponse(
            id = entity.id,
            name = entity.name,
            rawKey = rawKey,
            keyPrefix = keyPrefix,
        )
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun initSystemKeys() {
        val systemServices = listOf("channel-service", "scheduler")
        for (serviceName in systemServices) {
            val existing = apiKeyMapper.selectSystemKeyByServiceName(serviceName)
            if (existing == null) {
                val rawKey = generateRawKey()
                val keyHash = sha256(rawKey)
                val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
                val encrypted = aesUtil.encrypt(rawKey)

                val entity = ApiKeyEntity().apply {
                    name = "system_$serviceName"
                    keyType = "SYSTEM"
                    userId = null
                    rawKeyEncrypted = encrypted
                    this.serviceName = serviceName
                    this.keyHash = keyHash
                    this.keyPrefix = keyPrefix
                    scopes = "chat"
                    rateLimit = 600
                    enabled = 1
                    expiresAt = null
                    creator = "system"
                    active = 1
                    createTime = LocalDateTime.now()
                    updateTime = LocalDateTime.now()
                }
                apiKeyMapper.insert(entity)
                log.info("System API Key created for service: {}", serviceName)
            }
        }
    }

    private fun loadAndCheckAccess(id: Long): ApiKeyEntity {
        val entity = apiKeyMapper.selectById(id)
            ?: throw RuntimeException("API Key not found")
        checkAccess(entity)
        return entity
    }

    private fun checkAccess(entity: ApiKeyEntity) {
        if (isAdmin()) return
        val username = currentUsername()
        if (entity.creator != username) {
            throw RuntimeException("No permission to access this API Key")
        }
        val currentTenantId = TenantContext.getTenantId()
        if (currentTenantId != null && entity.tenantId != currentTenantId) {
            throw RuntimeException("No permission to access this API Key")
        }
    }

    private fun currentUsername(): String = UserContextUtil.getCurrentUsername(jwtUtil)

    private fun isAdmin(): Boolean = SecurityUtils.getCurrentUser()?.isAdmin == 1

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
