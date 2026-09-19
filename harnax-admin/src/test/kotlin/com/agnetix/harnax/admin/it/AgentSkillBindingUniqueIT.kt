package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Checks the `UNIQUE(agent_id, skill_id)` key V33 puts on `agent_skill_binding`.
 *
 * The wizard's write path de-duplicates ids before it inserts, so this is the half that has to hold
 * no matter which caller arrives: `POST /skills/batch` on the CLI side and any future binding writer
 * go straight to the table. Asserted through SQL rather than the agent API because the API now
 * refuses the duplicate earlier, and the two guards are meant to fail independently.
 */
class AgentSkillBindingUniqueIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun insert(
        agentId: Long,
        skillId: Long,
    ): Int = jdbc.update(
        "INSERT INTO agent_skill_binding (agent_id, skill_id, create_time, update_time) VALUES (?, ?, NOW(), NOW())",
        agentId,
        skillId,
    )

    @Test
    @DisplayName("同一 agent 重复绑同一技能被数据库拒绝")
    fun `a second binding of the same skill for one agent is refused`() {
        val agentId = 900_001L
        val skillId = 900_002L
        jdbc.update("DELETE FROM agent_skill_binding WHERE agent_id = ?", agentId)
        try {
            insert(agentId, skillId)
            assertThrows<DuplicateKeyException> { insert(agentId, skillId) }
        } finally {
            jdbc.update("DELETE FROM agent_skill_binding WHERE agent_id = ?", agentId)
        }
    }

    @Test
    @DisplayName("两个 agent 绑同一技能照常写入")
    fun `two agents may bind the same skill`() {
        val skillId = 900_003L
        val agents = listOf(900_004L, 900_005L)
        jdbc.update("DELETE FROM agent_skill_binding WHERE skill_id = ?", skillId)
        try {
            assertEquals(1, insert(agents[0], skillId))
            assertEquals(1, insert(agents[1], skillId))
            val rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_skill_binding WHERE skill_id = ?",
                Int::class.java,
                skillId,
            )
            assertEquals(agents.size, rows)
        } finally {
            jdbc.update("DELETE FROM agent_skill_binding WHERE skill_id = ?", skillId)
        }
    }
}
