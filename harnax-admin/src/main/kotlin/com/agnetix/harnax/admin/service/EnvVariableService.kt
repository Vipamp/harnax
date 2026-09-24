package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableResponse
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.EnvVariable

interface EnvVariableService {

    fun page(keyword: String?, pageNum: Int, pageSize: Int): Page<EnvVariable>

    /**
     * The row behind [id] as the console sees it: this tenant's, and typed by the caller.
     *
     * The list and the agent-config dropdown already answer with only the caller's own rows, so this is
     * the one place a co-tenant user could still reach another user's variable — and a non-sensitive
     * value goes out verbatim through `GET /env-variables/{id}`. A row outside that scope answers as a
     * missing one, which is also what the update, delete and toggle paths do.
     */
    fun getEnvVariable(id: Long): EnvVariable?

    /**
     * The row behind [id] as far as this tenant holds it, whoever typed it.
     *
     * What an agent binding resolves through: a binding stores the id alone, and a shared agent points
     * at variables its owner created while whoever edits it now is somebody else. The runtime delivery
     * ([getDecryptedValue]) resolves the same way, so keeping this tenant-scoped is what stops the
     * console's narrower view from turning a variable that works into one that cannot be saved.
     */
    fun getRowWithinTenant(id: Long): EnvVariable?

    fun createEnvVariable(request: EnvVariableCreateRequest): Boolean

    fun updateEnvVariable(id: Long, request: EnvVariableUpdateRequest): Boolean

    fun deleteEnvVariable(id: Long): Boolean

    fun toggleEnabled(id: Long, enabled: Int): Boolean

    fun convertToResponse(envVariable: EnvVariable): EnvVariableResponse

    /**
     * List all env variables for agent config dropdown use.
     * Returns real (decrypted) values plus masked display values for sensitive ones.
     */
    fun listForAgentConfig(): List<Map<String, Any?>>

    /**
     * Get decrypted real value for an env variable by ID.
     * Returns null if not found.
     *
     * [tenantId] is the tenant of whatever is being resolved, not of the request: this is what the
     * internal delivery call runs on, and an internal call carries no tenant header. A binding row
     * that still points at another tenant's variable — saved before the save-time check existed — then
     * resolves to nothing, the same way a deleted or disabled one does.
     */
    fun getDecryptedValue(
        id: Long,
        tenantId: Long,
    ): String?
}
