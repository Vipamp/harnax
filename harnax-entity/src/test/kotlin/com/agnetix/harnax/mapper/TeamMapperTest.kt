package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Team
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

/**
 * 主管配置的读写：`team` 自己持有 `system_prompt` 与 `model_id`（D1），运行侧对这两项没有回退路径，
 * 所以它们必须原样进出，而不是靠服务层每次再拼一遍。
 *
 * @author agnetix
 * @since 2026-09-20
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class TeamMapperTest {

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
    private lateinit var teamMapper: TeamMapper

    private fun insertTeam(
        systemPrompt: String,
        modelId: Long,
    ): Team {
        val team = Team().apply {
            tenantId = 1
            name = "研究报告团队-${System.nanoTime()}"
            description = "负责资料收集、分析与报告生成"
            this.systemPrompt = systemPrompt
            this.modelId = modelId
            status = 1
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        teamMapper.insert(team)
        return team
    }

    @Test
    @DisplayName("insert/selectById - 主管的提示词与模型随团队读写")
    fun leadConfigRoundTrips() {
        val inserted = insertTeam("你是本次协作的负责人，只拆解与验收", 7L)

        val saved = teamMapper.selectById(inserted.id)!!

        assertEquals("你是本次协作的负责人，只拆解与验收", saved.systemPrompt)
        assertEquals(7L, saved.modelId)
    }

    @Test
    @DisplayName("updateById - 换模型与改提示词都落库")
    fun updateReplacesLeadConfig() {
        val team = insertTeam("旧提示词", 1L)

        team.systemPrompt = "新提示词"
        team.modelId = 9L

        assertEquals(1, teamMapper.updateById(team))
        val reloaded = teamMapper.selectById(team.id)!!
        assertEquals("新提示词", reloaded.systemPrompt)
        assertEquals(9L, reloaded.modelId)
    }
}
