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
        return Page.fromPageInfo(envVariableMapper.selectEnvVariableList(keyword, currentUsername, currentTenantId()))
    }

    /**
     * Single-row access to `env_variable`, as the console sees it.
     *
     * `GET /env-variables/{id}` answers with this row and a non-sensitive value goes out verbatim, so an
     * unscoped by-id read makes the list filter cosmetic: any logged-in user could enumerate ids and
     * read someone else's variables. The unit of isolation is the creator, not just the tenant, because
     * that is what the list ([page]) and the config dropdown ([listForAgentConfig]) already answer with
     * - the console never shows a row the caller did not type, so a read that did would be the one door
     * left open. A row outside that scope answers as a missing one, which is also how
     * [com.agnetix.harnax.admin.service.impl.McpServerServiceImpl] treats one.
     */
    override fun getEnvVariable(id: Long): EnvVariable? = getRowWithinTenant(id)?.takeIf { it.creator == UserContextUtil.getCurrentUsername(jwtUtil) }

    /** What a stored binding resolves through: any live row of this tenant, whoever typed it. */
    override fun getRowWithinTenant(id: Long): EnvVariable? = envVariableMapper.selectById(id)?.takeIf { it.tenantId == currentTenantId() }

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

        // Checked before the insert so a clash reads as the key that clashed, not as the SQL error
        // `uk_env_tenant_creator_active_key` would raise - and asked of the caller's own rows only,
        // which is what that key covers: two users of one tenant may hold the same key.
        if (envVariableMapper.selectByKey(envVariable.envKey, envVariable.creator, envVariable.tenantId) != null) {
            throw BizException("Env variable key '${envVariable.envKey}' already exists")
        }

        // Encrypt value if sensitive
        if (envVariable.sensitive == 1) {
            envVariable.envValue = aesUtil.encrypt(envVariable.envValue)
        }

        envVariableMapper.insert(envVariable)
        true
    } catch (e: BizException) {
        throw e
    } catch (e: Exception) {
        log.error("Failed to create env variable", e)
        throw RuntimeException("Failed to create env variable")
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateEnvVariable(id: Long, request: EnvVariableUpdateRequest): Boolean = try {
        // `getEnvVariable` holds this tenant's rows typed by this caller, so both the cross-tenant and
        // the cross-user row - the two things the explicit IDOR check used to compare by hand - answer
        // here as a row that is simply not there.
        val envVariable = getEnvVariable(id)
            ?: throw BizException("Env variable not found")

        request.envKey?.let { envKey ->
            // Only a rename can collide: the row's own name is held by this very row.
            if (envKey != envVariable.envKey &&
                envVariableMapper.selectByKey(envKey, envVariable.creator, envVariable.tenantId) != null
            ) {
                throw BizException("Env variable key '$envKey' already exists")
            }
            envVariable.envKey = envKey
        }
        // Determine the resulting sensitive flag before deciding encryption
        val targetSensitive = request.sensitive ?: envVariable.sensitive
        // Only update value if provided (empty means keep current for sensitive)
        if (request.envValue != null) {
            envVariable.envValue = if (targetSensitive == 1) {
                // The detail API masks a sensitive value, so the mask coming back means "unchanged";
                // encrypting it would store the mask in place of the credential it stands for. Compared
                // against this row's own mask rather than by looking for asterisks in it — a real
                // credential that happens to contain "****" used to be forever uneditable, with the
                // call answering success while keeping the old value.
                if (isUnchangedMask(request.envValue!!, envVariable.envValue)) {
                    envVariable.envValue
                } else {
                    aesUtil.encrypt(request.envValue!!)
                }
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
    } catch (e: BizException) {
        throw e
    } catch (e: Exception) {
        log.error("Failed to update env variable", e)
        throw RuntimeException("Failed to update env variable")
    }

    override fun deleteEnvVariable(id: Long): Boolean {
        val envVariable = getEnvVariable(id)
            ?: throw RuntimeException("Env variable not found")
        assertNotReferencedByAgents(id, envVariable.envKey, "delete")
        return envVariableMapper.deleteById(id) > 0
    }

    /**
     * Refuse to take a variable an agent still binds to away from it, by deleting or by disabling.
     *
     * A binding that references a variable stores the id and nothing else, and delivery resolves the
     * value through it every time, so both actions empty every agent that points at it - with no
     * error, because the resolve simply yields nothing.
     */
    private fun assertNotReferencedByAgents(id: Long, envKey: String?, then: String) {
        val referring = agentMapper.selectByEnvVarRef(id)
        if (referring.isEmpty()) return
        val shown = referring.take(MAX_REFERRING_AGENTS).joinToString(", ") { it.name } +
            if (referring.size > MAX_REFERRING_AGENTS) " …" else ""
        throw BizException(
            "Env variable '$envKey' is bound by ${referring.size} agent(s): $shown. " +
                "Rebind them first, then $then.",
        )
    }

    override fun toggleEnabled(id: Long, enabled: Int): Boolean {
        val envVariable = getEnvVariable(id)
            ?: throw RuntimeException("Env variable not found")
        // Symmetric with delete: the binding snapshots keep their envVarId either way, so the agents
        // that read this variable lose its value the moment it is switched off.
        if (enabled == 0) {
            assertNotReferencedByAgents(id, envVariable.envKey, "disable")
        }
        return envVariableMapper.toggleEnabled(id, enabled) > 0
    }

    override fun convertToResponse(envVariable: EnvVariable): EnvVariableResponse {
        val displayValue = if (envVariable.sensitive == 1) {
            try {
                val decrypted = aesUtil.decrypt(envVariable.envValue)
                maskValue(decrypted)
            } catch (e: Exception) {
                FULL_MASK
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
        // The dropdown offers only what this caller typed, same as the list page. That is narrower than
        // what a saved agent may hold: a reference already on the row is resolved by tenant
        // ([getRowWithinTenant]), because a shared agent legitimately binds its owner's variables -
        // adding one is the act scoped to the person adding it.
        val allVars = envVariableMapper.selectEnvVariableList(null, currentUsername, currentTenantId())
        return allVars.filter { it.enabled == 1 }.map { env ->
            val isSensitive = env.sensitive == 1
            val displayValue = if (isSensitive && !env.envValue.isNullOrBlank()) {
                try {
                    val realValue = aesUtil.decrypt(env.envValue)
                    maskValue(realValue)
                } catch (e: Exception) {
                    log.warn("Failed to decrypt env variable {}: {}", env.envKey, e.message)
                    FULL_MASK
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
        value.length <= 4 -> FULL_MASK
        value.length <= 8 -> "${value.take(1)}****${value.takeLast(1)}"
        else -> "${value.take(3)}****${value.takeLast(2)}"
    }

    /**
     * Whether [incoming] is the display form of what is already stored, i.e. the edit form echoing
     * the mask back instead of typing a new value.
     *
     * Recomputed from the stored row rather than pattern-matched: the mask is a function of the value,
     * so this says "unchanged" for exactly the strings the page could have shown and nothing else —
     * including [FULL_MASK], which is what the page shows when the stored text will not open.
     */
    private fun isUnchangedMask(
        incoming: String,
        stored: String,
    ): Boolean = (runCatching { maskValue(aesUtil.decrypt(stored)) }.getOrNull() ?: FULL_MASK) == incoming

    override fun getDecryptedValue(
        id: Long,
        tenantId: Long,
    ): String? {
        val env = envVariableMapper.selectById(id) ?: return null
        // Same asymmetry the MCP branch of delivery already closes: this resolver is the only one with
        // no tenant in sight, so a stale cross-tenant reference would hand over another tenant's
        // secret. Answers as a missing row, which is how delivery already treats deleted and disabled.
        // Creator-blind on purpose, like [getRowWithinTenant]: delivery runs for whoever is chatting
        // with the agent, not for whoever typed the variable.
        if (env.tenantId != tenantId) return null
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
        /** What a value that must not be shown displays as: a short value and an unreadable one alike. */
        const val FULL_MASK = "******"

        /** Enough to point at the offenders; the count in the message is the full one. */
        const val MAX_REFERRING_AGENTS = 5

        /** Last resort only — see [currentTenantId]. */
        const val DEFAULT_TENANT_ID = 1L
    }
}
