package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.service.CliPluginService
import com.agnetix.harnax.entity.CliPlugin
import com.agnetix.harnax.mapper.CliPluginMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CliPluginServiceImpl(
    private val cliPluginMapper: CliPluginMapper,
) : CliPluginService {

    private val log = LoggerFactory.getLogger(CliPluginServiceImpl::class.java)

    override fun list(type: String?): List<CliPlugin> = if (type.isNullOrBlank()) {
        cliPluginMapper.selectAll()
    } else {
        cliPluginMapper.selectByType(type)
    }

    override fun getById(id: Long): CliPlugin? = cliPluginMapper.selectById(id)

    override fun toggleStatus(id: Long, status: Int): Boolean {
        if (status !in listOf(0, 1)) {
            log.warn("[CliPlugin] Invalid status value: {}", status)
            return false
        }
        val plugin = cliPluginMapper.selectById(id)
        if (plugin == null) {
            log.warn("[CliPlugin] Plugin not found: id={}", id)
            return false
        }
        return cliPluginMapper.updateStatus(id, status) > 0
    }

    override fun getEnabledPlugins(): List<CliPlugin> = cliPluginMapper.selectAllEnabled()
}
