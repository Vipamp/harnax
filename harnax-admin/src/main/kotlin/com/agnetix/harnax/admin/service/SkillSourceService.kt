package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.entity.SkillRepository

interface SkillSourceService {

    fun page(name: String?, sourceType: String?, status: Int?, pageNum: Int, pageSize: Int): Page<SkillSourceResponse>

    fun getSkillSource(id: Long): SkillRepository?

    fun createSkillSource(request: SkillSourceCreateRequest): SkillRepository

    fun updateSkillSource(id: Long, request: SkillSourceUpdateRequest): Boolean

    fun deleteSkillSource(id: Long): Boolean

    fun fetchSkills(id: Long): List<SyncSkillResponse>

    fun uploadAndInstall(zipPath: String, originalFilename: String, name: String): SkillRepository

    fun convertToResponse(entity: SkillRepository): SkillSourceResponse
}
