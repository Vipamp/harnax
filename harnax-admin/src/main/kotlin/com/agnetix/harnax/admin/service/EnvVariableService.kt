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
}
