package com.agnetix.harnax.mapper

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Landing-page overview counts that do not come out of `token_stats`.
 *
 * Everything here is a stock-take rather than a consumption measure: one row of plain counts for the
 * tenant asking, plus one row for the platform-wide registries.
 *
 * [getTenantAssetCounts] is the only statement in this mapper that may name a tenant, and it does so
 * through the single `tenantActive` fragment in `DashboardMapper.xml` — every cell of its projection
 * includes that fragment, so an added cell that forgets it is visible in the diff as the one subquery
 * without a predicate. [getPlatformCounts] legitimately has none: `agent_tool` and `cli` carry no
 * `tenant_id` column at all, which is why the page shows those two cells apart from the tenant's own
 * assets instead of beside them.
 *
 * Rows whose `tenant_id` is NULL belong to no workspace and match no count here — `sys_user.tenant_id`
 * is nullable, and an equality against a real tenant id leaves such a row out the same way the
 * `TokenStatsMapper` aggregations leave an unattributed run out.
 */
@Mapper
interface DashboardMapper {

    /**
     * One row carrying every asset cell of the overview, counted for one tenant.
     *
     * Predicates are `tenant_id = ? AND active = 1` without a `status` term: a disabled agent is still
     * the tenant's asset, `status` is a switch and not a statement about existence. `skill_draft` is the
     * one cell outside that shape because the table has no `active` column — its soft delete is carried
     * by `status`, so this counts the rows still waiting on a reviewer.
     *
     * @param tenantId Owning tenant, required — there is no value here that means "every tenant"
     * @param activeUserSince Window start for the active-user cell, `yyyy-MM-dd HH:mm:ss`, compared
     *   against `sys_user.last_login_time`
     * @return One row of camelCase counts, or null when the statement somehow answers no row
     */
    fun getTenantAssetCounts(
        @Param("tenantId") tenantId: Long,
        @Param("activeUserSince") activeUserSince: String,
    ): MutableMap<String?, Any?>?

    /**
     * Platform-wide registry counts, deliberately tenant-free.
     *
     * No parameter takes a tenant because no such column exists on either table; a caller cannot ask for
     * "its" tools, and the response keeps these two numbers in their own block so the page can label
     * them as platform-level rather than imply they belong to the workspace.
     *
     * @return One row with `tools` and `cliPackages`, or null when the statement answers no row
     */
    fun getPlatformCounts(): MutableMap<String?, Any?>?
}
