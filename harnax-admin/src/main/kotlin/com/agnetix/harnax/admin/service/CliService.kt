package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.Cli

/**
 * CLI read side plus the one switch an operator is allowed to touch.
 *
 * There is no create, update or delete: a `cli` row is registered from a plugin package by
 * `CliPackageAutoRegistrar` and goes away when that package leaves the directory (design D2). What the
 * page can do is take a CLI out of circulation, which is why [toggleCliStatus] is the only writer.
 */
interface CliService {

    fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Cli>

    fun getCli(id: Long): Cli?

    fun toggleCliStatus(id: Long, status: Int): Boolean

    /**
     * The shape a page row is answered with: what the table renders and nothing more.
     */
    fun convertToResponse(cli: Cli): CliResponse

    /**
     * [convertToResponse] plus the `plugin.yaml` columns that only a detail view has room for.
     */
    fun convertToDetailResponse(cli: Cli): CliResponse
}
