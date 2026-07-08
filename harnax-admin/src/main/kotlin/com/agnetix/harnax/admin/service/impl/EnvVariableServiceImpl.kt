package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableResponse
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.EnvVariable
import com.agnetix.harnax.mapper.EnvVariableMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class EnvVariableServiceImpl(
    private val envVariableMapper: EnvVariableMapper,
    private val jwtUtil: JwtUtil,
) : EnvVariableService {

    private val log = LoggerFactory.getLogger(EnvVariableServiceImpl::class.java)

    override fun page(keyword: String?, pageNum: Int, pageSize: Int): Page<EnvVariable> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<EnvVariable>(pageNum, pageSize)
        return Page.fromPageInfo(envVariableMapper.selectEnvVariableList(keyword, currentUsername))
    }

    override fun getEnvVariable(id: Long): EnvVariable? = envVariableMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createEnvVariable(request: EnvVariableCreateRequest): Boolean = try {
        val envVariable = EnvVariable()
        envVariable.envKey = request.envKey!!
        envVariable.envValue = request.envValue!!
        envVariable.description = request.description
        envVariable.sensitive = request.sensitive ?: 0
        envVariable.tenantId = TenantContext.getTenantId() ?: 1
        envVariable.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
        envVariable.createTime = LocalDateTime.now()
        envVariable.updateTime = LocalDateTime.now()
        envVariableMapper.insert(envVariable)
        true
    } catch (e: Exception) {
        log.error("Failed to create env variable", e)
        throw RuntimeException("Failed to create env variable: ${e.message}")
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateEnvVariable(id: Long, request: EnvVariableUpdateRequest): Boolean = try {
        val envVariable = getEnvVariable(id)
            ?: throw RuntimeException("Env variable not found")

        request.envKey?.let { envVariable.envKey = it }
        request.envValue?.let { envVariable.envValue = it }
        request.description?.let { envVariable.description = it }
        request.sensitive?.let { envVariable.sensitive = it }

        envVariable.updateTime = LocalDateTime.now()
        envVariableMapper.updateById(envVariable)
        true
    } catch (e: Exception) {
        log.error("Failed to update env variable", e)
        throw RuntimeException("Failed to update env variable: ${e.message}")
    }

    override fun deleteEnvVariable(id: Long): Boolean = envVariableMapper.deleteById(id) > 0

    override fun toggleEnabled(id: Long, enabled: Int): Boolean = envVariableMapper.toggleEnabled(id, enabled) > 0

    override fun convertToResponse(envVariable: EnvVariable): EnvVariableResponse {
        val displayValue = if (envVariable.sensitive == 1) {
            maskValue(envVariable.envValue)
        } else {
            envVariable.envValue
        }
        return EnvVariableResponse(
            id = envVariable.id,
            envKey = envVariable.envKey,
            envValue = displayValue,
            description = envVariable.description,
            sensitive = envVariable.sensitive,
            enabled = envVariable.enabled,
            creator = envVariable.creator,
            createTime = envVariable.createTime,
            updateTime = envVariable.updateTime,
        )
    }

    private fun maskValue(value: String): String = if (value.length <= 7) "******" else "${value.take(3)}****${value.takeLast(4)}"
}
