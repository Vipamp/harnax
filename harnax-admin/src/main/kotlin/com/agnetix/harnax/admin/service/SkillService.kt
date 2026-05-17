package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillResponse
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.entity.Skill

/**
 * Skill service interface
 */
interface SkillService {

    /**
     * Paginated query for skill list
     *
     * @param name         Skill name
     * @param repositoryId Repository ID
     * @param status       Status filter field
     * @param pageNum      Current page number
     * @param pageSize     Page size
     * @return Paginated result
     */
    fun page(name: String?, repositoryId: Long?, status: Int?, pageNum: Int, pageSize: Int): Page<Skill>

    /**
     * Get single skill details
     *
     * @param id Skill ID
     * @return Skill entity
     */
    fun getSkill(id: Long): Skill?

    /**
     * Create skill
     *
     * @param request Skill creation request object
     * @return Creation result
     */
    fun createSkill(request: SkillCreateRequest): Boolean

    /**
     * Update skill
     *
     * @param id      Skill ID
     * @param request Skill update request object
     * @return Update result
     */
    fun updateSkill(id: Long, request: SkillUpdateRequest): Boolean

    /**
     * Toggle skill enable status
     *
     * @param id     Skill ID
     * @param status Enable status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleSkillStatus(id: Long, status: Int): Boolean

    /**
     * Delete skill
     *
     * @param id Skill ID
     * @return Deletion result
     */
    fun deleteSkill(id: Long): Boolean

    /**
     * Query skill by name
     *
     * @param repositoryId Repository ID
     * @param name         Skill name
     * @return Skill entity
     */
    fun getByNameAndRepo(repositoryId: Long, name: String): Skill?

    /**
     * Batch save skills (for sync)
     *
     * @param repositoryId Repository ID
     * @param skills       Skill list
     * @return Number of saved skills
     */
    fun batchSaveSkills(repositoryId: Long, skills: List<String>): Int

    fun convertToResponse(skill: Skill): SkillResponse
}
