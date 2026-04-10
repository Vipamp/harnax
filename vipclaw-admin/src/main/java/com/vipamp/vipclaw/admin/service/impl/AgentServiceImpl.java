package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vipamp.vipclaw.admin.dto.AgentCreateRequest;
import com.vipamp.vipclaw.admin.dto.AgentUpdateRequest;
import com.vipamp.vipclaw.admin.dto.AgentResponse;
import com.vipamp.vipclaw.admin.entity.Agent;
import com.vipamp.vipclaw.admin.entity.McpServer;
import com.vipamp.vipclaw.admin.entity.Skill;
import com.vipamp.vipclaw.admin.entity.SkillRepository;
import com.vipamp.vipclaw.admin.mapper.AgentMapper;
import com.vipamp.vipclaw.admin.mapper.SessionMapper;
import com.vipamp.vipclaw.admin.service.AgentService;
import com.vipamp.vipclaw.admin.service.McpServerService;
import com.vipamp.vipclaw.admin.service.SkillRepositoryService;
import com.vipamp.vipclaw.admin.service.SkillService;
import com.vipamp.vipclaw.admin.util.JwtUtil;
import com.vipamp.vipclaw.admin.util.UserContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能体服务实现类
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Agent> implements AgentService {

    private final McpServerService mcpServerService;
    private final SkillRepositoryService skillRepositoryService;
    private final SkillService skillService;
    private final com.vipamp.vipclaw.admin.service.ModelService modelService;
    private final SessionMapper sessionMapper;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Page<Agent> getAgentPage(String name, Integer status, Integer current, Integer size) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        
        // 获取当前用户
        String currentUsername = UserContextUtil.getCurrentUsername(jwtUtil);
        
        // 权限过滤：只查询公开的或自己创建的
        wrapper.and(w -> w
                .eq(Agent::getIsPublic, 1)
                .or()
                .eq(Agent::getCreator, currentUsername)
        );
        
        if (name != null && !name.isEmpty()) {
            wrapper.like(Agent::getName, name);
        }
        
        if (status != null) {
            wrapper.eq(Agent::getStatus, status);
        }

        // 强制校验 active 字段
        wrapper.eq(Agent::getActive, 1);
        wrapper.orderByDesc(Agent::getCreateTime);
        
        return page(new Page<>(current, size), wrapper);
    }

    @Override
    public Agent getAgentById(Long id) {
        return getById(id);
    }
    
    /**
     * 将 Agent 实体转换为响应 DTO（包含完整的技能和 MCP 信息）
     */
    public AgentResponse convertToResponse(Agent agent) {
        if (agent == null) {
            return null;
        }
        
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setDescription(agent.getDescription());
        response.setSystemPrompt(agent.getSystemPrompt());
        response.setModelId(agent.getModelId());
        
        // 查询模型名称和价格
        if (agent.getModelId() != null) {
            com.vipamp.vipclaw.admin.entity.Model model = modelService.getById(agent.getModelId());
            if (model != null) {
                response.setModelName(model.getModelName());
                response.setModelPrice(model.getPrice());
            }
        }
        
        response.setOwner(agent.getOwner());
        response.setStatus(agent.getStatus());
        response.setIsPublic(agent.getIsPublic());
        response.setCreator(agent.getCreator());
        response.setCreateTime(agent.getCreateTime());
        response.setUpdateTime(agent.getUpdateTime());
        
        // 查询关联会话列表
        List<com.vipamp.vipclaw.admin.entity.Session> sessions = sessionMapper.selectList(
            new LambdaQueryWrapper<com.vipamp.vipclaw.admin.entity.Session>()
                .eq(com.vipamp.vipclaw.admin.entity.Session::getAgentId, agent.getId())
                .eq(com.vipamp.vipclaw.admin.entity.Session::getActive, 1)
                .orderByDesc(com.vipamp.vipclaw.admin.entity.Session::getCreateTime)
                .last("LIMIT 10")
        );
        
        // 转换为 SessionItem 列表
        List<com.vipamp.vipclaw.admin.dto.AgentResponse.SessionItem> sessionItems = sessions.stream()
            .map(session -> {
                com.vipamp.vipclaw.admin.dto.AgentResponse.SessionItem item = 
                    new com.vipamp.vipclaw.admin.dto.AgentResponse.SessionItem();
                item.setId(session.getId());
                item.setTitle(session.getTitle());
                item.setSessionDescription(session.getSessionDescription());
                item.setSessionId(session.getSessionId());
                return item;
            })
            .collect(java.util.stream.Collectors.toList());
        
        response.setSessionList(sessionItems);
        response.setSessionCount(sessionItems.size());
        
        // 解析 MCP 列表 (JSON 格式)
        if (agent.getMcpList() != null && !agent.getMcpList().isEmpty()) {
            try {
                // 先反序列化为 Map 获取 ID 和 enableSkip
                List<Map<String, Object>> mcpConfigs = objectMapper.readValue(
                    agent.getMcpList(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}
                );
                
                // 从数据库查询完整的 MCP 信息
                List<AgentResponse.McpItem> mcpItems = new ArrayList<>();
                for (Map<String, Object> config : mcpConfigs) {
                    Long mcpId = ((Number) config.get("id")).longValue();
                    String enableSkip = (String) config.get("enable_skip");
                    
                    McpServer fullMcp = mcpServerService.getById(mcpId);
                    if (fullMcp != null) {
                        AgentResponse.McpItem item = new AgentResponse.McpItem();
                        item.setMcpId(fullMcp.getId());
                        item.setMcpName(fullMcp.getName());
                        item.setMcpDescription(fullMcp.getDescription());
                        item.setEnableSkip(enableSkip);
                        mcpItems.add(item);
                    }
                }
                response.setMcpList(mcpItems);
            } catch (Exception e) {
                log.warn("解析 MCP 列表失败", e);
                response.setMcpList(new ArrayList<>());
            }
        }
        
        // 解析技能列表（逗号分隔的字符串）
        if (agent.getSkillList() != null && !agent.getSkillList().isEmpty()) {
            try {
                String[] skillIds = agent.getSkillList().split(",");
                List<AgentResponse.SkillItem> skillItems = new ArrayList<>();
                
                for (String skillIdStr : skillIds) {
                    try {
                        Long skillId = Long.parseLong(skillIdStr.trim());
                        // 从数据库查询完整的技能信息
                        Skill skill = skillService.getById(skillId);
                        if (skill != null) {
                            AgentResponse.SkillItem item = new AgentResponse.SkillItem();
                            item.setSkillId(skill.getId());
                            item.setSkillName(skill.getName());
                            item.setSkillDescription(skill.getSkillmd());
                            
                            // 查询技能仓库信息
                            SkillRepository repository = skillRepositoryService.getById(skill.getRepositoryId());
                            if (repository != null) {
                                item.setRepositoryId(repository.getId());
                                item.setRepositoryName(repository.getName());
                            }
                            
                            skillItems.add(item);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("无效的技能 ID: {}", skillIdStr);
                    }
                }
                
                response.setSkillList(skillItems);
            } catch (Exception e) {
                log.warn("解析技能列表失败", e);
                response.setSkillList(new ArrayList<>());
            }
        }
        
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createAgent(AgentCreateRequest request) {
        try {
            Agent agent = new Agent();
            agent.setName(request.getName());
            agent.setDescription(request.getDescription());
            agent.setSystemPrompt(request.getSystemPrompt());
            agent.setModelId(request.getModelId());
            agent.setOwner(request.getOwner());
            agent.setStatus(request.getStatus() != null ? request.getStatus() : 1);
            
            // 设置创建人
            String currentUsername = UserContextUtil.getCurrentUsername(jwtUtil);
            agent.setCreator(currentUsername);
            
            // 默认不公开
            if (agent.getIsPublic() == null) {
                agent.setIsPublic(0);
            }
            
            // 转换 MCP 列表为 JSON 存储
            // 格式: [{"id":1, "enable_skip":"true"},{"id":2, "enable_skip":"false"}]
            if (request.getMcpList() != null && !request.getMcpList().isEmpty()) {
                try {
                    agent.setMcpList(objectMapper.writeValueAsString(request.getMcpList()));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("MCP 列表 JSON 序列化失败", e);
                }
            }
            
            // 技能列表直接存储为字符串格式 "1,2,3"
            if (request.getSkillList() != null && !request.getSkillList().isEmpty()) {
                agent.setSkillList(request.getSkillList());
            }
            
            return save(agent);
        } catch (Exception e) {
            log.error("创建智能体失败", e);
            throw new RuntimeException("创建智能体失败：" + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateAgent(Long id, AgentUpdateRequest request) {
        try {
            Agent agent = getById(id);
            if (agent == null) {
                throw new RuntimeException("智能体不存在");
            }
            
            if (request.getName() != null) {
                agent.setName(request.getName());
            }
            if (request.getDescription() != null) {
                agent.setDescription(request.getDescription());
            }
            if (request.getSystemPrompt() != null) {
                agent.setSystemPrompt(request.getSystemPrompt());
            }
            if (request.getModelId() != null) {
                agent.setModelId(request.getModelId());
            }
            if (request.getOwner() != null) {
                agent.setOwner(request.getOwner());
            }
            if (request.getStatus() != null) {
                agent.setStatus(request.getStatus());
            }
            if (request.getIsPublic() != null) {
                agent.setIsPublic(request.getIsPublic());
            }

            // 更新 MCP 列表
            if (request.getMcpList() != null) {
                // 允许清空 MCP 列表
                if (request.getMcpList().isEmpty()) {
                    agent.setMcpList(null);
                } else {
                    // 直接存储 JSON 格式：[{"id":1, "enable_skip":"true"},{"id":2, "enable_skip":"false"}]
                    try {
                        agent.setMcpList(objectMapper.writeValueAsString(request.getMcpList()));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("MCP 列表 JSON 序列化失败", e);
                    }
                }
            }
            // 如果 request.getMcpList() == null，保持原有值不变
                        
            // 更新技能列表
            if (request.getSkillList() != null) {
                // skillList 是字符串格式 "1,2,3" 或空字符串 ""
                agent.setSkillList(request.getSkillList().trim().isEmpty() ? null : request.getSkillList());
            }
            // 如果 request.getSkillList() == null，保持原有值不变
            
            return updateById(agent);
        } catch (Exception e) {
            log.error("更新智能体失败", e);
            throw new RuntimeException("更新智能体失败：" + e.getMessage());
        }
    }

    @Override
    public boolean toggleAgentStatus(Long id, Integer status) {
        Agent agent = getById(id);
        if (agent == null) {
            throw new RuntimeException("智能体不存在");
        }
        agent.setStatus(status);
        return updateById(agent);
    }

    @Override
    public boolean deleteAgent(Long id) {
        return removeById(id);
    }
}
