package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serial
import java.io.Serializable
import java.math.BigDecimal

/**
 * Everything the landing page shows, for the tenant the request is standing in.
 *
 * Every field is non-null with a default, including the nested ones. admin's Jackson 3 serialisation
 * drops null keys, so a front end reading this payload cannot tell an absent key from a value that was
 * never there — it has to be able to rely on the key being present and carrying zero or an empty list.
 *
 * The whole response is one read of one tenant: nothing here is a platform-wide number except
 * [platform], which is labelled as such because the two tables behind it have no tenant column to
 * filter on.
 */
@Schema(description = "Tenant-scoped overview metrics for the landing page")
data class DashboardOverviewResponse(
    @Schema(description = "Consumption from today until the request")
    val today: DashboardWindowStats = DashboardWindowStats(),
    @Schema(description = "Consumption from yesterday 00:00 to the same clock point as today's window")
    val yesterdaySameSpan: DashboardWindowStats = DashboardWindowStats(),
    @Schema(description = "Daily calls and tokens over the last 14 days, always 14 points")
    val trend: List<DashboardTrendPoint> = emptyList(),
    @Schema(description = "Top 5 agents by tokens over the last 14 days, already ordered")
    val topAgents: List<DashboardRankItem> = emptyList(),
    @Schema(description = "Top 5 models by tokens over the last 14 days, already ordered")
    val topModels: List<DashboardRankItem> = emptyList(),
    @Schema(description = "How many assets this tenant holds")
    val assets: DashboardAssetCounts = DashboardAssetCounts(),
    @Schema(description = "Platform-wide registries, not this tenant's")
    val platform: DashboardPlatformCounts = DashboardPlatformCounts(),
    @Schema(description = "Skill drafts waiting for a reviewer in this tenant")
    val pendingSkillDrafts: Long = 0L,
    @Schema(description = "Users holding this tenant as their primary one")
    val totalUsers: Long = 0L,
    @Schema(description = "Of those, users who logged in during the last 7 days")
    val activeUsersLast7Days: Long = 0L,
    @Schema(description = "Fee across the 14-day trend window, in yuan and without invented precision")
    val recent14dFee: BigDecimal = BigDecimal.ZERO,
    @Schema(description = "The instant every window in this response was measured against, yyyy-MM-dd HH:mm:ss")
    val serverTime: String = "",
) : Serializable {

    companion object {
        @Serial
        private const val serialVersionUID = 1L

        /**
         * A count out of an aggregate row, the way the other stats DTOs read theirs.
         *
         * Missing key, SQL NULL and a non-numeric driver type all land on 0: a window with no rows has
         * to read as zero consumption, not as a failed request.
         */
        private fun longOf(
            map: Map<String?, Any?>?,
            key: String,
        ): Long = (map?.get(key) as? Number)?.toLong() ?: 0L

        /** A name out of an aggregate row; the LEFT JOINs give NULL once the referenced row is gone. */
        private fun stringOf(
            map: Map<String?, Any?>?,
            key: String,
        ): String = map?.get(key) as? String ?: ""

        /** A money amount out of an aggregate row, kept exact — the fee column is a DECIMAL. */
        private fun feeOf(map: Map<String?, Any?>?): BigDecimal {
            val fee = map?.get("totalFee")
            return when (fee) {
                null -> BigDecimal.ZERO
                is BigDecimal -> fee
                is Number -> BigDecimal.valueOf(fee.toDouble())
                else -> BigDecimal(fee.toString())
            }
        }

        /**
         * Convert the single row of [getDashboardWindowStats] into one window's numbers.
         *
         * @param map The aggregate row, or null when the read answered nothing
         */
        fun mapToWindowStats(map: Map<String?, Any?>?): DashboardWindowStats = DashboardWindowStats(
            calls = longOf(map, "calls"),
            tokens = longOf(map, "tokens"),
            sessions = longOf(map, "sessions"),
            agents = longOf(map, "agents"),
        )

        /**
         * Convert one trend row of [getDashboardDailyTrend] into a day point.
         *
         * @param date Day the point stands for, `yyyy-MM-dd`, already normalised out of the row's bucket
         * @param map The aggregate row for that day
         */
        fun mapToTrendPoint(
            date: String,
            map: Map<String?, Any?>?,
        ): DashboardTrendPoint = DashboardTrendPoint(
            date = date,
            calls = longOf(map, "calls"),
            tokens = longOf(map, "tokens"),
        )

        /** A day point carrying nothing, for a day the query returned no row for. */
        fun emptyTrendPoint(date: String): DashboardTrendPoint = DashboardTrendPoint(date = date)

        /**
         * Convert one ranking row into a rank item.
         *
         * The id is deliberately not carried: these two lists are display-only, no action on the page
         * goes back to the agent or model row behind an entry.
         *
         * @param map The aggregate row from `aggregateByAgent` or `aggregateByModel`
         * @param nameKey Which column holds the display name for this dimension
         * @param qualifierKey Which column holds the second label, or null when the dimension has none.
         * Grouped by row id, an aggregate can hand back two entries whose name is identical — the model
         * dimension does whenever one tenant registers the same model name under two providers — and the
         * provider name is what tells those two rows apart on the page.
         */
        fun mapToRankItem(
            map: Map<String?, Any?>?,
            nameKey: String,
            qualifierKey: String? = null,
        ): DashboardRankItem = DashboardRankItem(
            name = stringOf(map, nameKey),
            qualifier = qualifierKey?.let { stringOf(map, it) } ?: "",
            tokens = longOf(map, "grandTotalToken"),
        )

        /** Convert the single row of [getTenantAssetCounts] into the asset strip. */
        fun mapToAssetCounts(map: Map<String?, Any?>?): DashboardAssetCounts = DashboardAssetCounts(
            agents = longOf(map, "agents"),
            skills = longOf(map, "skills"),
            models = longOf(map, "models"),
            mcpServers = longOf(map, "mcpServers"),
            channels = longOf(map, "channels"),
            teams = longOf(map, "teams"),
            sessions = longOf(map, "sessions"),
            users = longOf(map, "users"),
        )

        /** Convert the single row of [getPlatformCounts] into the platform line. */
        fun mapToPlatformCounts(map: Map<String?, Any?>?): DashboardPlatformCounts = DashboardPlatformCounts(
            tools = longOf(map, "tools"),
            cliPackages = longOf(map, "cliPackages"),
        )

        /** Pending skill drafts, read off the same row as the asset counts. */
        fun pendingSkillDraftsOf(map: Map<String?, Any?>?): Long = longOf(map, "pendingSkillDrafts")

        /** Active users inside the 7-day window, read off the same row as the asset counts. */
        fun activeUsersOf(map: Map<String?, Any?>?): Long = longOf(map, "activeUsers")

        /** Every user of this tenant, the denominator the active-user count is shown against. */
        fun totalUsersOf(map: Map<String?, Any?>?): Long = longOf(map, "users")

        /** `totalFee` out of an overall-stats row, reused so the 14-day fee needs no new statement. */
        fun recent14dFeeOf(map: Map<String?, Any?>?): BigDecimal = feeOf(map)
    }
}

