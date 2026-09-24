package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Checks the `UNIQUE(agent_id, cli_id)` key V41 puts on `agent_cli_binding`.
 *
 * Same shape as [AgentSkillBindingUniqueIT], and for the same reason: `saveCliBindings` collapses the
 * list now, so the service layer would pass with or without this key. Asserted through SQL because the
 * two guards are meant to fail independently — and a migration whose statement never reached the
 * database only shows up here.
 */
class AgentCliBindingUniqueIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun insert(
        agentId: Long,
        cliId: Long,
    ): Int = jdbc.update(
        "INSERT INTO agent_cli_binding (agent_id, cli_id, create_time, update_time) VALUES (?, ?, NOW(), NOW())",
        agentId,
        cliId,
    )

    @Test
    @DisplayName("同一 agent 重复绑同一 CLI 被数据库拒绝")
    fun `a second binding of the same cli for one agent is refused`() {
        val agentId = 910_001L
        val cliId = 910_002L
        jdbc.update("DELETE FROM agent_cli_binding WHERE agent_id = ?", agentId)
        try {
            insert(agentId, cliId)
            assertThrows<DuplicateKeyException> { insert(agentId, cliId) }
        } finally {
            jdbc.update("DELETE FROM agent_cli_binding WHERE agent_id = ?", agentId)
        }
    }

    @Test
    @DisplayName("两个 agent 绑同一 CLI 照常写入")
    fun `two agents may bind the same cli`() {
        val cliId = 910_003L
        val agents = listOf(910_004L, 910_005L)
        jdbc.update("DELETE FROM agent_cli_binding WHERE cli_id = ?", cliId)
        try {
            assertEquals(1, insert(agents[0], cliId))
            assertEquals(1, insert(agents[1], cliId))
            val rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_cli_binding WHERE cli_id = ?",
                Int::class.java,
                cliId,
            )
            assertEquals(agents.size, rows)
        } finally {
            jdbc.update("DELETE FROM agent_cli_binding WHERE cli_id = ?", cliId)
        }
    }
}
