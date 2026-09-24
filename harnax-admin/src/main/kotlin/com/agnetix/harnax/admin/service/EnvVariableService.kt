package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableResponse
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.EnvVariable

interface EnvVariableService {

    fun page(keyword: String?, pageNum: Int, pageSize: Int): Page<EnvVariable>

    fun getEnvVariable(id: Long): EnvVariable?

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
