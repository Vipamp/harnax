package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpAgentDetailResponse
import com.agnetix.harnax.admin.dto.mp.MpAgentResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.service.ModelProviderService
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.MpSessionMapper
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
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
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(MpAgentService::class.java)

    fun listAgents(userId: Long): List<MpAgentResponse> {
        val agents = agentMapper.selectAgentList(null, 1, "")
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

    fun getAgentDetail(agentId: Long): MpAgentDetailResponse {
        val agent = agentMapper.selectById(agentId)
            ?: throw BizException("Agent not found")
        if (agent.active != 1 || agent.status != 1) {
            throw BizException("Agent is not available")
        }

        val model = modelService.getModel(agent.modelId)
        val modelName = model?.modelName ?: ""
        val modelProvider = model?.let { modelProviderService.getModelProvider(it.providerId) }
        val modelProviderName = modelProvider?.name ?: ""

        val mcpItems = parseMcpList(agent.mcpList)
        val skillItems = parseSkillList(agent.skillList)

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

    private fun parseMcpList(mcpListJson: String): List<MpAgentDetailResponse.McpInfo> {
        if (mcpListJson.isBlank() || mcpListJson == "[]") return emptyList()
        return try {
            val configs: List<Map<String, Any>> = objectMapper.readValue(
                mcpListJson,
                object : TypeReference<List<Map<String, Any>>>() {},
            )
            configs.mapNotNull { config ->
                val mcpId = (config["id"] as? Number)?.toLong() ?: return@mapNotNull null
                val fullMcp = mcpServerService.getMcpServer(mcpId) ?: return@mapNotNull null
                MpAgentDetailResponse.McpInfo(
                    id = fullMcp.id,
                    name = fullMcp.name,
                    description = fullMcp.description,
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse MCP list: {}", e.message)
            emptyList()
        }
    }

    private fun parseSkillList(skillListStr: String): List<MpAgentDetailResponse.SkillInfo> {
        if (skillListStr.isBlank()) return emptyList()
        return try {
            skillListStr.split(",").mapNotNull { skillIdStr ->
                val skillId = skillIdStr.trim().toLongOrNull() ?: return@mapNotNull null
                val skill = skillService.getSkill(skillId) ?: return@mapNotNull null
                MpAgentDetailResponse.SkillInfo(
                    id = skill.id,
                    name = skill.name,
                    description = skill.description,
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse skill list: {}", e.message)
            emptyList()
        }
    }
}