/**
 * One window of consumption: how much ran, how many conversations and on how many agents.
 */
@Schema(description = "Consumption inside one time window")
data class DashboardWindowStats(
    @Schema(description = "Model calls, one token_stats row each")
    val calls: Long = 0L,
    @Schema(description = "Total tokens")
    val tokens: Long = 0L,
    @Schema(description = "Distinct sessions that ran")
    val sessions: Long = 0L,
    @Schema(description = "Distinct agents that ran")
    val agents: Long = 0L,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * One day of the trend series.
 *
 * A day with no consumption is present at zero rather than absent: the page plots 14 points and a hole
 * would read as a gap in the line rather than as a quiet day.
 */
@Schema(description = "One day of the trend series")
data class DashboardTrendPoint(
    @Schema(description = "Day of this point, yyyy-MM-dd")
    val date: String = "",
    @Schema(description = "Model calls that day")
    val calls: Long = 0L,
    @Schema(description = "Tokens consumed that day")
    val tokens: Long = 0L,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * One entry of a top-5 list.
 *
 * No id: these lists are display-only. [name] stays empty when the consuming row's agent or model has
 * been deleted — the aggregate keeps such a row through a LEFT JOIN, so its tokens still count and the
 * page renders it as deleted rather than dropping the consumption.
 *
 * [qualifier] carries the model's provider name and is empty on the agent list. The aggregates group by
 * row id, so one list can hold two entries whose [name] is identical, and the page needs the second
 * label to tell them apart rather than render the same line twice.
 */
@Schema(description = "One entry of a top-5 consumption ranking")
data class DashboardRankItem(
    @Schema(description = "Agent or model name, empty when that row is gone")
    val name: String = "",
    @Schema(description = "Second label, the provider name on the model list; empty when there is none")
    val qualifier: String = "",
    @Schema(description = "Tokens attributed to this entry")
    val tokens: Long = 0L,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * What the tenant owns.
 *
 * Counted as `tenant_id = current tenant AND active = 1` with no `status` term: a disabled agent is
 * still one of the tenant's assets, `status` is a switch rather than a statement about existence.
 */
@Schema(description = "Asset counts held by the requesting tenant")
data class DashboardAssetCounts(
    @Schema(description = "Agents")
    val agents: Long = 0L,
    @Schema(description = "Skills")
    val skills: Long = 0L,
    @Schema(description = "Models")
    val models: Long = 0L,
    @Schema(description = "MCP servers")
    val mcpServers: Long = 0L,
    @Schema(description = "Channels")
    val channels: Long = 0L,
    @Schema(description = "Teams")
    val teams: Long = 0L,
    @Schema(description = "Sessions")
    val sessions: Long = 0L,
    @Schema(description = "Users whose primary tenant this is")
    val users: Long = 0L,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * Platform-wide registries.
 *
 * Separate from [DashboardAssetCounts] on purpose: neither table carries a `tenant_id` column, so these
 * two numbers are the same for every tenant and would mislead sitting in the tenant's own strip.
 */
@Schema(description = "Platform-wide registry counts, not scoped to a tenant")
data class DashboardPlatformCounts(
    @Schema(description = "Registered tools, platform-wide since agent_tool has no tenant column")
    val tools: Long = 0L,
    @Schema(description = "CLI packages, platform-wide for the same reason")
    val cliPackages: Long = 0L,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}
