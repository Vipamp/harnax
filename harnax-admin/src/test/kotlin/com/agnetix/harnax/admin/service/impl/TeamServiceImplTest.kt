package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.TeamCreateRequest
import com.agnetix.harnax.admin.dto.TeamUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.entity.TeamMember
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
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
 * save-time refusals: a team that saved cleanly is a team the runtime does not have to guess about.
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
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    private val agents = mutableMapOf<Long, Agent>()
    private val insertedTeams = mutableListOf<Team>()
    private val insertedMembers = mutableListOf<TeamMember>()
    private lateinit var service: TeamServiceImpl

    @BeforeEach
    fun setUp() {
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))
        `when`(jwtUtil.validateToken(anyString())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(anyString())).thenReturn(CURRENT_USER)
        TenantContext.setTenantId(TENANT)

        agent(LEAD, "Coordinator")
        agent(MEMBER_A, "Researcher")
        agent(MEMBER_B, "Writer")

        `when`(agentMapper.selectById(anyLong())).thenAnswer { agents[it.getArgument<Long>(0)] }
        `when`(agentMapper.selectByIds(any())).thenAnswer { invocation ->
            invocation.getArgument<List<Long>>(0).mapNotNull { agents[it] }
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

        service = TeamServiceImpl(
            jwtUtil = jwtUtil,
            teamMapper = teamMapper,
            teamMemberMapper = teamMemberMapper,
            agentMapper = agentMapper,
            sessionMapper = sessionMapper,
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
        fun `a lead agent that does not exist names the id it could not resolve`() {
            agents.remove(LEAD)

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Lead agent not found: $LEAD", error.message)
        }

        @Test
        fun `a lead agent owned by another tenant is refused`() {
            agents[LEAD] = agent(LEAD, "Coordinator", tenantId = TENANT + 1)

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Lead agent belongs to another tenant: $LEAD", error.message)
        }

        @Test
        fun `a disabled lead agent is refused`() {
            agents[LEAD] = agent(LEAD, "Coordinator", status = 0)

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Lead agent is disabled: Coordinator", error.message)
        }

        @Test
        fun `a private lead agent owned by someone else is refused`() {
            agents[LEAD] = agent(LEAD, "Coordinator", isPublic = 0, creator = "other-user")

            val error = assertThrows<BizException> { service.createTeam(createRequest()) }

            assertEquals("Lead agent is not available to the current user: Coordinator", error.message)
        }

        @Test
        fun `a private member agent of another user is refused even though the lead is usable`() {
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
        fun `the lead cannot also be a member`() {
            val request = createRequest(members = listOf(member(LEAD), member(MEMBER_A)))

            val error = assertThrows<BizException> { service.createTeam(request) }

            assertEquals("The lead agent cannot also be a member of the same team", error.message)
        }
    }

    @Nested
    @DisplayName("Create persistence")
    inner class CreatePersistence {

        @Test
        fun `the saved row carries the caller's tenant and creator plus the documented defaults`() {
            assertTrue(service.createTeam(createRequest(name = "Research")))

            val team = insertedTeams.single()
            assertEquals("Research", team.name)
            assertEquals(LEAD, team.leadAgentId)
            assertEquals(TENANT, team.tenantId)
            assertEquals(CURRENT_USER, team.creator)
            assertEquals("", team.description)
            assertEquals("", team.instructions)
            assertEquals(1, team.status)
            assertEquals(0, team.isPublic)
            assertEquals(1, team.active)
        }

        @Test
        fun `explicit status instructions and description are kept`() {
            val request = createRequest(description = "reads sources", instructions = "always cite", status = 0, isPublic = 1)

            assertTrue(service.createTeam(request))

            val team = insertedTeams.single()
            assertEquals("reads sources", team.description)
            assertEquals("always cite", team.instructions)
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
        fun `members are not written when the team row itself failed to insert`() {
            `when`(teamMapper.insert(any())).thenReturn(0)

            assertFalse(service.createTeam(createRequest()))

            assertTrue(insertedMembers.isEmpty())
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
        fun `the stored membership is revalidated against a new lead`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(LEAD)))

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest(description = "note")) }

            assertEquals("The lead agent cannot also be a member of the same team", error.message)
            verify(teamMapper, never()).updateById(any())
        }

        @Test
        fun `a lead that went unusable stops the update`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            val error = assertThrows<BizException> { service.updateTeam(TEAM_ID, TeamUpdateRequest(leadAgentId = GONE)) }

            assertEquals("Lead agent not found: $GONE", error.message)
            verify(teamMapper, never()).updateById(any())
        }

        @Test
        fun `a new lead is stored`() {
            val team = team()
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            assertTrue(service.updateTeam(TEAM_ID, TeamUpdateRequest(leadAgentId = MEMBER_B)))

            assertEquals(MEMBER_B, team.leadAgentId)
        }

        @Test
        fun `null fields leave the stored values alone`() {
            val team = team(name = "Research", tenantId = TENANT).apply {
                description = "old description"
                instructions = "old instructions"
                isPublic = 1
            }
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            service.updateTeam(TEAM_ID, TeamUpdateRequest())

            assertEquals("Research", team.name)
            assertEquals("old description", team.description)
            assertEquals("old instructions", team.instructions)
            assertEquals(1, team.isPublic)
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
        fun `delete removes the bindings before the team`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team())

            assertTrue(service.deleteTeam(TEAM_ID))

            val order = inOrder(teamMemberMapper, teamMapper)
            order.verify(teamMemberMapper).deleteByTeamId(TEAM_ID)
            order.verify(teamMapper).deleteById(TEAM_ID)
        }

        @Test
        fun `delete of another tenant's team removes nothing`() {
            `when`(teamMapper.selectById(TEAM_ID)).thenReturn(team(tenantId = TENANT + 1))

            val error = assertThrows<BizException> { service.deleteTeam(TEAM_ID) }

            assertEquals("Team belongs to another tenant", error.message)
            verify(teamMapper, never()).deleteById(anyLong())
            verify(teamMemberMapper, never()).deleteByTeamId(anyLong())
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
        fun `lead and members are named in assembly order from one agent read`() {
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(
                listOf(binding(MEMBER_A, "gathers"), binding(MEMBER_B, "drafts")),
            )

            val response = service.convertToResponse(team())

            assertEquals("Coordinator", response.leadAgentName)
            assertEquals("Coordinator description", response.leadAgentDescription)
            assertEquals(listOf("Researcher" to "gathers", "Writer" to "drafts"), response.memberList.map { it.agentName to it.delegationDescription })
            assertTrue(response.memberList.all { it.agentAvailable && it.agentStatus == 1 })
            verify(agentMapper).selectByIds(listOf(MEMBER_A, MEMBER_B, LEAD))
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
            assertEquals("gathering", response.memberList.first().delegationDescription)
        }

        @Test
        fun `a lead whose agent is gone still names the id it cannot resolve`() {
            agents.remove(LEAD)
            `when`(teamMemberMapper.selectByTeamId(TEAM_ID)).thenReturn(listOf(binding(MEMBER_A)))

            val response = service.convertToResponse(team())

            assertEquals("#$LEAD", response.leadAgentName)
            assertNull(response.leadAgentDescription)
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

    private fun team(
        id: Long = TEAM_ID,
        name: String = "Research",
        tenantId: Long = TENANT,
        leadAgentId: Long = LEAD,
    ): Team = Team().apply {
        this.id = id
        this.name = name
        this.tenantId = tenantId
        this.leadAgentId = leadAgentId
        this.creator = CURRENT_USER
    }

    private fun binding(agentId: Long, delegation: String = "gathering"): TeamMember = TeamMember().apply {
        teamId = TEAM_ID
        memberAgentId = agentId
        delegationDescription = delegation
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
        leadAgentId: Long = LEAD,
        instructions: String? = null,
        members: List<TeamCreateRequest.MemberItem>? = listOf(member(MEMBER_A), member(MEMBER_B)),
        status: Int? = null,
        isPublic: Int? = null,
    ): TeamCreateRequest = TeamCreateRequest(
        name = name,
        description = description,
        leadAgentId = leadAgentId,
        instructions = instructions,
        members = members,
        status = status,
        isPublic = isPublic,
    )

    companion object {
        private const val CURRENT_USER = "admin"
        private const val TENANT = 7L
        private const val TEAM_ID = 42L
        private const val LEAD = 1L
        private const val MEMBER_A = 2L
        private const val MEMBER_B = 3L
        private const val GONE = 99L
    }
}
