package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillRepositoryCreateRequest
import com.agnetix.harnax.admin.dto.SkillRepositoryResponse
import com.agnetix.harnax.admin.dto.SkillRepositoryUpdateRequest
import com.agnetix.harnax.admin.dto.SyncSkillResponse
import com.agnetix.harnax.admin.entity.SkillRepository

/**
 * Skill repository service interface
 */
interface SkillRepositoryService {

    /**
     * Query skill repository list with pagination
     *
     * @param name     Repository name
     * @param status   Status filter field
     * @param pageNum  Current page number
     * @param pageSize Page size
     * @return Paginated result
     */
    fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<SkillRepository>

    /**
     * Get all active repository list
     *
     * @return Repository list
     */
    fun getActiveRepositories(): List<SkillRepository>

    /**
     * Get single skill repository details
     *
     * @param id Skill repository ID
     * @return Skill repository entity
     */
    fun getSkillRepository(id: Long): SkillRepository?

    /**
     * Create skill repository
     *
     * @param request Skill repository create request object
     * @return Create result
     */
    fun createSkillRepository(request: SkillRepositoryCreateRequest): Boolean

    /**
     * Update skill repository
     *
     * @param id      Skill repository ID
     * @param request Skill repository update request object
     * @return Update result
     */
    fun updateSkillRepository(id: Long, request: SkillRepositoryUpdateRequest): Boolean

    /**
     * Toggle skill repository enable status
     *
     * @param id     Skill repository ID
     * @param status Enable status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleSkillRepository(id: Long, status: Int): Boolean

    /**
     * Delete skill repository
     *
     * @param id Skill repository ID
     * @return Delete result
     */
    fun deleteSkillRepository(id: Long): Boolean

    /**
     * Query repository by name
     *
     * @param name Repository name
     * @return Skill repository entity
     */
    fun getByName(name: String): SkillRepository?

    /**
     * Get remote skill list (sync from Git repository)
     *
     * @param repositoryId Skill repository ID
     * @return Remote skill list
     */
    fun fetchRemoteSkills(repositoryId: Long): List<SyncSkillResponse>

    /**
     * Convert skill repository entity to skill repository response object
     *
     * @param skillRepository Skill repository entity
     * @return Skill repository response object
     */
    fun convertToResponse(skillRepository: SkillRepository): SkillRepositoryResponse
}
