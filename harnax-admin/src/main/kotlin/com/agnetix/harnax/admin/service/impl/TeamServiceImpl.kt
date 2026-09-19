package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.TeamCreateRequest
import com.agnetix.harnax.admin.dto.TeamResponse
import com.agnetix.harnax.admin.dto.TeamUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.TeamService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.entity.TeamMember
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Team service implementation.
 *
 * A team stores references to existing agents and nothing else: no second set of model, tool, MCP,
 * skill or CLI bindings (design D1/D2). Everything that could make a saved team unresolvable at
 * runtime is rejected here, at save time, because the runtime refuses to guess — a member that has
 * since disappeared must not silently drop out of the delegation list the lead sees.
 */
@Service
class TeamServiceImpl(
    private val jwtUtil: JwtUtil,
    private val teamMapper: TeamMapper,
    private val teamMemberMapper: TeamMemberMapper,
    private val agentMapper: AgentMapper,
    private val sessionMapper: SessionMapper,
) : TeamService {

    private val log = LoggerFactory.getLogger(TeamServiceImpl::class.java)

    override fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Team> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<Team>(safePageNum, safePageSize)
        return Page.fromPageInfo(teamMapper.selectTeamList(name, status, currentUsername ?: "", currentTenantId()))
    }

    override fun getTeam(id: Long): Team? = teamMapper.selectById(id)?.takeIf { it.tenantId == currentTenantId() }

    @Transactional(rollbackFor = [Exception::class])
    override fun createTeam(request: TeamCreateRequest): Boolean {
        log.info("Creating team, name: {}", request.name)
        val tenantId = currentTenantId()
        if (teamMapper.selectByName(request.name!!, tenantId) != null) {
            throw BizException("Team name already exists")
        }

        val members = request.members.orEmpty()
        val lead = requireUsableAgent(request.leadAgentId!!, "Lead")
        validateMembers(members, lead.id)

        val team = Team().apply {
            this.name = request.name!!
            this.description = request.description ?: ""
            this.leadAgentId = lead.id
            this.instructions = request.instructions ?: ""
            this.status = request.status ?: 1
            this.isPublic = request.isPublic ?: 0
            this.tenantId = tenantId
            this.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
            this.active = 1
        }
        val success = teamMapper.insert(team) > 0
        if (success) {
            saveMembers(team.id, members)
        }
        log.info("Team creation {}, teamId: {}", if (success) "successful" else "failed", team.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateTeam(id: Long, request: TeamUpdateRequest): Boolean {
        log.info("Updating team, id: {}", id)
        val team = teamMapper.selectById(id) ?: throw BizException("Team not found")
        requireSameTenant(team.tenantId)

        if (request.name != null && request.name != team.name) {
            if (teamMapper.selectByName(request.name!!, team.tenantId) != null) {
                throw BizException("Team name already exists")
            }
            team.name = request.name!!
        }
        request.description?.let { team.description = it }
        request.instructions?.let { team.instructions = it }
        request.isPublic?.let { team.isPublic = it }

        val leadAgentId = request.leadAgentId ?: team.leadAgentId
        val members = request.members ?: teamMemberMapper.selectByTeamId(id).map {
            TeamCreateRequest.MemberItem(agentId = it.memberAgentId, delegationDescription = it.delegationDescription)
        }
        val lead = requireUsableAgent(leadAgentId, "Lead")
        validateMembers(members, lead.id)
        team.leadAgentId = lead.id

        val success = teamMapper.updateById(team) > 0
        if (request.members != null) {
            saveMembers(id, members)
        }
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleTeamStatus(id: Long, status: Int): Boolean {
        val team = teamMapper.selectById(id) ?: throw BizException("Team not found")
        requireSameTenant(team.tenantId)
        return teamMapper.updateStatus(id, status) > 0
    }

    /**
     * Logical delete. Existing team sessions keep their `team_id` and then fail at the next resolve
     * with "Team not found" rather than falling back to the lead's plain agent configuration —
     * design section 3.3 requires an explicit refusal over a silent downgrade.
     */
    @Transactional(rollbackFor = [Exception::class])
    override fun deleteTeam(id: Long): Boolean {
        val team = teamMapper.selectById(id) ?: throw BizException("Team not found")
        requireSameTenant(team.tenantId)
        teamMemberMapper.deleteByTeamId(id)
        return teamMapper.deleteById(id) > 0
    }

    /**
     * A member whose agent row has since been deleted stays in the list, flagged unavailable: a silently
     * shorter list is what lets an operator believe the team is still intact.
     */
    override fun convertToResponse(team: Team): TeamResponse {
        val bindings = teamMemberMapper.selectByTeamId(team.id)
        val agents = agentsOf((bindings.map { it.memberAgentId } + team.leadAgentId).distinct())
        val lead = agents[team.leadAgentId]
        return TeamResponse.fromEntity(team).copy(
            leadAgentName = lead?.name ?: "#${team.leadAgentId}",
            leadAgentDescription = lead?.description,
            memberList = bindings.map { binding ->
                val agent = agents[binding.memberAgentId]
                TeamResponse.MemberItem(
                    agentId = binding.memberAgentId,
                    agentName = agent?.name ?: "#${binding.memberAgentId}",
                    agentDescription = agent?.description,
                    delegationDescription = binding.delegationDescription,
                    agentStatus = agent?.status,
                    agentAvailable = agent != null,
                )
            },
        )
    }

    override fun listRelatedSessions(id: Long): List<RelatedSessionInfo> {
        val team = teamMapper.selectById(id) ?: throw BizException("Team not found")
        requireSameTenant(team.tenantId)
        return sessionMapper.selectByTeamId(id)
            .filter { it.sessionId.isNotBlank() }
            .map {
                RelatedSessionInfo(
                    sessionId = it.sessionId,
                    sourceType = "session",
                    sourceName = it.title.ifBlank { it.sessionId },
                )
            }
            .distinctBy { it.sessionId }
    }

    /** One batched read per page or detail call instead of one query per member. */
    private fun agentsOf(agentIds: List<Long>): Map<Long, Agent> = if (agentIds.isEmpty()) emptyMap() else agentMapper.selectByIds(agentIds).associateBy { it.id }

    /**
     * Existence, tenant, enablement and visibility of one referenced agent.
     *
     * Visibility is the caller's own read rule (`is_public` or their own), not a team-level grant:
     * seeing a team must not hand out its private members (design section 3.3).
     */
    private fun requireUsableAgent(agentId: Long, role: String): Agent {
        val agent = agentMapper.selectById(agentId) ?: throw BizException("$role agent not found: $agentId")
        if (agent.tenantId != currentTenantId()) {
            throw BizException("$role agent belongs to another tenant: $agentId")
        }
        if (agent.status != 1) {
            throw BizException("$role agent is disabled: ${agent.name}")
        }
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        if (agent.isPublic != 1 && agent.creator != currentUsername) {
            throw BizException("$role agent is not available to the current user: ${agent.name}")
        }
        return agent
    }

    private fun validateMembers(members: List<TeamCreateRequest.MemberItem>, leadAgentId: Long) {
        if (members.isEmpty()) {
            throw BizException("A team needs at least one member")
        }
        val agentIds = members.mapNotNull { it.agentId }
        if (agentIds.size != members.size) {
            throw BizException("Member agent ID cannot be empty")
        }
        if (agentIds.distinct().size != agentIds.size) {
            throw BizException("Duplicate members in the team")
        }
        if (agentIds.contains(leadAgentId)) {
            throw BizException("The lead agent cannot also be a member of the same team")
        }
        agentIds.forEach { requireUsableAgent(it, "Member") }
    }

    private fun saveMembers(teamId: Long, members: List<TeamCreateRequest.MemberItem>) {
        teamMemberMapper.deleteByTeamId(teamId)
        if (members.isEmpty()) return
        val agents = agentsOf(members.mapNotNull { it.agentId })
        val now = LocalDateTime.now()
        // Insertion order is the operator's assembly order, and selectByTeamId reads it back by id.
        val rows = members.map { item ->
            TeamMember().apply {
                this.teamId = teamId
                this.memberAgentId = item.agentId!!
                // The agent's own description is the starting role text, editable per team and never
                // written back to the agent (design section 4.1).
                this.delegationDescription = item.delegationDescription?.takeIf { it.isNotBlank() }
                    ?: agents[item.agentId]?.description ?: ""
                this.createTime = now
                this.updateTime = now
            }
        }
        teamMemberMapper.batchInsert(rows)
    }

    private fun currentTenantId(): Long = TenantContext.getTenantId() ?: 1

    private fun requireSameTenant(resourceTenantId: Long) {
        val currentTenantId = TenantContext.getTenantId() ?: return
        if (resourceTenantId != currentTenantId) {
            throw BizException("Team belongs to another tenant")
        }
    }
}
