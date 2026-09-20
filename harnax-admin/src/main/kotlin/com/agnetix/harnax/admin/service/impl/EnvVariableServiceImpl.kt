package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableResponse
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.EnvVariable
import com.agnetix.harnax.mapper.AgentMapper
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
    private val agentMapper: AgentMapper,
) : EnvVariableService {

    private val log = LoggerFactory.getLogger(EnvVariableServiceImpl::class.java)

    override fun page(keyword: String?, pageNum: Int, pageSize: Int): Page<EnvVariable> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val boundedPageSize = pageSize.coerceIn(1, 1000)
        val boundedPageNum = pageNum.coerceAtLeast(1)
        PageHelper.startPage<EnvVariable>(boundedPageNum, boundedPageSize)
        return Page.fromPageInfo(envVariableMapper.selectEnvVariableList(keyword, currentUsername))
    }

    /**
     * Single-row access to `env_variable`.
     *
     * `GET /env-variables/{id}` answers with this row and a non-sensitive value goes out verbatim, so
     * an unscoped by-id read makes the list filter cosmetic: any logged-in user could enumerate ids
     * and read another tenant's variables. A row outside the current tenant answers as a missing one,
     * which is also how [com.agnetix.harnax.admin.service.impl.McpServerServiceImpl] treats one.
     */
    override fun getEnvVariable(id: Long): EnvVariable? = envVariableMapper.selectById(id)?.takeIf { it.tenantId == currentTenantId() }

    /**
     * The tenant this request acts within.
     *
     * Copy of `McpServerServiceImpl.currentTenantId`, for the same reason: `TenantInterceptor` only
     * populates the ThreadLocal when an `X-Tenant-ID` header arrives with it, so a header-less
     * request would otherwise create rows inside tenant 1 — a workspace the caller may not belong to
     * — and default the update/delete/toggle permission checks there too.
     */
    private fun currentTenantId(): Long = TenantContext.getTenantId() ?: tenantFromToken() ?: tenantFromUserRecord() ?: DEFAULT_TENANT_ID

    private fun tenantFromToken(): Long? = UserContextUtil.getToken()?.let { token ->
        runCatching { jwtUtil.getTenantIdFromToken(token) }.getOrNull()?.takeIf { it > 0 }
    }

    private fun tenantFromUserRecord(): Long? = runCatching {
        SecurityUtils.getCurrentUser()?.tenantId?.takeIf { it > 0 }
    }.getOrNull()

    @Transactional(rollbackFor = [Exception::class])
    override fun createEnvVariable(request: EnvVariableCreateRequest): Boolean = try {
        val envVariable = EnvVariable()
        envVariable.envKey = request.envKey!!
        envVariable.envValue = request.envValue!!
        envVariable.description = request.description
        envVariable.sensitive = request.sensitive ?: 0
        envVariable.enabled = request.enabled ?: 1
        envVariable.tenantId = currentTenantId()
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
        val currentTenantId = currentTenantId()
        if (envVariable.creator != currentUsername || envVariable.tenantId != currentTenantId) {
            throw RuntimeException("No permission to modify this env variable")
        }

        request.envKey?.let { envVariable.envKey = it }
        // Determine the resulting sensitive flag before deciding encryption
        val targetSensitive = request.sensitive ?: envVariable.sensitive
        // Only update value if provided (empty means keep current for sensitive)
        if (request.envValue != null) {
            envVariable.envValue = if (targetSensitive == 1) {
                // The detail API masks a sensitive value, so a mask coming back means "unchanged";
                // encrypting it would store the mask in place of the credential it stands for.
                if (request.envValue!!.contains("****")) envVariable.envValue else aesUtil.encrypt(request.envValue!!)
            } else {
                request.envValue!!
            }
        }
        request.description?.let { envVariable.description = it }
        if (request.envValue == null && targetSensitive != envVariable.sensitive) {
            // `sensitive` describes how this column is encoded, so flipping it alone leaves the other
            // reading in place: 1 to 0 ships the AES ciphertext as the tool's env value, and 0 to 1
            // stores plaintext that later fails to decrypt, which delivery reports as "no value".
            envVariable.envValue = reEncode(envVariable.envValue, toSensitive = targetSensitive)
        }
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
        val currentTenantId = currentTenantId()
        if (envVariable.creator != currentUsername || envVariable.tenantId != currentTenantId) {
            throw RuntimeException("No permission to delete this env variable")
        }
        assertNotReferencedByAgents(id, envVariable.envKey)
        return envVariableMapper.deleteById(id) > 0
    }

    /**
     * Refuse to delete a variable an agent still binds to.
     *
     * A binding that references a variable stores the id and nothing else, and delivery resolves the
     * value through it every time, so deleting the variable empties every agent that points at it —
     * with no error, because the resolve simply yields nothing.
     */
    private fun assertNotReferencedByAgents(id: Long, envKey: String?) {
        val referring = agentMapper.selectByEnvVarRef(id)
        if (referring.isEmpty()) return
        val shown = referring.take(MAX_REFERRING_AGENTS).joinToString(", ") { it.name } +
            if (referring.size > MAX_REFERRING_AGENTS) " …" else ""
        throw BizException(
            "Env variable '$envKey' is bound by ${referring.size} agent(s): $shown. " +
                "Rebind them first, then delete.",
        )
    }

    override fun toggleEnabled(id: Long, enabled: Int): Boolean {
        val envVariable = getEnvVariable(id)
            ?: throw RuntimeException("Env variable not found")
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val currentTenantId = currentTenantId()
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
        // The one delivery resolves through, so this is where disabling a variable has to take effect:
        // the agent-config dropdown already hides such a row, which makes an ignored toggle a switch
        // that looks working while a rotated or compromised value stays live in every bound agent.
        if (env.enabled != 1) return null
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

    /**
     * Convert a stored value to the encoding [toSensitive] describes.
     *
     * Text this key cannot open is returned untouched: it is not ciphertext of ours, and overwriting
     * it would destroy the only copy of whatever the operator actually stored.
     */
    private fun reEncode(stored: String, toSensitive: Int): String = when {
        stored.isBlank() -> stored
        toSensitive == 1 -> aesUtil.encrypt(stored)
        else -> runCatching { aesUtil.decrypt(stored) }.getOrDefault(stored)
    }

    private companion object {
        /** Enough to point at the offenders; the count in the message is the full one. */
        const val MAX_REFERRING_AGENTS = 5

        /** Last resort only — see [currentTenantId]. */
        const val DEFAULT_TENANT_ID = 1L
    }
}
