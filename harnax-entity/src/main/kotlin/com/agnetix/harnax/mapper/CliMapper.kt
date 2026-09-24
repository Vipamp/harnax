package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Cli
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * CLI plugin package mapper.
 *
 * Rows are created and deleted only by `CliPackageAutoRegistrar`, which reads the package directory
 * at startup; no page or API writes this table. `uk_cli_name` is what makes the upsert converge on
 * one row per package name across versions.
 *
 * Tenant isolation is absent by design (design D10): a published package is a platform asset, so
 * there is no per-tenant or per-author visibility left to express.
 */
@Mapper
interface CliMapper {

    fun selectById(@Param("id") id: Long): Cli?

    fun selectByIds(@Param("ids") ids: List<Long>): List<Cli>

    fun selectByName(@Param("name") name: String): Cli?

    /**
     * Packages shipping one of [skillIds] through `cli.skill_id`.
     *
     * The third holder a skill can have, and the one the binding tables do not show: a shipped skill
     * belongs to its package, so a guard that reads only bindings would let it be disabled or deleted
     * out from under a live CLI.
     */
    fun selectBySkillIds(@Param("skillIds") skillIds: List<Long>): List<Cli>

    fun selectCliList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
    ): List<Cli>

    /** Every row including pruned-and-soft-deleted ones — the registrar diffs this against the directory */
    fun selectAll(): List<Cli>

    /** Insert or update by `name`. Never touches `status`, which is the operator's kill switch. */
    fun upsertCliPackage(cli: Cli): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    /** Hard delete for the prune path only. */
    fun deleteByIds(@Param("ids") ids: List<Long>): Int
}
