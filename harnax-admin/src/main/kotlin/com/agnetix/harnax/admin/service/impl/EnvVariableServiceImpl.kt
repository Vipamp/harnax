package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableResponse
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.EnvVariable
import com.agnetix.harnax.mapper.EnvVariableMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class EnvVariableServiceImpl(
    private val envVariableMapper: EnvVariableMapper,
    private val jwtUtil: JwtUtil,
    private val aesUtil: AesUtil,
) : EnvVariableService {

    private val log = LoggerFactory.getLogger(EnvVariableServiceImpl::class.java)

    override fun page(keyword: String?, pageNum: Int, pageSize: Int): Page<EnvVariable> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val boundedPageSize = pageSize.coerceIn(1, 100)
        val boundedPageNum = pageNum.coerceAtLeast(1)
        PageHelper.startPage<EnvVariable>(boundedPageNum, boundedPageSize)
        return Page.fromPageInfo(envVariableMapper.selectEnvVariableList(keyword, currentUsername))
    }

    override fun getEnvVariable(id: Long): EnvVariable? = envVariableMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createEnvVariable(request: EnvVariableCreateRequest): Boolean = try {
        val envVariable = EnvVariable()
        envVariable.envKey = request.envKey!!
        envVariable.envValue = request.envValue!!
        envVariable.description = request.description
        envVariable.sensitive = request.sensitive ?: 0
        envVariable.enabled = request.enabled ?: 1
        envVariable.tenantId = TenantContext.getTenantId() ?: 1
        envVariable.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
        envVariable.createTime = LocalDateTime.now()
        envVariable.updateTime = LocalDateTime.now()

        // Encrypt value if sensitive
        if (envVariable.sensitive == 1) {
            envVariable.envValue = aesUtil.encrypt(envVariable.envValue)
        }

        envVariableMapper.insert(envVariable)
        true
    } catch (e: Exception) {
        log.error("Failed to create env variable", e)
        throw RuntimeException("Failed to create env variable")
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateEnvVariable(id: Long, request: EnvVariableUpdateRequest): Boolean = try {
        val envVariable = getEnvVariable(id)
            ?: throw RuntimeException("Env variable not found")

        // IDOR check
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val currentTenantId = TenantContext.getTenantId() ?: 1
        if (envVariable.creator != currentUsername || envVariable.tenantId != currentTenantId) {
            throw RuntimeException("No permission to modify this env variable")
        }

        request.envKey?.let { envVariable.envKey = it }
        // Determine the resulting sensitive flag before deciding encryption
        val targetSensitive = request.sensitive ?: envVariable.sensitive
        // Only update value if provided (empty means keep current for sensitive)
        if (request.envValue != null) {
            envVariable.envValue = if (targetSensitive == 1) {
                aesUtil.encrypt(request.envValue!!)
            } else {
                request.envValue!!
            }
        }
        request.description?.let { envVariable.description = it }
        request.sensitive?.let { envVariable.sensitive = it }

        envVariable.updateTime = LocalDateTime.now()
        envVariableMapper.updateById(envVariable)
        true
    } catch (e: Exception) {
        log.error("Failed to update env variable", e)
        throw RuntimeException("Failed to update env variable")
    }

    override fun deleteEnvVariable(id: Long): Boolean {
        val envVariable = getEnvVariable(id)
            ?: throw RuntimeException("Env variable not found")
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val currentTenantId = TenantContext.getTenantId() ?: 1
        if (envVariable.creator != currentUsername || envVariable.tenantId != currentTenantId) {
            throw RuntimeException("No permission to delete this env variable")
        }
        return envVariableMapper.deleteById(id) > 0
    }

    override fun toggleEnabled(id: Long, enabled: Int): Boolean {
        val envVariable = getEnvVariable(id)
            ?: throw RuntimeException("Env variable not found")
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val currentTenantId = TenantContext.getTenantId() ?: 1
        if (envVariable.creator != currentUsername || envVariable.tenantId != currentTenantId) {
            throw RuntimeException("No permission to modify this env variable")
        }
        return envVariableMapper.toggleEnabled(id, enabled) > 0
    }

    override fun convertToResponse(envVariable: EnvVariable): EnvVariableResponse {
        val displayValue = if (envVariable.sensitive == 1) {
            try {
                val decrypted = aesUtil.decrypt(envVariable.envValue)
                maskValue(decrypted)
            } catch (e: Exception) {
                "******"
            }
        } else {
            envVariable.envValue
        }
        return EnvVariableResponse(
            id = envVariable.id,
            envKey = envVariable.envKey,
            envValue = displayValue,
            description = envVariable.description,
            sensitive = envVariable.sensitive,
            enabled = envVariable.enabled,
            creator = envVariable.creator,
            createTime = envVariable.createTime,
            updateTime = envVariable.updateTime,
        )
    }

    override fun listForAgentConfig(): List<Map<String, Any?>> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val allVars = envVariableMapper.selectEnvVariableList(null, currentUsername)
        return allVars.filter { it.enabled == 1 }.map { env ->
            val isSensitive = env.sensitive == 1
            val displayValue = if (isSensitive && !env.envValue.isNullOrBlank()) {
                try {
                    val realValue = aesUtil.decrypt(env.envValue)
                    maskValue(realValue)
                } catch (e: Exception) {
                    log.warn("Failed to decrypt env variable {}: {}", env.envKey, e.message)
                    "******"
                }
            } else {
                env.envValue ?: ""
            }
            mapOf(
                "id" to env.id,
                "envKey" to env.envKey,
                "displayValue" to displayValue,
                "sensitive" to isSensitive,
            )
        }
    }

    private fun maskValue(value: String): String = when {
        value.length <= 4 -> "******"
        value.length <= 8 -> "${value.take(1)}****${value.takeLast(1)}"
        else -> "${value.take(3)}****${value.takeLast(2)}"
    }

    override fun getDecryptedValue(id: Long): String? {
        val env = envVariableMapper.selectById(id) ?: return null
        return if (env.sensitive == 1 && !env.envValue.isNullOrBlank()) {
            try {
                aesUtil.decrypt(env.envValue)
            } catch (e: Exception) {
                log.warn("Failed to decrypt env variable {}: {}", env.envKey, e.message)
                null
            }
        } else {
            env.envValue
        }
    }
}
