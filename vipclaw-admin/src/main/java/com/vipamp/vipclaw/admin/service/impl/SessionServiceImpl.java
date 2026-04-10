package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vipamp.vipclaw.admin.dto.SessionCreateRequest;
import com.vipamp.vipclaw.admin.dto.SessionResponse;
import com.vipamp.vipclaw.admin.entity.Agent;
import com.vipamp.vipclaw.admin.entity.McpServer;
import com.vipamp.vipclaw.admin.entity.Session;
import com.vipamp.vipclaw.admin.entity.Skill;
import com.vipamp.vipclaw.admin.entity.SkillRepository;
import com.vipamp.vipclaw.admin.exception.BizException;
import com.vipamp.vipclaw.admin.mapper.SessionMapper;
import com.vipamp.vipclaw.admin.service.AgentService;
import com.vipamp.vipclaw.admin.service.McpServerService;
import com.vipamp.vipclaw.admin.service.SessionService;
import com.vipamp.vipclaw.admin.service.SkillRepositoryService;
import com.vipamp.vipclaw.admin.service.SkillService;
import com.vipamp.vipclaw.admin.util.JwtUtil;
import com.vipamp.vipclaw.admin.util.UserContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话服务实现类
 *
 * @author vipamp
 * @since 2026-03-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session> implements SessionService {

    private final AgentService agentService;
    private final McpServerService mcpServerService;
    private final SkillRepositoryService skillRepositoryService;
    private final SkillService skillService;
    private final com.vipamp.vipclaw.admin.service.ModelService modelService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Page<Session> getSessionPage(String keyword, Integer status, Integer current, Integer size) {
        log.info("分页查询会话列表，current: {}, size: {}, keyword: {}, status: {}", current, size, keyword, status);

        Page<Session> page = new Page<>(current, size);
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();

        // 获取当前用户
        String currentUsername = UserContextUtil.getCurrentUsername(jwtUtil);

        // 权限过滤：只查询公开的或自己创建的
        wrapper.and(w -> w
                .eq(Session::getIsPublic, 1)
                .or()
                .eq(Session::getCreator, currentUsername)
        );

        if (StringUtils.hasText(keyword)) {
            wrapper.like(Session::getTitle, keyword);
        }

        if (status != null) {
            wrapper.eq(Session::getStatus, status);
        }

        // 强制校验 active 字段
        wrapper.eq(Session::getActive, 1);
        wrapper.orderByDesc(Session::getCreateTime);

        return this.page(page, wrapper);
    }

    @Override
    public Session getSessionById(Long id) {
        log.info("查询会话详情，id: {}", id);
        Session session = this.getById(id);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        return session;
    }

    @Override
    public SessionResponse convertToResponse(Session session) {
        if (session == null) {
            return null;
        }

        SessionResponse response = new SessionResponse();
        response.setId(session.getId());
        response.setTitle(session.getTitle());
        response.setSessionDescription(session.getSessionDescription());
        response.setSessionId(session.getSessionId());
        response.setAgentId(session.getAgentId());
        response.setName(session.getName());
        response.setDescription(session.getDescription());
        response.setSystemPrompt(session.getSystemPrompt());
        response.setModelId(session.getModelId());

        // 查询模型名称
        if (session.getModelId() != null) {
            com.vipamp.vipclaw.admin.entity.Model model = modelService.getById(session.getModelId());
            if (model != null) {
                response.setModelName(model.getModelName());
                response.setModelPrice(model.getPrice());
            }
        }

        response.setOwner(session.getOwner());
        response.setStatus(session.getStatus());
        response.setIsPublic(session.getIsPublic());
        response.setCreator(session.getCreator());
        response.setCreateTime(session.getCreateTime());
        response.setUpdateTime(session.getUpdateTime());

        // 解析 MCP 列表 (JSON 格式)
        if (session.getMcpList() != null && !session.getMcpList().isEmpty()) {
            try {
                List<Map<String, Object>> mcpConfigs = objectMapper.readValue(
                    session.getMcpList(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}
                );

                List<SessionResponse.McpItem> mcpItems = new ArrayList<>();
                for (Map<String, Object> config : mcpConfigs) {
                    Long mcpId = ((Number) config.get("id")).longValue();
                    String enableSkip = (String) config.get("enable_skip");

                    McpServer fullMcp = mcpServerService.getById(mcpId);
                    if (fullMcp != null) {
                        SessionResponse.McpItem item = new SessionResponse.McpItem();
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
        if (session.getSkillList() != null && !session.getSkillList().isEmpty()) {
            try {
                String[] skillIds = session.getSkillList().split(",");
                List<SessionResponse.SkillItem> skillItems = new ArrayList<>();

                for (String skillIdStr : skillIds) {
                    try {
                        Long skillId = Long.parseLong(skillIdStr.trim());
                        Skill skill = skillService.getById(skillId);
                        if (skill != null) {
                            SessionResponse.SkillItem item = new SessionResponse.SkillItem();
                            item.setSkillId(skill.getId());
                            item.setSkillName(skill.getName());

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
    public boolean createSession(SessionCreateRequest request) {
        try {
            // 检查会话名称是否重复
            long count = this.count(
                new LambdaQueryWrapper<Session>()
                    .eq(Session::getTitle, request.getTitle())
                    .eq(Session::getActive, 1)
            );
            if (count > 0) {
                throw new BizException("会话名称已存在，请使用其他名称");
            }

            // 根据智能体ID获取智能体信息
            Agent agent = agentService.getById(request.getAgentId());
            if (agent == null) {
                throw new BizException("智能体不存在");
            }

            Session session = new Session();
            session.setTitle(request.getTitle());
            session.setSessionDescription(request.getSessionDescription());
            session.setSessionId(UUID.randomUUID().toString());
            session.setAgentId(request.getAgentId());
            
            // 从智能体复制信息
            session.setName(agent.getName());
            session.setDescription(agent.getDescription());
            session.setSystemPrompt(agent.getSystemPrompt());
            session.setModelId(agent.getModelId());
            session.setMcpList(agent.getMcpList());
            session.setSkillList(agent.getSkillList());
            session.setOwner(agent.getOwner());
            session.setStatus(1);

            // 设置创建人
            String currentUsername = UserContextUtil.getCurrentUsername(jwtUtil);
            session.setCreator(currentUsername);

            // 默认不公开
            session.setIsPublic(0);

            boolean success = this.save(session);
            log.info("会话创建{}，id: {}", success ? "成功" : "失败", session.getId());
            return success;
        } catch (Exception e) {
            log.error("创建会话失败", e);
            throw new RuntimeException("创建会话失败：" + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleSessionStatus(Long id, Integer status) {
        log.info("切换会话状态，id: {}, status: {}", id, status);

        Session session = this.getById(id);
        if (session == null) {
            throw new BizException("会话不存在");
        }

        LambdaUpdateWrapper<Session> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(Session::getStatus, status)
                .eq(Session::getId, id);
        return this.update(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSession(Long id) {
        log.info("删除会话，id: {}", id);

        Session session = this.getById(id);
        if (session == null) {
            throw new BizException("会话不存在");
        }

        return this.removeById(id);
    }
    
    @Override
    public boolean existsByTitle(String title) {
        long count = this.count(
            new LambdaQueryWrapper<Session>()
                .eq(Session::getTitle, title)
                .eq(Session::getActive, 1)
        );
        return count > 0;
    }
}