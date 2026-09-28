package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentSkillBinding
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DuplicateKeyException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `selectAgentBindingCounts` integration test: the disable/delete guards and the skill list all read this
 * one grouped result, so the SQL itself has to be proven against a real MySQL — one row per skill, no row
 * for a skill nothing is bound to. Per-agent de-duplication is no longer a thing the schema can express,
 * since the consolidated baseline carries UNIQUE(agent_id, skill_id); the case that pins that key is here
 * for the same reason.
 *
 * @author agnetix
 * @since 2026-09-18
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class AgentSkillBindingMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var bindingMapper: AgentSkillBindingMapper

    private fun bind(
        agentId: Long,
        skillId: Long,
    ) {
        bindingMapper.batchInsert(
            listOf(
                AgentSkillBinding().apply {
                    this.agentId = agentId
                    this.skillId = skillId
                    createTime = LocalDateTime.now()
                    updateTime = LocalDateTime.now()
                },
            ),
        )
    }

    @Test
    @DisplayName("selectAgentBindingCounts - 每个技能一行，按 agent 计数")
    fun aggregatesOneRowPerSkill() {
        // The baseline carries UNIQUE(agent_id, skill_id), so one agent can contribute at most one
        // row per skill: what this asserts is the grouping, one row per requested skill.
        bind(7L, 100L)
        bind(8L, 100L)
        bind(9L, 101L)

        val counts = bindingMapper.selectAgentBindingCounts(listOf(100L, 101L, 102L)).associate { it.skillId to it.agentCount }

        assertEquals(mapOf(100L to 2, 101L to 1), counts)
    }

    @Test
    @DisplayName("重复绑定同一 agent 与技能被唯一键拒绝")
    fun duplicateBindingIsRejected() {
        // This is why COUNT(DISTINCT agent_id) in the mapper is defence only: the schema itself
        // refuses the duplicate row an unguarded write would produce.
        bind(7L, 100L)

        assertThrows<DuplicateKeyException> { bind(7L, 100L) }
    }

    @Test
    @DisplayName("selectAgentBindingCounts - 未绑定的技能不出现在结果里")
    fun omitsUnboundSkills() {
        val counts = bindingMapper.selectAgentBindingCounts(listOf(999L))

        assertTrue(counts.isEmpty())
    }
}
