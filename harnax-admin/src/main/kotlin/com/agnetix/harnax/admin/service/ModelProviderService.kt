package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.ModelProviderCreateRequest
import com.agnetix.harnax.admin.dto.ModelProviderResponse
import com.agnetix.harnax.admin.dto.ModelProviderUpdateRequest
import com.agnetix.harnax.admin.dto.ModelStatsInfo
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.ModelProvider

/**
 * Model provider service interface
 */
interface ModelProviderService {

    /**
     * Query model providers with pagination
     *
     * @param name     Provider name
     * @param type     Provider type
     * @param status   Status
     * @param isPublic Is public
     * @param pageNum  Current page number
     * @param pageSize Page size
     * @return Paginated result
     */
    fun page(name: String?, type: String?, status: Int?, isPublic: Int?, pageNum: Int, pageSize: Int): Page<ModelProvider>

    /**
     * Get model provider details
     *
     * @param id ID
     * @return Model provider response
     */
    fun getModelProvider(id: Long): ModelProvider?

    /**
     * Create model provider
     *
     * @param request Create request
     * @return Model provider response
     */
    fun createModelProvider(request: ModelProviderCreateRequest): Boolean

    /**
     * Update model provider
     *
     * @param id      ID
     * @param request Update request
     * @return Update result
     */
    fun updateModelProvider(id: Long, request: ModelProviderUpdateRequest): Boolean

    /**
     * Update model provider status
     *
     * @param id     ID
     * @param status Status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun updateStatus(id: Long, status: Int): Boolean

    /**
     * Toggle model provider status
     *
     * @param id     ID
     * @param status Status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleModelProvider(id: Long, status: Int): Boolean

    /**
     * Connection test
     *
     * @param id ID
     * @return Whether connection is successful
     */
    fun connectivityTest(id: Long): Boolean

    /**
     * Delete model provider (with validation)
     *
     * @param id ID
     * @return Whether delete is successful
     */
    fun deleteModelProvider(id: Long): Boolean

    /**
     * Convert model provider to response object
     *
     * @param modelProvider Model provider
     * @return Model provider response
     */
    fun convertToResponse(modelProvider: ModelProvider): ModelProviderResponse

    /**
     * Get model statistics for provider
     *
     * @param providerId Provider ID
     * @return Model statistics info
     */
    fun getModelStats(providerId: Long): ModelStatsInfo
}
