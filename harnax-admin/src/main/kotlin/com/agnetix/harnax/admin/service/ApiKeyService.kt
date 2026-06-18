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
}
