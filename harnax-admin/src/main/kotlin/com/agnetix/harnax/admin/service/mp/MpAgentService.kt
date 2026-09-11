package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpAgentDetailResponse
import com.agnetix.harnax.admin.dto.mp.MpAgentResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.service.ModelProviderService
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.MpSessionMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MpAgentService(
    private val agentMapper: AgentMapper,
    private val mpSessionMapper: MpSessionMapper,
    private val modelService: ModelService,
    private val modelProviderService: ModelProviderService,
    private val mcpServerService: McpServerService,
    private val skillService: SkillService,
    private val mcpBindingMapper: AgentMcpBindingMapper,
    private val skillBindingMapper: AgentSkillBindingMapper,
    private val jwtUtil: JwtUtil,
) {

    private val log = LoggerFactory.getLogger(MpAgentService::class.java)

    fun listAgents(
        userId: Long,
        tenantId: Long?,
    ): List<MpAgentResponse> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val agents = agentMapper.selectAgentList(null, 1, currentUsername, tenantId)
        return agents.filter { it.active == 1 }.map { agent ->
            val modelName = modelService.getModel(agent.modelId)?.modelName ?: ""
            val sessionCount = mpSessionMapper.countByUserIdAndAgentId(userId, agent.id)
            MpAgentResponse(
                id = agent.id,
                name = agent.name,
                description = agent.description,
                modelName = modelName,
                status = agent.status,
                sessionCount = sessionCount,
            )
        }
    }

    fun getAgentDetail(
        agentId: Long,
        tenantId: Long?,
    ): MpAgentDetailResponse {
        val agent = agentMapper.selectById(agentId)
            ?: throw BizException("Agent not found")
        if (tenantId != null && agent.tenantId != tenantId) {
            // Same wording as a missing agent, so another tenant's ids stay unlisted.
            throw BizException("Agent not found")
        }
        if (agent.active != 1 || agent.status != 1) {
            throw BizException("Agent is not available")
        }

        val model = modelService.getModel(agent.modelId)
        val modelName = model?.modelName ?: ""
        val modelProvider = model?.let { modelProviderService.getModelProvider(it.providerId) }
        val modelProviderName = modelProvider?.name ?: ""

        val mcpItems = loadMcpItems(agentId)
        val skillItems = loadSkillItems(agentId)

        return MpAgentDetailResponse(
            id = agent.id,
            name = agent.name,
            description = agent.description,
            modelName = modelName,
            modelProvider = modelProviderName,
            mcpList = mcpItems,
            skillList = skillItems,
            enableThink = false,
            enableSearch = false,
            enablePlan = false,
        )
    }

    /**
     * Load MCP items from binding table instead of parsing agent.mcpList JSON.
     */
    private fun loadMcpItems(agentId: Long): List<MpAgentDetailResponse.McpInfo> {
        val bindings = mcpBindingMapper.selectByAgentId(agentId)
        return bindings.mapNotNull { binding ->
            val fullMcp = mcpServerService.getMcpServer(binding.mcpId) ?: return@mapNotNull null
            MpAgentDetailResponse.McpInfo(
                id = fullMcp.id,
                name = fullMcp.name,
                description = fullMcp.description,
            )
        }
    }

    /**
     * Load skill items from binding table instead of parsing agent.skillList comma-separated string.
     */
    private fun loadSkillItems(agentId: Long): List<MpAgentDetailResponse.SkillInfo> {
        val bindings = skillBindingMapper.selectByAgentId(agentId)
        return bindings.mapNotNull { binding ->
            val skill = skillService.getSkill(binding.skillId) ?: return@mapNotNull null
            MpAgentDetailResponse.SkillInfo(
                id = skill.id,
                name = skill.name,
                description = skill.description,
            )
        }
    }
}
