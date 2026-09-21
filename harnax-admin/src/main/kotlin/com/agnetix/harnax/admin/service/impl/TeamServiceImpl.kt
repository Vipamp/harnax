package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.TeamCreateRequest
import com.agnetix.harnax.admin.dto.TeamResponse
import com.agnetix.harnax.admin.dto.TeamUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.TeamService
import com.agnetix.harnax.admin.skill.SkillBindingResolver
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.entity.TeamMember
import com.agnetix.harnax.entity.TeamSkillBinding
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Team service implementation.
 *
 * A team owns its lead — prompt, model and skills — and references existing agents only as members
 * (design D1/D2). Everything that could make a saved team unresolvable at runtime is rejected here, at
 * save time, because the runtime refuses to guess: a member that has since disappeared must not silently
 * drop out of the delegation list, and a lead whose model cannot be resolved has no fallback at all.
 */
@Service
class TeamServiceImpl(
    private val jwtUtil: JwtUtil,
    private val teamMapper: TeamMapper,
    private val teamMemberMapper: TeamMemberMapper,
    private val teamSkillBindingMapper: TeamSkillBindingMapper,
    private val agentMapper: AgentMapper,
    private val modelMapper: ModelMapper,
    private val sessionMapper: SessionMapper,
    private val skillMapper: SkillMapper,
    private val skillRepositoryService: SkillRepositoryService,
    private val skillBindingResolver: SkillBindingResolver,
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
        requireUsableModel(request.modelId!!)
        validateMembers(members)
        val skills = skillBindingResolver.resolveBindable(request.skillIds.orEmpty())

        val team = Team().apply {
            this.name = request.name!!
            this.description = request.description ?: ""
            this.systemPrompt = request.systemPrompt!!
            this.modelId = request.modelId!!
            this.status = request.status ?: 1
            this.isPublic = request.isPublic ?: 0
            this.tenantId = tenantId
            this.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
            this.active = 1
        }
        val success = teamMapper.insert(team) > 0
        if (success) {
            saveMembers(team.id, members)
            saveSkills(team.id, skills)
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
        request.systemPrompt?.let { team.systemPrompt = it }
        request.isPublic?.let { team.isPublic = it }
        request.modelId?.let {
            requireUsableModel(it)
            team.modelId = it
        }

        val members = request.members ?: teamMemberMapper.selectByTeamId(id).map {
            TeamCreateRequest.MemberItem(agentId = it.memberAgentId, delegationDescription = it.delegationDescription)
        }
        validateMembers(members)
        // Null means "leave the lead's skills alone"; an empty list means "give it none". Only a
        // resolved set may be written, so the guard runs before the team row is touched.
        val skills = request.skillIds?.let { skillBindingResolver.resolveBindable(it) }

        val success = teamMapper.updateById(team) > 0
        if (request.members != null) {
            saveMembers(id, members)
        }
        skills?.let { saveSkills(id, it) }
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
     * with "Team not found" rather than falling back to some other configuration — design section 3.3
     * requires an explicit refusal over a silent downgrade.
     */
    @Transactional(rollbackFor = [Exception::class])
    override fun deleteTeam(id: Long): Boolean {
        val team = teamMapper.selectById(id) ?: throw BizException("Team not found")
        requireSameTenant(team.tenantId)
        teamMemberMapper.deleteByTeamId(id)
        teamSkillBindingMapper.deleteByTeamId(id)
        return teamMapper.deleteById(id) > 0
    }

    /**
     * A member whose agent row has since been deleted stays in the list, flagged unavailable: a silently
     * shorter list is what lets an operator believe the team is still intact. The same holds for a skill
     * the lead still binds.
     */
    override fun convertToResponse(team: Team): TeamResponse {
        val bindings = teamMemberMapper.selectByTeamId(team.id)
        val agents = agentsOf(bindings.map { it.memberAgentId }.distinct())
        val skillBindings = teamSkillBindingMapper.selectByTeamId(team.id)
        val skills = skillsOf(skillBindings.map { it.skillId })
        return TeamResponse.fromEntity(team).copy(
            modelName = modelMapper.selectById(team.modelId)?.modelName,
            skillList = skillBindings.map { binding ->
                val skill = skills[binding.skillId]
                TeamResponse.SkillItem(
                    skillId = binding.skillId,
                    skillName = skill?.name ?: "#${binding.skillId}",
                    skillDescription = skill?.description,
                    repositoryId = skill?.repositoryId,
                    repositoryName = skill?.repositoryId?.let { skillRepositoryService.getSkillRepository(it)?.name },
                    skillAvailable = skill != null,
                )
            },
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

    private fun skillsOf(skillIds: List<Long>): Map<Long, Skill> = if (skillIds.isEmpty()) emptyMap() else skillMapper.selectByIds(skillIds).associateBy { it.id }

    /**
     * Whether the lead can actually run on this model.
     *
     * Stricter than the agent side, which never looked: delivery has no fallback for a lead whose model
     * cannot be used, so what the guard refuses here is exactly what would otherwise become a team that
     * starts and then fails on its first message. Visibility follows the model picker's own rule
     * (`is_public` or the caller's own), not tenancy — a shared public model is pickable.
     */
    private fun requireUsableModel(modelId: Long) {
        val model = modelMapper.selectById(modelId) ?: throw BizException("Lead model not found: $modelId")
        if (model.isPublic != 1 && model.creator != UserContextUtil.getCurrentUsername(jwtUtil)) {
            throw BizException("Lead model is not available to the current user: $modelId")
        }
        if (model.status != 1) {
            throw BizException("Lead model is disabled: ${model.modelName}")
        }
        if (model.modelType != "chat") {
            throw BizException("Lead model is not a chat model: ${model.modelName}")
        }
    }

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

    private fun validateMembers(members: List<TeamCreateRequest.MemberItem>) {
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

    /** The lead's skills, already guard-checked by [SkillBindingResolver]; the whole set is replaced. */
    private fun saveSkills(teamId: Long, skills: List<Skill>) {
        teamSkillBindingMapper.deleteByTeamId(teamId)
        if (skills.isEmpty()) return
        val now = LocalDateTime.now()
        teamSkillBindingMapper.batchInsert(
            skills.map { skill ->
                TeamSkillBinding().apply {
                    this.teamId = teamId
                    this.skillId = skill.id
                    this.createTime = now
                    this.updateTime = now
                }
            },
        )
    }

    private fun currentTenantId(): Long = TenantContext.getTenantId() ?: 1

    private fun requireSameTenant(resourceTenantId: Long) {
        val currentTenantId = TenantContext.getTenantId() ?: return
        if (resourceTenantId != currentTenantId) {
            throw BizException("Team belongs to another tenant")
        }
    }
}
