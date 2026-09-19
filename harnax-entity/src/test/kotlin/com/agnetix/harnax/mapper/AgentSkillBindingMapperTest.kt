package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentSkillBinding
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
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
 * `selectAgentBindingCounts` 集成测试：停用/删除守卫与技能列表都读这一个分组结果，
 * 所以 SQL 本身（按技能聚合、按 agent 去重、未绑定时不出现）必须被真实 MySQL 验一次。
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
    @DisplayName("selectAgentBindingCounts - 每个技能一行，按 agent 去重计数")
    fun countsDistinctAgentsPerSkill() {
        // 7 绑两次同一个技能：V33 之前存量里就有这种重复行，COUNT(agent_id) 会把它读成两个 agent
        bind(7L, 100L)
        bind(7L, 100L)
        bind(8L, 100L)
        bind(9L, 101L)

        val counts = bindingMapper.selectAgentBindingCounts(listOf(100L, 101L, 102L)).associate { it.skillId to it.agentCount }

        assertEquals(mapOf(100L to 2, 101L to 1), counts)
    }

    @Test
    @DisplayName("selectAgentBindingCounts - 未绑定的技能不出现在结果里")
    fun omitsUnboundSkills() {
        val counts = bindingMapper.selectAgentBindingCounts(listOf(999L))

        assertTrue(counts.isEmpty())
    }
}
