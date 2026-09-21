package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.SkillMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness

/**
 * The four write-time skill guards, in one place because two owners bind skills now: an agent and a
 * team's lead. The refusals have to read identically from either entry point — an operator who hits one
 * on the agent page must not get a vaguer or different message on the team page, and neither page may
 * accept a binding the runtime would later drop with only a log line.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillBindingResolverTest {

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    private val skills = mutableMapOf<Long, Skill>()
    private lateinit var resolver: SkillBindingResolver

    @BeforeEach
    fun setUp() {
        TenantContext.setTenantId(TENANT)
        `when`(skillMapper.selectByIds(any())).thenAnswer { invocation ->
            invocation.getArgument<List<Long>>(0).mapNotNull { skills[it] }
        }
        `when`(skillRepositoryService.getBuiltinRepository())
            .thenReturn(SkillRepository().apply { id = BUILTIN_REPO })
        resolver = SkillBindingResolver(skillMapper, skillRepositoryService)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    private fun skill(
        id: Long,
        name: String,
        status: Int = 1,
        tenantId: Long = TENANT,
        repositoryId: Long = 10L,
    ): Skill = Skill().apply {
        this.id = id
        this.name = name
        this.status = status
        this.tenantId = tenantId
        this.repositoryId = repositoryId
    }.also { skills[id] = it }

    @Test
    @DisplayName("缺失、已删除或越出租户的技能 id 被点名拒绝")
    fun refusesUnresolvableIds() {
        skill(1L, "检索规范")

        val error = assertThrows<BizException> { resolver.resolveBindable(listOf(1L, 5L)) }

        assertEquals("Skill is missing, deleted, or outside your tenant: 5", error.message)
    }

    @Test
    @DisplayName("停用技能不能被绑定")
    fun refusesDisabledSkill() {
        skill(1L, "检索规范", status = 0)

        val error = assertThrows<BizException> { resolver.resolveBindable(listOf(1L)) }

        assertEquals("Skill is disabled, enable it before binding: 检索规范", error.message)
    }

    @Test
    @DisplayName("内置 CLI 仓库的技能只能由 CLI 绑定引出，不能直接绑")
    fun refusesBuiltinCliSkill() {
        skill(1L, "cli 内置技能", repositoryId = BUILTIN_REPO)

        val error = assertThrows<BizException> { resolver.resolveBindable(listOf(1L)) }

        assertEquals(
            "Skills from '${BuiltinRepository.CLI_SKILLS}' cannot be bound directly (auto-loaded via CLI): cli 内置技能",
            error.message,
        )
    }

    @Test
    @DisplayName("运行侧按名索引技能，同名绑定必须被拒")
    fun refusesDuplicateNames() {
        skill(1L, "同名", repositoryId = 10L)
        skill(2L, "同名", repositoryId = 20L)

        val error = assertThrows<BizException> { resolver.resolveBindable(listOf(1L, 2L)) }

        assertEquals("Skills bound together must have distinct names, duplicated: 同名", error.message)
    }

    @Test
    @DisplayName("同一请求里的重复 id 折叠成一条，不会撞唯一键")
    fun collapsesRepeatedIds() {
        skill(1L, "检索规范")

        assertEquals(listOf(1L), resolver.resolveBindable(listOf(1L, 1L)).map { it.id })
    }

    @Test
    @DisplayName("内置仓库是全平台一行，其技能的租户豁免仍然成立")
    fun exemptsBuiltinRepositoryFromTheTenantFilter() {
        skill(1L, "cli 内置技能", tenantId = TENANT + 1, repositoryId = BUILTIN_REPO)

        // Rejected for being a builtin skill, not for its tenant — the exemption let it get that far
        val error = assertThrows<BizException> { resolver.resolveBindable(listOf(1L)) }

        assertEquals(
            "Skills from '${BuiltinRepository.CLI_SKILLS}' cannot be bound directly (auto-loaded via CLI): cli 内置技能",
            error.message,
        )
    }
}

private const val TENANT = 7L
private const val BUILTIN_REPO = 99L
