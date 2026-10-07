package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overview shows one workspace's numbers and no other's (design 6.2, invariants I1 and I2).
 *
 * Two directions are asserted, and the second one is what makes the first worth having:
 *
 * 1. The neighbour tenant's own rows are visible to the neighbour. Without this, a seed that silently
 *    landed nowhere would leave the "nothing moved" assertion below passing on an empty database.
 * 2. Tenant 1's response is byte-identical before and after the neighbour's rows appear, field for field
 *    including the nested blocks and both rankings.
 *
 * A third case covers I2 on its own terms: rows whose `tenant_id` is null, which equality excludes from
 * both workspaces.
 *
 * The neighbour id is synthetic and belongs to this class alone — `TokenStatsAggregationIT` holds 930_930
 * and `ModelTenantIsolationIT` holds 940_002, and the tenants in the seed data (4 and 5) carry rows this
 * class does not control, which would turn a difference assertion into a guess. The row ids this class
 * writes are its own band too, 961_001 upwards, so no primary key of another fixture is ever overwritten.
 * Every tenant-scoped table named by the asset query gets one row, so dropping the shared `tenantActive`
 * fragment from any single cell of that projection shows up as a changed number rather than a coincidence.
 */
class DashboardTenantIsolationIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @AfterEach
    fun clearNeighbourRows() {
        jdbc.update("DELETE FROM token_stats WHERE tenant_id = ? OR session_id = ?", NEIGHBOUR_TENANT_ID, UNOWNED_SESSION_ID)
        jdbc.update("DELETE FROM session WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM agent WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM skill WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM skill_draft WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM `model` WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM mcp_server WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM channel WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM team WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM sys_user WHERE tenant_id = ? OR id = ?", NEIGHBOUR_TENANT_ID, UNOWNED_USER_ID)
    }

    /** One of everything the overview counts, written into the neighbour's name and nowhere else. */
    private fun seedNeighbourRows() {
        val now = LocalDateTime.now().format(TIMESTAMP)

        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, ?, 'Neighbour Agent', 'neighbour', 1)",
            NEIGHBOUR_AGENT_ID,
            NEIGHBOUR_TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, ?, 'Neighbour Second Agent', 'neighbour', 1)",
            NEIGHBOUR_SECOND_AGENT_ID,
            NEIGHBOUR_TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO session (id, tenant_id, title, session_id, agent_id, creator, active) VALUES (?, ?, 'Neighbour Session', ?, ?, 'neighbour', 1)",
            NEIGHBOUR_SESSION_ROW_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_SESSION_ID,
            NEIGHBOUR_AGENT_ID,
        )
        jdbc.update(
            "INSERT INTO skill (id, tenant_id, name, repository_id, creator, active) VALUES (?, ?, 'Neighbour Skill', ?, 'neighbour', 1)",
            NEIGHBOUR_SKILL_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_REPOSITORY_ID,
        )
        jdbc.update(
            "INSERT INTO `model` (id, tenant_id, name, model_name, provider_id, model_type, active) VALUES (?, ?, 'Neighbour Model', 'neighbour-model', ?, 'chat', 1)",
            NEIGHBOUR_MODEL_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_PROVIDER_ID,
        )
        jdbc.update(
            "INSERT INTO mcp_server (id, tenant_id, name, type, active) VALUES (?, ?, 'Neighbour MCP', 'stdio', 1)",
            NEIGHBOUR_MCP_ID,
            NEIGHBOUR_TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO channel (id, tenant_id, name, type, agent_id, callback_key, session_id, active) " +
                "VALUES (?, ?, 'Neighbour Channel', 'http', ?, 'neighbour-callback-key', ?, 1)",
            NEIGHBOUR_CHANNEL_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_AGENT_ID,
            NEIGHBOUR_SESSION_ID,
        )
        jdbc.update(
            "INSERT INTO team (id, tenant_id, name, system_prompt, model_id, active) VALUES (?, ?, 'Neighbour Team', 'Rules', ?, 1)",
            NEIGHBOUR_TEAM_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_MODEL_ID,
        )
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active, last_login_time) " +
                "VALUES (?, ?, 'neighbour_user_it', 'x', 'Neighbour', 'neighbour@example.com', '000', 1, ?)",
            NEIGHBOUR_USER_ID,
            NEIGHBOUR_TENANT_ID,
            now,
        )
        jdbc.update(
            "INSERT INTO skill_draft (id, tenant_id, name, skillmd, source_session_id, status) " +
                "VALUES (?, ?, 'neighbour-draft', 'body', ?, 'PENDING')",
            NEIGHBOUR_DRAFT_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_SESSION_ID,
        )
        // Consumption dated today, since that is the window the four cards measure.
        insertNeighbourStats(NEIGHBOUR_SESSION_ID, NEIGHBOUR_AGENT_ID, 400L, now)
        insertNeighbourStats("neighbour-other-session", NEIGHBOUR_SECOND_AGENT_ID, 500L, now)
    }

    private fun insertNeighbourStats(
        sessionId: String,
        agentId: Long,
        tokens: Long,
        ts: String,
    ) {
        jdbc.update(
            "INSERT INTO token_stats (tenant_id, agent_id, session_id, chat_model_id, input_token, output_token, total_token, fee, ts) " +
                "VALUES (?, ?, ?, NULL, ?, 0, ?, 0, ?)",
            NEIGHBOUR_TENANT_ID,
            agentId,
            sessionId,
            tokens,
            tokens,
            ts,
        )
    }

    @Test
    @DisplayName("another workspace's rows move none of this one's numbers, in either direction")
    fun neighbourRowsStayOutOfTheOverview() {
        val before = assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))

        seedNeighbourRows()

        // Direction one: the neighbour sees its own rows. If this reads as nothing, the seed went nowhere
        // and direction two below would pass without proving anything about the predicates.
        val asNeighbour = assertOk(getJson(OVERVIEW, tenantId = NEIGHBOUR_TENANT_ID))
        assertEquals(2L, asNeighbour["today"]["calls"].asLong(), "two calls were written for the neighbour")
        assertEquals(900L, asNeighbour["today"]["tokens"].asLong(), "and 900 tokens")
        assertEquals(2L, asNeighbour["today"]["sessions"].asLong(), "over two conversations")
        assertEquals(2L, asNeighbour["today"]["agents"].asLong(), "on two agents")
        assertEquals(2L, asNeighbour["assets"]["agents"].asLong(), "the neighbour owns two agents")
        assertEquals(1L, asNeighbour["assets"]["skills"].asLong(), "one skill")
        assertEquals(1L, asNeighbour["assets"]["models"].asLong(), "one model")
        assertEquals(1L, asNeighbour["assets"]["mcpServers"].asLong(), "one MCP server")
        assertEquals(1L, asNeighbour["assets"]["channels"].asLong(), "one channel")
        assertEquals(1L, asNeighbour["assets"]["teams"].asLong(), "one team")
        assertEquals(1L, asNeighbour["assets"]["sessions"].asLong(), "one session row")
        assertEquals(1L, asNeighbour["assets"]["users"].asLong(), "one user")
        assertEquals(1L, asNeighbour["totalUsers"].asLong(), "the same user, as the todo line's denominator")
        assertEquals(1L, asNeighbour["activeUsersLast7Days"].asLong(), "who logged in inside the 7-day window")
        assertEquals(1L, asNeighbour["pendingSkillDrafts"].asLong(), "and one draft waiting for review")

        // Direction two: nothing about this tenant moved. Compared as whole payloads rather than field by
        // field, so a change in any cell — a ranking entry appearing, an order flipping — fails too. Only
        // the measured instant differs between two reads, so that one key is set aside and checked apart.
        val after = assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))
        assertTrue(after["serverTime"].asText().isNotEmpty(), "the clock still answers")
        assertEquals(withoutClock(before), withoutClock(after), "every number of tenant 1 is unmoved by another tenant's rows")

        // And the platform registries, which carry no tenant at all, answer the same for both callers.
        assertEquals(before["platform"].toString(), asNeighbour["platform"].toString(), "tools and CLI packages are platform-wide")
    }

    /**
     * A row nobody owns is nobody's number (invariant I2).
     *
     * `token_stats.tenant_id` and `sys_user.tenant_id` are both nullable and equality excludes null, so the
     * two rows below belong to no workspace. The case is here for the shape that would break it: a clause
     * added to "also show the unassigned rows" turns one of these into every page's consumption, and the
     * only way to see that is to measure two workspaces against their own baselines.
     */
    @Test
    @DisplayName("a row with no tenant is counted for no workspace")
    fun unownedRowsBelongToNoWorkspace() {
        val ownBefore = assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))
        val neighbourBefore = assertOk(getJson(OVERVIEW, tenantId = NEIGHBOUR_TENANT_ID))

        val now = LocalDateTime.now().format(TIMESTAMP)
        // Dated today, and carrying an agent id rather than null, so a leak would move both cells at once.
        jdbc.update(
            "INSERT INTO token_stats (tenant_id, agent_id, session_id, chat_model_id, input_token, output_token, total_token, fee, ts) " +
                "VALUES (NULL, ?, ?, NULL, 700, 0, 700, 0, ?)",
            NEIGHBOUR_AGENT_ID,
            UNOWNED_SESSION_ID,
            now,
        )
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active, last_login_time) " +
                "VALUES (?, NULL, 'unowned_dashboard_it_user', 'x', 'Unowned', 'unowned@example.com', '000', 1, ?)",
            UNOWNED_USER_ID,
            now,
        )

        // Positive control: both rows really are in the tables. Without this the untouched payloads below
        // would also be produced by a seed that landed nowhere.
        assertEquals(
            1,
            jdbc.queryForObject("SELECT COUNT(*) FROM token_stats WHERE session_id = ?", Int::class.java, UNOWNED_SESSION_ID),
            "the unowned consumption row exists",
        )
        assertEquals(
            1,
            jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id = ?", Int::class.java, UNOWNED_USER_ID),
            "and so does the unowned user row",
        )

        assertEquals(
            withoutClock(ownBefore),
            withoutClock(assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))),
            "neither the unowned call nor the unowned user reaches this workspace's page",
        )
        assertEquals(
            withoutClock(neighbourBefore),
            withoutClock(assertOk(getJson(OVERVIEW, tenantId = NEIGHBOUR_TENANT_ID))),
            "and neither of them reaches another workspace's page",
        )
    }

    /**
     * The payload with the read's own instant taken out, as a string two reads can be compared on.
     *
     * `serverTime` is the only field two calls are allowed to differ in — it is the instant the numbers
     * were measured at, and the second read happens seconds later. Everything else in the tree, nested
     * blocks and lists included, has to match character for character.
     */
    private fun withoutClock(node: JsonNode): String = (node as ObjectNode).deepCopy().apply { remove("serverTime") }.toString()

    private companion object {
        /** The workspace the overview is read for: the admin token's own, which is also the resolver's default. */
        const val OWN_TENANT_ID = 1L

        /** A tenant nothing else in the suite belongs to. */
        const val NEIGHBOUR_TENANT_ID = 950_500L

        const val OVERVIEW = "/api/admin/dashboard/overview"

        const val NEIGHBOUR_AGENT_ID = 961_001L
        const val NEIGHBOUR_SECOND_AGENT_ID = 961_002L
        const val NEIGHBOUR_SESSION_ROW_ID = 961_003L
        const val NEIGHBOUR_SKILL_ID = 961_004L
        const val NEIGHBOUR_MODEL_ID = 961_005L
        const val NEIGHBOUR_PROVIDER_ID = 961_006L
        const val NEIGHBOUR_MCP_ID = 961_007L
        const val NEIGHBOUR_CHANNEL_ID = 961_008L
        const val NEIGHBOUR_TEAM_ID = 961_009L
        const val NEIGHBOUR_USER_ID = 961_010L
        const val NEIGHBOUR_DRAFT_ID = 961_011L
        const val NEIGHBOUR_REPOSITORY_ID = 961_012L

        const val NEIGHBOUR_SESSION_ID = "dashboard-isolation-neighbour-session"

        /** The unowned row's own handles: nothing but this class can delete a null-tenant row by tenant. */
        const val UNOWNED_USER_ID = 961_013L

        const val UNOWNED_SESSION_ID = "dashboard-isolation-unowned-session"

        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
