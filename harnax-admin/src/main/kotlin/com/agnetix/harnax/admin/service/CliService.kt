package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.CliCreateRequest
import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.CliUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.Cli

/**
 * CLI service interface
 */
interface CliService {

    fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Cli>

    fun getCli(id: Long): Cli?

    fun createCli(request: CliCreateRequest): Boolean

    fun updateCli(id: Long, request: CliUpdateRequest): Boolean

    fun toggleCliStatus(id: Long, status: Int): Boolean

    fun deleteCli(id: Long): Boolean

    fun convertToResponse(cli: Cli): CliResponse
}
