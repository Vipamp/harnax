package com.vipamp.vipclaw.agent.service.adaptor;

import com.vipamp.vipclaw.agent.adaptor.SkillAdaptor;
import com.vipamp.vipclaw.common.entity.Skill;
import com.vipamp.vipclaw.common.mapper.SkillMapper;
import io.agentscope.core.skill.AgentSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SkillAdaptor 实现类
 * 从数据库加载技能并转换为 AgentSkill
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAdaptorImpl implements SkillAdaptor {

    private final SkillMapper skillMapper;

    @Override
    public AgentSkill getSkill(long skillId) {
        if (skillId <= 0) {
            log.warn("Invalid skillId: {}", skillId);
            return null;
        }

        // 查询技能信息
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            log.warn("Skill not found: {}", skillId);
            return null;
        }

        // 转换为 AgentSkill
        return buildAgentSkill(skill);
    }

    /**
     * 构建 AgentSkill
     */
    private AgentSkill buildAgentSkill(Skill skill) {
        try {
            return AgentSkill.builder()
                    .name(skill.getName())
                    .description(skill.getDescription())
                    .build();
        } catch (Exception e) {
            log.error("Failed to build AgentSkill for skill: {}", skill.getId(), e);
            return null;
        }
    }
}
