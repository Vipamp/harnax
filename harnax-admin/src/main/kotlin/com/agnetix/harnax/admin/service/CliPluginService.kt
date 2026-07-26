package com.agnetix.harnax.admin.service

import com.agnetix.harnax.entity.CliPlugin

interface CliPluginService {

    fun list(type: String?): List<CliPlugin>

    fun getById(id: Long): CliPlugin?

    fun toggleStatus(id: Long, status: Int): Boolean

    fun getEnabledPlugins(): List<CliPlugin>
}
