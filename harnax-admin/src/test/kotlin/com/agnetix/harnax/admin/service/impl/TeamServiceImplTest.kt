package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.TeamCreateRequest
import com.agnetix.harnax.admin.dto.TeamUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.skill.SkillBindingResolver
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.entity.TeamMember
import com.agnetix.harnax.entity.TeamSkillBinding
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import com.github.pagehelper.PageHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * TeamServiceImpl Unit Tests.
 *
 * The service is the only place a team can be made unresolvable, so most of the value here is in the
 * save-time refusals. Since V34 the lead's own configuration — prompt, model, skills — is saved here too,
 * which adds a second thing that can be written badly: none of it has a fallback at runtime.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamServiceImplTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var teamMapper: TeamMapper

    @Mock
    private lateinit var teamMemberMapper: TeamMemberMapper

    @Mock
    private lateinit var teamSkillBindingMapper: TeamSkillBindingMapper

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var modelMapper: ModelMapper

    @Mock
    private lateinit var modelService: ModelService

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @Mock
    private lateinit var skillBindingResolver: SkillBindingResolver

    private val agents = mutableMapOf<Long, Agent>()
    private val models = mutableMapOf<Long, Model>()
    private val skills = mutableMapOf<Long, Skill>()
    private val insertedTeams = mutableListOf<Team>()
    private val insertedMembers = mutableListOf<TeamMember>()
    private val insertedBindings = mutableListOf<TeamSkillBinding>()
    private lateinit var service: TeamServiceImpl

    @BeforeEach
    fun setUp() {
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))
        `when`(jwtUtil.validateToken(anyString())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(anyString())).thenReturn(CURRENT_USER)
        TenantContext.setTenantId(TENANT)

        agent(MEMBER_A, "Researcher")
        agent(MEMBER_B, "Writer")
        model(MODEL, "qwen3-max")
        skill(100L, "资料检索规范")
        skill(101L, "报告撰写规范")

        `when`(agentMapper.selectById(anyLong())).thenAnswer { agents[it.getArgument<Long>(0)] }
        `when`(agentMapper.selectByIds(any())).thenAnswer { invocation ->
            invocation.getArgument<List<Long>>(0).mapNotNull { agents[it] }
        }
        `when`(modelMapper.selectById(anyLong())).thenAnswer { models[it.getArgument<Long>(0)] }
        `when`(modelService.getVisibleModel(anyLong())).thenAnswer { models[it.getArgument<Long>(0)] }
        `when`(skillBindingResolver.deliverable(any(), anyLong())).thenAnswer { invocation ->
            invocation.getArgument<List<Long>>(0).mapNotNull { skills[it] }
        }
        `when`(skillRepositoryService.getSkillRepository(anyLong())).thenAnswer { invocation ->
            val id = invocation.getArgument<Long>(0)
            SkillRepository().apply {
                this.id = id
                name = "repo-$id"
            }
        }
        `when`(skillBindingResolver.resolveBindable(any())).thenAnswer { invocation ->
            invocation.getArgument<List<Long>>(0).mapNotNull { skills[it] }
        }
        `when`(teamMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<Team>(0).let { team ->
                team.id = TEAM_ID
                insertedTeams.add(team)
            }
            1
        }
        `when`(teamMapper.updateById(any())).thenReturn(1)
        `when`(teamMapper.deleteById(anyLong())).thenReturn(1)
        `when`(teamMapper.updateStatus(anyLong(), anyInt())).thenReturn(1)
        `when`(teamMemberMapper.deleteByTeamId(anyLong())).thenReturn(1)
        `when`(teamMemberMapper.batchInsert(any())).thenAnswer { invocation ->
            insertedMembers.addAll(invocation.getArgument<List<TeamMember>>(0))
            1
        }
        `when`(teamSkillBindingMapper.deleteByTeamId(anyLong())).thenReturn(1)
        `when`(teamSkillBindingMapper.batchInsert(any())).thenAnswer { invocation ->
            insertedBindings.addAll(invocation.getArgument<List<TeamSkillBinding>>(0))
            1
        }

        service = TeamServiceImpl(
            jwtUtil = jwtUtil,
            teamMapper = teamMapper,
            teamMemberMapper = teamMemberMapper,
            teamSkillBindingMapper = teamSkillBindingMapper,
            agentMapper = agentMapper,
            modelMapper = modelMapper,
            modelService = modelService,
            sessionMapper = sessionMapper,
            skillRepositoryService = skillRepositoryService,
            skillBindingResolver = skillBindingResolver,
        )
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
        PageHelper.clearPage()
    }

    @Nested
    @DisplayName("Create validation")
    inner class CreateValidation {

        @Test
        fun `a name already used in this tenant is refused before anything is written`() {
            `when`(teamMapper.selectByName("Research", TENANT)).thenReturn(team(name = "Research"))

            val error = assertThrows<BizException> { service.createTeam(createRequest(name = "Research")) }

            assertEquals("Team name already exists", error.message)
            verify(teamMapper, never()).insert(any())
        }

        @Test
        fun `the same name in another tenant is not a clash`() {
            `when`(teamMapper.selectByName("Research", TENANT)).thenReturn(null)

            assertTrue(service.createTeam(createRequest(name = "Research")))
        }

        @Test
        fun `a model that does not exist names the id it could not resolve`() {
            val error = assertThrows<BizException> { service.createTeam(createRequest(modelId = GONE)) }

            assertEquals("Lead model not found: $GONE", error.message)
            verify(teamMapper, never()).insert(any())
        }

        @Test
        fun `a private model of another tenant is refused`() {
            models[MODEL] = model(MODEL, "qwen3-max", isPublic = 0, tenantId = TENANT + 1)

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Lead model is not available to the current tenant: $MODEL", error.message)
        }

        @Test
        fun `a private model of another user in this tenant may lead`() {
            // 隔离粒度是租户：同租户的私有行对同租户可选，模型列表也是这么给的
            models[MODEL] = model(MODEL, "qwen3-max", isPublic = 0, creator = "other-user")

            assertTrue(service.createTeam(createRequest()))
        }

        @Test
        fun `a public model of another tenant may lead`() {
            models[MODEL] = model(MODEL, "qwen3-max", isPublic = 1, tenantId = TENANT + 1)

            assertTrue(service.createTeam(createRequest()))
        }

        @Test
        fun `a disabled model is refused as the lead model`() {
            models[MODEL] = model(MODEL, "qwen3-max", status = 0)

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Lead model is disabled: qwen3-max-2026-07-15", error.message)
            verify(teamMapper, never()).insert(any())
        }

        @Test
        fun `a non-chat model is refused as the lead model`() {
            models[MODEL] = model(MODEL, "qwen3-max", modelType = "embedding")

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Lead model is not a chat model: qwen3-max-2026-07-15", error.message)
            verify(teamMapper, never()).insert(any())
        }

        @Test
        fun `a private member agent of another user is refused`() {
            agents[MEMBER_B] = agent(MEMBER_B, "Writer", isPublic = 0, creator = "other-user")

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Member agent is not available to the current user: Writer", error.message)
        }

        @Test
        fun `a disabled member agent is refused`() {
            agents[MEMBER_A] = agent(MEMBER_A, "Researcher", status = 0)

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Member agent is disabled: Researcher", error.message)
            verify(teamMapper, never()).insert(any())
        }

        @Test
        fun `a team without members is refused`() {
            val error = assertThrows<BizException> { service.createTeam(createRequest(members = emptyList())) }

            assertEquals("A team needs at least one member", error.message)
        }

        @Test
        fun `a missing member list is refused the same way`() {
            val error = assertThrows<BizException> { service.createTeam(createRequest(members = null)) }

            assertEquals("A team needs at least one member", error.message)
        }

        @Test
        fun `a member without an agent id is refused`() {
            val request = createRequest(members = listOf(member(MEMBER_A), member(null)))

            val error = assertThrows<BizException> { service.createTeam(request) }

            assertEquals("Member agent ID cannot be empty", error.message)
        }

        @Test
        fun `the same member listed twice is refused`() {
            val request = createRequest(members = listOf(member(MEMBER_A), member(MEMBER_A)))

            val error = assertThrows<BizException> { service.createTeam(request) }

            assertEquals("Duplicate members in the team", error.message)
        }

        @Test
        fun `a lead agent and a member can be the same agent because the lead is no longer an agent`() {
            // The rule this replaces refused a lead that also appeared as a member. Members are still
            // plain agents, so an agent a team leads with is delegatable to like any other.
            assertTrue(service.createTeam(createRequest(members = listOf(member(MEMBER_A)))))
        }

        @Test
        fun `bad skills are refused by the same guard the agent page uses`() {
            `when`(skillBindingResolver.resolveBindable(any())).thenThrow(BizException("Skill is disabled, enable it before binding: 检索规范"))

            val error = assertThrows<BizException> { service.createTeam(createRequest(skillIds = listOf(100L))) }

            assertEquals("Skill is disabled, enable it before binding: 检索规范", error.message)
            verify(teamMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Create persistence")
    inner class CreatePersistence {

        @Test
        fun `the saved row carries the lead's prompt and model plus the caller's tenant and creator`() {
            assertTrue(service.createTeam(createRequest(name = "Research")))

            val team = insertedTeams.single()
            assertEquals("Research", team.name)
            assertEquals(LEAD_PROMPT, team.systemPrompt)
            assertEquals(MODEL, team.modelId)
            assertEquals(TENANT, team.tenantId)
            assertEquals(CURRENT_USER, team.creator)
            assertEquals(1, team.status)
            assertEquals(0, team.isPublic)
            assertEquals(1, team.active)
        }

        @Test
        fun `explicit status and visibility are kept`() {
            assertTrue(service.createTeam(createRequest(description = "reads sources", status = 0, isPublic = 1)))

            val team = insertedTeams.single()
            assertEquals("reads sources", team.description)
            assertEquals(0, team.status)
            assertEquals(1, team.isPublic)
        }

        @Test
        fun `member order survives and a blank role note starts from the agent description`() {
            val request = createRequest(
                members = listOf(
                    member(MEMBER_B, "drafts the report"),
                    member(MEMBER_A, "   "),
                    TeamCreateRequest.MemberItem(agentId = 4L),
                ),
            )
            agents[4L] = agent(4L, "Reviewer")

            assertTrue(service.createTeam(request))

            assertEquals(listOf(MEMBER_B, MEMBER_A, 4L), insertedMembers.map { it.memberAgentId })
            assertEquals(listOf("drafts the report", "Researcher description", "Reviewer description"), insertedMembers.map { it.delegationDescription })
            assertTrue(insertedMembers.all { it.teamId == TEAM_ID })
        }

        @Test
        fun `the lead's skills are bound to the team and no agent row is created for them`() {
            assertTrue(service.createTeam(createRequest(skillIds = listOf(100L, 101L))))

            assertEquals(listOf(100L, 101L), insertedBindings.map { it.skillId })
            assertTrue(insertedBindings.all { it.teamId == TEAM_ID })
            verify(agentMapper, never()).insert(any())
        }

        @Test
        fun `no skill list writes no binding rows`() {
            assertTrue(service.createTeam(createRequest(skillIds = null)))

            assertTrue(insertedBindings.isEmpty())
            verify(teamSkillBindingMapper, never()).batchInsert(any())
        }

        @Test
        fun `members and skills are not written when the team row itself failed to insert`() {
            `when`(teamMapper.insert(any())).thenReturn(0)

            assertFalse(service.createTeam(createRequest(skillIds = listOf(100L))))

            assertTrue(insertedMembers.isEmpty())
            assertTrue(insertedBindings.isEmpty())
        }
    }

    @Nested
    @DisplayName("Update")
    inner class Update {

        @Test
        fun `an unknown team id is refused`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(null)

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest(name = "Renamed")) }

            assertEquals("Team not found", error.message)
            verify(teamMapper, never()).updateById(any())
        }

        @Test
        fun `another tenant's team cannot be updated`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team(tenantId = TENANT + 1))

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest(name = "Renamed")) }

            assertEquals("Team belongs to another tenant", error.message)
            verify(teamMapper, never()).updateById(any())
        }

        @Test
        fun `renaming onto a name this tenant already took is refused`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(teamMapper.selectByName("Ops", TENANT)).thenReturn(team(name = "Ops"))

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest(name = "Ops")) }

            assertEquals("Team name already exists", error.message)
        }

        @Test
        fun `saving the unchanged name is not treated as a clash`() {
            val team = team()
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(name = "Research")))

            verify(teamMapper, never()).selectByName(anyString(), anyLong())
            assertEquals("Research", team.name)
        }

        @Test
        fun `a new prompt and model replace the lead's configuration`() {
            val team = team()
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))
            model(222L, "gpt-5")

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(systemPrompt = "新提示词", modelId = 222L)))

            assertEquals("新提示词", team.systemPrompt)
            assertEquals(222L, team.modelId)
        }

        @Test
        fun `a model that went missing stops the update before anything is written`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest(modelId = GONE)) }

            assertEquals("Lead model not found: $GONE", error.message)
            verify(teamMapper, never()).updateById(any())
        }

        @Test
        fun `null fields leave the stored values alone`() {
            val team = team(name = "Research", tenantId = TENANT).apply {
                description = "old description"
                systemPrompt = "old prompt"
                modelId = MODEL
                isPublic = 1
            }
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            service.updateTeam(TEAM_ID, TeamUpdateRequest())

            assertEquals("Research", team.name)
            assertEquals("old description", team.description)
            assertEquals("old prompt", team.systemPrompt)
            assertEquals(MODEL, team.modelId)
            assertEquals(1, team.isPublic)
        }

        @Test
        fun `an absent member payload keeps the stored membership and does not rewrite rows`() {
            val team = team()
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A), binding(MEMBER_B)))

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(description = "new note")))

            verify(teamMemberMapper, never()).deleteByTeamId(anyLong())
            verify(teamMemberMapper, never()).batchInsert(any())
            assertEquals("new note", team.description)
        }

        @Test
        fun `a member payload replaces the membership, wiping the old rows first`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(members = listOf(member(MEMBER_A), member(MEMBER_B)))))

            val order = inOrder(teamMemberMapper)
            order.verify(teamMemberMapper).deleteByTeamId(TEAM_ID)
            order.verify(teamMemberMapper).batchInsert(any())
            assertEquals(listOf(MEMBER_A, MEMBER_B), insertedMembers.map { it.memberAgentId })
        }

        @Test
        fun `the stored membership is revalidated on every save`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(GONE)))

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest(description = "note")) }

            assertEquals("Member agent not found: $GONE", error.message)
            verify(teamMapper, never()).updateById(any())
        }

        @Test
        fun `an absent skill payload keeps the stored skills`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(skillIds = null)))

            verify(teamSkillBindingMapper, never()).deleteByTeamId(anyLong())
            verify(teamSkillBindingMapper, never()).batchInsert(any())
        }

        @Test
        fun `a skill payload replaces the whole set and an empty one clears it`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(skillIds = listOf(101L, 100L))))
            assertEquals(listOf(101L, 100L), insertedBindings.map { it.skillId })

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(skillIds = emptyList())))
            verify(teamSkillBindingMapper, org.mockito.Mockito.times(2)).deleteByTeamId(TEAM_ID)
        }
    }

    @Nested
    @DisplayName("Status, delete and read scoping")
    inner class StatusAndDelete {

        @Test
        fun `toggling an unknown team is refused`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(null)

            val error = assertThrows<BizException> { service.toggleTeamStatus(TEAM_ID, 0) }

            assertEquals("Team not found", error.message)
            verify(teamMapper, never()).updateStatus(anyLong(), anyInt())
        }

        @Test
        fun `toggling another tenant's team is refused`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team(tenantId = TENANT + 1))

            val error = assertThrows<BizException> { service.toggleTeamStatus(TEAM_ID, 0) }

            assertEquals("Team belongs to another tenant", error.message)
        }

        @Test
        fun `a team of this tenant is disabled`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())

            assertTrue(service.toggleTeamStatus(TEAM_ID, 0))

            verify(teamMapper).updateStatus(TEAM_ID, 0)
        }

        @Test
        fun `delete removes the member and skill bindings before the team`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())

            assertTrue(service.deleteTeam(TEAM_ID))

            val order = inOrder(teamMemberMapper, teamSkillBindingMapper, teamMapper)
            order.verify(teamMemberMapper).deleteByTeamId(TEAM_ID)
            order.verify(teamSkillBindingMapper).deleteByTeamId(TEAM_ID)
            order.verify(teamMapper).deleteById(TEAM_ID)
        }

        @Test
        fun `delete of another tenant's team removes nothing`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team(tenantId = TENANT + 1))

            val error = assertThrows<BizException> { service.deleteTeam(TEAM_ID) }

            assertEquals("Team belongs to another tenant", error.message)
            verify(teamMapper, never()).deleteById(anyLong())
            verify(teamMemberMapper, never()).deleteByTeamId(anyLong())
            verify(teamSkillBindingMapper, never()).deleteByTeamId(anyLong())
        }

        @Test
        fun `delete refuses while the team still has sessions, naming them`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(sessionMapper.selectByTeamId(TEAM_ID)).thenReturn(
                listOf(session("web-1", "first"), session("web-2", "second")),
            )

            val error = assertThrows<BizException> { service.deleteTeam(TEAM_ID) }

            assertTrue(error.message!!.contains("2 session(s)"))
            assertTrue(error.message!!.contains("web-1"))
            assertTrue(error.message!!.contains("web-2"))
            verify(teamMapper, never()).deleteById(anyLong())
            verify(teamMemberMapper, never()).deleteByTeamId(anyLong())
            verify(teamSkillBindingMapper, never()).deleteByTeamId(anyLong())
        }

        @Test
        fun `delete proceeds once the team has no sessions left`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(sessionMapper.selectByTeamId(TEAM_ID)).thenReturn(emptyList())

            assertTrue(service.deleteTeam(TEAM_ID))
        }

        @Test
        fun `update hides a private team of another user`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(
                team().apply {
                    isPublic = 0
                    creator = "someone-else"
                },
            )

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest()) }

            assertEquals("Team not found", error.message)
            verify(teamMapper, never()).updateById(any())
        }

        @Test
        fun `getTeam hides a private team of another user`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(
                team().apply {
                    isPublic = 0
                    creator = "someone-else"
                },
            )

            assertNull(service.getTeam(TEAM_ID))
        }

        @Test
        fun `getTeam hides another tenant's team`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team(tenantId = TENANT + 1))

            assertNull(service.getTeam(TEAM_ID))
        }

        @Test
        fun `getTeam returns this tenant's team`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())

            assertEquals(TEAM_ID, service.getTeam(TEAM_ID)?.id)
        }

        @Test
        fun `page reads under the caller's user and tenant`() {
            `when`(teamMapper.selectTeamList("re", 1, CURRENT_USER, TENANT)).thenReturn(listOf(team()))

            val page = service.page("re", 1, 1, 10)

            assertEquals(listOf(TEAM_ID), page.records.map { it.id })
            verify(teamMapper).selectTeamList("re", 1, CURRENT_USER, TENANT)
        }
    }

    @Nested
    @DisplayName("Related sessions")
    inner class RelatedSessions {

        @Test
        fun `an unknown team is refused`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(null)

            val error = assertThrows<BizException> { service.listRelatedSessions(TEAM_ID) }

            assertEquals("Team not found", error.message)
        }

        @Test
        fun `another tenant's team sessions cannot be listed`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team(tenantId = TENANT + 1))

            val error = assertThrows<BizException> { service.listRelatedSessions(TEAM_ID) }

            assertEquals("Team belongs to another tenant", error.message)
            verify(sessionMapper, never()).selectByTeamId(anyLong())
        }

        @Test
        fun `blank session ids drop out, titles name the rest and duplicates collapse`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(sessionMapper.selectByTeamId(TEAM_ID)).thenReturn(
                listOf(
                    session("s-1", "Weekly report"),
                    session("s-1", "same session again"),
                    session("", "never started"),
                    session("s-2", ""),
                ),
            )

            val related = service.listRelatedSessions(TEAM_ID)

            assertEquals(listOf("s-1" to "Weekly report", "s-2" to "s-2"), related.map { it.sessionId to it.sourceName })
            assertTrue(related.all { it.sourceType == "session" })
        }
    }

    @Nested
    @DisplayName("Response assembly")
    inner class ResponseAssembly {

        @Test
        fun `the lead's own configuration and its skills come back with the members in order`() {
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(
                listOf(binding(MEMBER_A, "gathers"), binding(MEMBER_B, "drafts")),
            )
            `when`(teamSkillBindingMapper.selectByTeamId(TEAM_ID)).thenReturn(
                listOf(skillBinding(100L), skillBinding(101L)),
            )

            val response = service.convertToResponse(team())

            assertEquals(LEAD_PROMPT, response.systemPrompt)
            assertEquals(MODEL, response.modelId)
            // The agent listing shows `modelName`; so must this one, or the same model reads as two
            // different strings on the two pages.
            assertEquals("qwen3-max-2026-07-15", response.modelName)
            assertEquals(listOf(100L to "资料检索规范", 101L to "报告撰写规范"), response.skillList.map { it.skillId to it.skillName })
            assertEquals(listOf("Researcher" to "gathers", "Writer" to "drafts"), response.memberList.map { it.agentName to it.delegationDescription })
            assertTrue(response.memberList.all { it.agentAvailable && it.agentStatus == 1 })
        }

        @Test
        fun `a member whose agent is gone stays listed and is flagged unavailable`() {
            agents.remove(MEMBER_B)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A), binding(MEMBER_B)))

            val response = service.convertToResponse(team())

            val gone = response.memberList[1]
            assertEquals(MEMBER_B, gone.agentId)
            assertEquals("#$MEMBER_B", gone.agentName)
            assertFalse(gone.agentAvailable)
            assertNull(gone.agentStatus)
        }

        @Test
        fun `a bound skill whose row is gone stays listed by id instead of vanishing`() {
            skills.remove(101L)
            `when`(teamSkillBindingMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(skillBinding(100L), skillBinding(101L)))

            val response = service.convertToResponse(team())

            assertEquals(listOf(100L, 101L), response.skillList.map { it.skillId })
            assertEquals("#101", response.skillList[1].skillName)
        }

        @Test
        fun `the lead skills are read through the visibility resolver with the tenant it must respect`() {
            `when`(teamSkillBindingMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(skillBinding(100L), skillBinding(101L)))

            service.convertToResponse(team())

            // `skillMapper.selectByIds` carries no tenant condition, so a binding row saved before the
            // save-time guard existed would name another tenant's skill on this page.
            verify(skillBindingResolver).deliverable(listOf(100L, 101L), TENANT)
        }

        @Test
        fun `a bound skill the tenant may not receive is flagged instead of being described`() {
            `when`(teamSkillBindingMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(skillBinding(100L), skillBinding(101L)))
            `when`(skillBindingResolver.deliverable(listOf(100L, 101L), TENANT)).thenReturn(listOf(skills.getValue(100L)))

            val response = service.convertToResponse(team())

            assertEquals(listOf(true, false), response.skillList.map { it.skillAvailable })
            assertEquals("#101", response.skillList[1].skillName)
            assertNull(response.skillList[1].skillDescription)
        }

        @Test
        fun `a model whose row is gone still names the id the operator saved`() {
            models.remove(MODEL)

            val response = service.convertToResponse(team())

            assertEquals(MODEL, response.modelId)
            assertNull(response.modelName)
        }
    }

    private fun agent(
        id: Long,
        name: String,
        status: Int = 1,
        isPublic: Int = 1,
        creator: String = CURRENT_USER,
        tenantId: Long = TENANT,
    ): Agent = Agent().apply {
        this.id = id
        this.name = name
        this.description = "$name description"
        this.status = status
        this.isPublic = isPublic
        this.creator = creator
        this.tenantId = tenantId
    }.also { agents[id] = it }

    private fun model(
        id: Long,
        name: String,
        modelName: String = "$name-2026-07-15",
        status: Int = 1,
        isPublic: Int = 1,
        modelType: String = "chat",
        creator: String = CURRENT_USER,
        tenantId: Long = TENANT,
    ): Model = Model().apply {
        this.id = id
        this.name = name
        this.modelName = modelName
        this.status = status
        this.isPublic = isPublic
        this.modelType = modelType
        this.creator = creator
        this.tenantId = tenantId
    }.also { models[id] = it }

    private fun skill(
        id: Long,
        name: String,
    ): Skill = Skill().apply {
        this.id = id
        this.name = name
        this.description = "$name description"
        this.repositoryId = 10L
    }.also { skills[id] = it }

    private fun team(
        id: Long = TEAM_ID,
        name: String = "Research",
        tenantId: Long = TENANT,
    ): Team = Team().apply {
        this.id = id
        this.name = name
        this.tenantId = tenantId
        this.systemPrompt = LEAD_PROMPT
        this.modelId = MODEL
        this.creator = CURRENT_USER
    }

    private fun binding(agentId: Long, delegation: String = "gathering"): TeamMember = TeamMember().apply {
        teamId = TEAM_ID
        memberAgentId = agentId
        delegationDescription = delegation
    }

    private fun skillBinding(skillId: Long): TeamSkillBinding = TeamSkillBinding().apply {
        teamId = TEAM_ID
        this.skillId = skillId
    }

    private fun session(sessionId: String, title: String): Session = Session().apply {
        this.sessionId = sessionId
        this.title = title
        teamId = TEAM_ID
    }

    private fun member(agentId: Long?, delegation: String? = null): TeamCreateRequest.MemberItem = TeamCreateRequest.MemberItem(agentId = agentId, delegationDescription = delegation)

    private fun createRequest(
        name: String = "Research",
        description: String? = null,
        systemPrompt: String = LEAD_PROMPT,
        modelId: Long = MODEL,
        skillIds: List<Long>? = null,
        members: List<TeamCreateRequest.MemberItem>? = listOf(member(MEMBER_A), member(MEMBER_B)),
        status: Int? = null,
        isPublic: Int? = null,
    ): TeamCreateRequest = TeamCreateRequest(
        name = name,
        description = description,
        systemPrompt = systemPrompt,
        modelId = modelId,
        skillIds = skillIds,
        members = members,
        status = status,
        isPublic = isPublic,
    )

    companion object {
        private const val CURRENT_USER = "admin"
        private const val TENANT = 7L
        private const val TEAM_ID = 42L
        private const val MODEL = 11L
        private const val LEAD_PROMPT = "你是本次协作的负责人，只拆解、委派与验收"
        private const val MEMBER_A = 2L
        private const val MEMBER_B = 3L
        private const val GONE = 99L
    }
}
