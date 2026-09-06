package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.entity.SkillRepository

interface SkillSourceService {

    fun page(name: String?, sourceType: String?, status: Int?, pageNum: Int, pageSize: Int): Page<SkillSourceResponse>

    fun getSkillSource(id: Long): SkillRepository?

    /**
     * Sources that are currently usable: the tenant's own plus the shared builtin one.
     *
     * Backs `GET /api/admin/skill-sources/active`, the parity endpoint of the legacy
     * `GET /api/admin/skill-repositories/active`.
     */
    fun listActive(): List<SkillRepository>

    /**
     * Creates the source and installs its skills in one call.
     * The response carries the per-skill outcome so a partial failure is never silent.
     */
    fun createSkillSource(request: SkillSourceCreateRequest): SkillSourceInstallResponse

    fun updateSkillSource(id: Long, request: SkillSourceUpdateRequest): Boolean

    fun deleteSkillSource(id: Long): Boolean

    fun toggleStatus(id: Long, status: Int): Boolean

    fun fetchSkills(id: Long): List<SyncSkillResponse>

    fun uploadAndInstall(zipPath: String, originalFilename: String, name: String): SkillSourceInstallResponse

    /** Re-runs the install for an existing source, picking up whatever the remote now serves. */
    fun installSkills(id: Long): SkillInstallResponse

    fun convertToResponse(entity: SkillRepository): SkillSourceResponse
}
