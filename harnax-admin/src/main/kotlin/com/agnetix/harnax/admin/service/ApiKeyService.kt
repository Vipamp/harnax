package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.ApiKeyCreateRequest
import com.agnetix.harnax.admin.dto.ApiKeyCreatedResponse
import com.agnetix.harnax.admin.dto.ApiKeyResponse
import com.agnetix.harnax.admin.dto.ApiKeyUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.ApiKeyEntity

interface ApiKeyService {

    fun page(keyword: String?, enabled: Int?, creator: String?, tenantId: Long?, pageNum: Int, pageSize: Int): Page<ApiKeyEntity>

    fun getApiKey(id: Long): ApiKeyEntity?

    fun createApiKey(request: ApiKeyCreateRequest): ApiKeyCreatedResponse

    fun updateApiKey(id: Long, request: ApiKeyUpdateRequest): Boolean

    fun deleteApiKey(id: Long): Boolean

    fun toggleEnabled(id: Long, enabled: Int): Boolean

    fun regenerateApiKey(id: Long): ApiKeyCreatedResponse

    fun convertToResponse(entity: ApiKeyEntity): ApiKeyResponse

    /** Create a permanent API key for a new user (called during user creation) */
    fun createPermanentKeyForUser(userId: Long, username: String, tenantId: Long?): ApiKeyCreatedResponse

    /** Get the decrypted rawKey of a user's permanent key (called during login) */
    fun getPermanentRawKey(userId: Long): String?

    /** Regenerate a user's permanent API key */
    fun regeneratePermanentKey(userId: Long): ApiKeyCreatedResponse

    /** Initialize SYSTEM keys for internal services (e.g. channel-service) */
    fun initSystemKeys()
}
