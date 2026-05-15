package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelResponse
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.Model

/**
 * Model service interface
 */
interface ModelService {

    /**
     * Query models with pagination
     *
     * @param name       Name
     * @param providerId Provider ID
     * @param modelType  Model type
     * @param status     Status
     * @param tags       Tags filter (supports multiple, e.g.: internet,reasoning,tool,mcp,vision)
     * @param minPrice   Minimum price
     * @param maxPrice   Maximum price
     * @param pageNum    Current page number
     * @param pageSize   Page size
     * @return Paginated result
     */
    fun page(
        name: String?,
        providerId: Long?,
        modelType: String?,
        status: Int?,
        tags: String?,
        minPrice: Double?,
        maxPrice: Double?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Model>

    /**
     * Get model details
     *
     * @param id ID
     * @return Model entity
     */
    fun getModel(id: Long): Model?

    /**
     * Create model
     *
     * @param request Create request
     * @return Model response
     */
    fun createModel(request: ModelCreateRequest): Boolean

    /**
     * Update model
     *
     * @param id      ID
     * @param request Update request
     * @return Model response
     */
    fun updateModel(id: Long, request: ModelUpdateRequest): Boolean

    /**
     * Update model status
     *
     * @param id     ID
     * @param status Status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun updateStatus(id: Long, status: Int): Boolean

    /**
     * Toggle model status
     *
     * @param id     ID
     * @param status Status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleModel(id: Long, status: Int): Boolean

    /**
     * Delete model (logical delete)
     *
     * @param id ID
     * @return Delete result
     */
    fun deleteModel(id: Long): Boolean

    /**
     * Convert model entity to model response
     *
     * @param model Model entity
     * @return Model response
     */
    fun convertToResponse(model: Model): ModelResponse
}
