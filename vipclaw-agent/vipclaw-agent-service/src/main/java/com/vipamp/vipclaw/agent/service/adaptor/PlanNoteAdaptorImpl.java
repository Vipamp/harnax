package com.vipamp.vipclaw.agent.service.adaptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vipamp.vipclaw.agent.adaptor.PlanNote;
import com.vipamp.vipclaw.agent.adaptor.PlanNoteAdaptor;
import com.vipamp.vipclaw.agent.adaptor.PlanSubTask;
import com.vipamp.vipclaw.common.mapper.PlanNoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * PlanNoteAdaptor 实现类
 * 从数据库加载和保存 PlanNote
 *
 * @author vipamp
 * @since 2026-04-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanNoteAdaptorImpl implements PlanNoteAdaptor {

    private final PlanNoteMapper planNoteMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void save(@NotNull PlanNote planNote) {
        try {
            // 转换为实体
            com.vipamp.vipclaw.common.entity.PlanNote entity = convertToEntity(planNote);

            // 保存到数据库
            int result = planNoteMapper.insert(entity);

            if (result > 0) {
                log.info("PlanNote saved successfully: sessionId={}, planId={}, name={}",
                        planNote.getSessionId(), planNote.getPlanId(), planNote.getName());
            } else {
                log.warn("Failed to save PlanNote: sessionId={}, planId={}",
                        planNote.getSessionId(), planNote.getPlanId());
            }
        } catch (Exception e) {
            log.error("Error saving PlanNote: sessionId={}, planId={}",
                    planNote.getSessionId(), planNote.getPlanId(), e);
        }
    }

    @Override
    public PlanNote getPlanNote(String sessionId, String planId) {
        try {
            LambdaQueryWrapper<com.vipamp.vipclaw.common.entity.PlanNote> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(com.vipamp.vipclaw.common.entity.PlanNote::getSessionId, sessionId)
                   .eq(com.vipamp.vipclaw.common.entity.PlanNote::getPlanId, planId);

            com.vipamp.vipclaw.common.entity.PlanNote entity = planNoteMapper.selectOne(wrapper);

            if (entity == null) {
                log.warn("PlanNote not found: sessionId={}, planId={}", sessionId, planId);
                return null;
            }

            return convertToDomain(entity);
        } catch (Exception e) {
            log.error("Error getting PlanNote: sessionId={}, planId={}", sessionId, planId, e);
            return null;
        }
    }

    @Override
    public List<PlanNote> getPlanNotes(String sessionId) {
        try {
            LambdaQueryWrapper<com.vipamp.vipclaw.common.entity.PlanNote> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(com.vipamp.vipclaw.common.entity.PlanNote::getSessionId, sessionId)
                   .orderByDesc(com.vipamp.vipclaw.common.entity.PlanNote::getCreateTime);

            List<com.vipamp.vipclaw.common.entity.PlanNote> entities = planNoteMapper.selectList(wrapper);

            List<PlanNote> planNotes = new ArrayList<>();
            for (com.vipamp.vipclaw.common.entity.PlanNote entity : entities) {
                planNotes.add(convertToDomain(entity));
            }

            return planNotes;
        } catch (Exception e) {
            log.error("Error getting PlanNotes: sessionId={}", sessionId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void deletePlan(String sessionId) {
        try {
            LambdaQueryWrapper<com.vipamp.vipclaw.common.entity.PlanNote> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(com.vipamp.vipclaw.common.entity.PlanNote::getSessionId, sessionId);

            int result = planNoteMapper.delete(wrapper);

            if (result > 0) {
                log.info("PlanNotes deleted successfully: sessionId={}, count={}", sessionId, result);
            } else {
                log.warn("No PlanNotes found to delete: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.error("Error deleting PlanNotes: sessionId={}", sessionId, e);
            throw e;
        }
    }

    /**
     * 将 PlanNote 领域对象转换为实体
     */
    private com.vipamp.vipclaw.common.entity.PlanNote convertToEntity(PlanNote planNote) throws JsonProcessingException {
        com.vipamp.vipclaw.common.entity.PlanNote entity = new com.vipamp.vipclaw.common.entity.PlanNote();
        entity.setSessionId(planNote.getSessionId());
        entity.setPlanId(planNote.getPlanId());
        entity.setName(planNote.getName());
        entity.setDescription(planNote.getDescription());
        entity.setExpectedOutcome(planNote.getExpectedOutcome());
        
        // 将子任务列表转换为 JSON 字符串
        if (planNote.getSubtasks() != null && !planNote.getSubtasks().isEmpty()) {
            entity.setSubtasks(objectMapper.writeValueAsString(planNote.getSubtasks()));
        }
        
        entity.setCreatedAt(planNote.getCreatedAt());
        entity.setFinishedAt(planNote.getFinishedAt());
        entity.setCostTimeseconds(planNote.getCostTimeseconds());

        return entity;
    }

    /**
     * 将实体转换为 PlanNote 领域对象
     */
    private PlanNote convertToDomain(com.vipamp.vipclaw.common.entity.PlanNote entity) {
        List<PlanSubTask> subtasks = null;
        
        if (StringUtils.hasText(entity.getSubtasks())) {
            try {
                subtasks = objectMapper.readValue(
                    entity.getSubtasks(),
                    new TypeReference<List<PlanSubTask>>() {}
                );
            } catch (JsonProcessingException e) {
                log.error("Failed to parse subtasks JSON for planId: {}", entity.getPlanId(), e);
                subtasks = new ArrayList<>();
            }
        }

        return new PlanNote(
                entity.getSessionId(),
                entity.getPlanId(),
                entity.getName(),
                entity.getDescription(),
                entity.getExpectedOutcome(),
                subtasks != null ? new ArrayList<>(subtasks) : new ArrayList<>(),
                entity.getCreatedAt(),
                entity.getFinishedAt(),
                entity.getCostTimeseconds() != null ? entity.getCostTimeseconds() : 0L
        );
    }
}
