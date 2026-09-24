package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TeamSkillBinding
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 主管技能绑定的读写：`team_skill_binding` 与 `agent_skill_binding` 形状相同，但实体测试库里只有团队这
 * 一份带 `UNIQUE(team_id, skill_id)`——agent 那份刻意留在 V33 之前的样子，好让
 * [AgentSkillBindingMapperTest] 用重复行去验 `COUNT(DISTINCT ...)`。
 * 这里验团队侧自己的一套：按团队取回、整表替换、删技能时清干净、重复绑定进不了库，以及技能列表读的那个分组计数。
 *
 * @author agnetix
 * @since 2026-09-20
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class TeamSkillBindingMapperTest {

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
    private lateinit var bindingMapper: TeamSkillBindingMapper

    private fun bind(
        teamId: Long,
        vararg skillIds: Long,
    ) {
        bindingMapper.batchInsert(
            skillIds.map {
                TeamSkillBinding().apply {
                    this.teamId = teamId
                    this.skillId = it
                    createTime = LocalDateTime.now()
                    updateTime = LocalDateTime.now()
                }
            },
        )
    }

    @Test
    @DisplayName("selectByTeamId - 按插入顺序取回主管的技能")
    fun readsBackInInsertionOrder() {
        bind(11L, 100L, 101L)

        assertEquals(listOf(100L, 101L), bindingMapper.selectByTeamId(11L).map { it.skillId })
    }

    @Test
    @DisplayName("batchInsert - 同一团队重复绑同一技能被唯一键挡下")
    fun rejectsDuplicateBinding() {
        bind(12L, 100L)

        assertFailsWith<DuplicateKeyException> { bind(12L, 100L) }
    }

    @Test
    @DisplayName("deleteByTeamId - 换主管技能前清掉该团队的全部绑定")
    fun clearsTeamBindings() {
        bind(13L, 100L, 101L)
        bind(14L, 102L)

        assertEquals(2, bindingMapper.deleteByTeamId(13L))
        assertEquals(emptyList(), bindingMapper.selectByTeamId(13L))
        assertEquals(listOf(102L), bindingMapper.selectByTeamId(14L).map { it.skillId })
    }

    @Test
    @DisplayName("selectBoundSkillIds - 只回被查且确有绑定的技能 id")
    fun reportsWhichSkillsAreBound() {
        bind(17L, 100L, 101L)

        assertEquals(
            listOf(100L, 101L),
            bindingMapper.selectBoundSkillIds(listOf(100L, 101L, 102L)).sorted(),
        )
    }

    @Test
    @DisplayName("selectTeamBindingCounts - 每个技能一行，按团队计数")
    fun countsTeamsPerSkill() {
        // 本表的 UNIQUE(team_id, skill_id) 让"同团队重复绑同一技能"进不了库，所以这条验的是分组与计数，
        // 不是 `DISTINCT` 本身——那个读法由 [AgentSkillBindingMapperTest] 用重复行验
        bind(21L, 100L, 101L)
        bind(22L, 100L)

        val counts = bindingMapper.selectTeamBindingCounts(listOf(100L, 101L, 102L)).associate { it.skillId to it.teamCount }

        assertEquals(mapOf(100L to 2, 101L to 1), counts)
    }

    @Test
    @DisplayName("selectTeamBindingCounts - 未绑定的技能不出现在结果里")
    fun omitsUnboundSkillsFromCounts() {
        assertTrue(bindingMapper.selectTeamBindingCounts(listOf(999L)).isEmpty())
    }
}
