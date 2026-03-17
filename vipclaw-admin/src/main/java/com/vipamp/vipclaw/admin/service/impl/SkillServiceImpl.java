package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipamp.vipclaw.admin.dto.SkillCreateRequest;
import com.vipamp.vipclaw.admin.dto.SkillResponse;
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Skill;
import com.vipamp.vipclaw.admin.exception.BizException;
import com.vipamp.vipclaw.admin.mapper.SkillMapper;
import com.vipamp.vipclaw.admin.service.SkillService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 技能服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillServiceImpl extends ServiceImpl<SkillMapper, Skill> implements SkillService {

    @Override
    public Page<Skill> getSkillPage(@Nullable String name,
                                    @Nullable Long repositoryId,
                                    @Nullable Integer status,
                                    Integer current,
                                    Integer size) {
        log.info("分页查询技能列表，current: {}, size: {}, name: {}, repositoryId: {}, status: {}", current, size, name, repositoryId, status);

        Page<Skill> page = new Page<>(current, size);
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();

        // 技能名称模糊查询
        if (StringUtils.hasText(name)) {
            wrapper.like(Skill::getName, name);
        }

        // 仓库ID筛选
        if (repositoryId != null) {
            wrapper.eq(Skill::getRepositoryId, repositoryId);
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(Skill::getStatus, status);
        }

        // 强制校验 active 字段
        wrapper.eq(Skill::getActive, 1);
        wrapper.orderByDesc(Skill::getUpdateTime);
        return this.page(page, wrapper);
    }

    @Override
    public Skill getSkillById(Long id) {
        log.info("查询技能详情，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getId, id)
                .eq(Skill::getActive, 1);
        wrapper.last("LIMIT 1");

        Skill skill = this.getOne(wrapper);
        if (skill == null) {
            throw new BizException("技能不存在");
        }
        return skill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createSkill(SkillCreateRequest request) {
        log.info("创建技能，name: {}", request.getName());

        // 检查技能名称是否存在（需要同时校验 active 字段）
        Skill existSkill = getByNameAndRepo(request.getRepositoryId(), request.getName());
        if (existSkill != null) {
            throw new BizException("技能名称已存在");
        }

        Skill skill = new Skill();
        skill.setName(request.getName());
        skill.setRepositoryId(request.getRepositoryId());
        skill.setDescription(request.getDescription());
        skill.setSkillmd(request.getSkillmd());
        skill.setResources(request.getResources());
        skill.setStatus(request.getStatus() != null ? request.getStatus() : 1); // 默认启用
        skill.setActive(1);  // 默认生效

        boolean success = this.save(skill);
        log.info("技能创建{}，skillId: {}", success ? "成功" : "失败", skill.getId());
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSkill(Long id, SkillUpdateRequest request) {
        log.info("更新技能，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<Skill> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Skill::getId, id)
                .eq(Skill::getActive, 1);
        queryWrapper.last("LIMIT 1");

        Skill skill = this.getOne(queryWrapper);
        if (skill == null) {
            throw new BizException("技能不存在");
        }

        // 如果请求中包含技能名称且与当前技能名称不同，检查新技能名称是否已被使用
        if (request.getName() != null && !request.getName().equals(skill.getName())) {
            Skill existSkill = getByNameAndRepo(skill.getRepositoryId(), request.getName());
            if (existSkill != null) {
                throw new BizException("技能名称已存在");
            }
            skill.setName(request.getName());
        }

        // 选择性更新字段
        if (request.getRepositoryId() != null) {
            skill.setRepositoryId(request.getRepositoryId());
        }
        if (request.getDescription() != null) {
            skill.setDescription(request.getDescription());
        }
        if (request.getSkillmd() != null) {
            skill.setSkillmd(request.getSkillmd());
        }
        if (request.getResources() != null) {
            skill.setResources(request.getResources());
        }
        if (request.getStatus() != null) {
            skill.setStatus(request.getStatus());
        }

        boolean success = this.updateById(skill);
        log.info("技能更新{}，id: {}", success ? "成功" : "失败", id);
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleSkillStatus(Long id, Integer status) {
        log.info("切换技能状态，id: {}, status: {}", id, status);

        // 强制校验 active 字段
        LambdaQueryWrapper<Skill> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Skill::getId, id)
                .eq(Skill::getActive, 1);
        queryWrapper.last("LIMIT 1");

        Skill skill = this.getOne(queryWrapper);
        if (skill == null) {
            throw new BizException("技能不存在");
        }

        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(Skill::getStatus, status)
                .eq(Skill::getId, id)
                .eq(Skill::getActive, 1);
        return this.update(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSkill(Long id) {
        log.info("删除技能，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<Skill> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Skill::getId, id)
                .eq(Skill::getActive, 1);
        queryWrapper.last("LIMIT 1");

        Skill skill = this.getOne(queryWrapper);
        if (skill == null) {
            throw new BizException("技能不存在");
        }

        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(Skill::getActive, 0)
                .eq(Skill::getId, id)
                .eq(Skill::getActive, 1);
        return this.update(wrapper);
    }

    @Override
    public Skill getByNameAndRepo(Long repositoryId, String name) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getName, name)
                .eq(Skill::getRepositoryId, repositoryId)
                .eq(Skill::getActive, 1);
        wrapper.last("LIMIT 1");
        return getOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer batchSaveSkills(Long repositoryId, List<SkillResponse> skills) {
        log.info("批量保存技能，repositoryId: {}, count: {}", repositoryId, skills != null ? skills.size() : 0);

        if (skills == null || skills.isEmpty()) {
            return 0;
        }

        int savedCount = 0;
        for (SkillResponse skillData : skills) {
            try {
                // 检查技能名称是否存在
                Skill existSkill = getByNameAndRepo(repositoryId, skillData.getName());
                if (existSkill != null) {
                    // 如果技能已存在，更新它，status 状态维持不变
                    log.info("技能已存在，执行更新：{}", skillData.getName());
                    LambdaUpdateWrapper<Skill> updateWrapper = new LambdaUpdateWrapper<>();
                    updateWrapper.set(Skill::getDescription, skillData.getDescription())
                            .set(Skill::getSkillmd, skillData.getSkillmd())
                            .set(Skill::getResources, skillData.getResources())
                            .set(Skill::getStatus, existSkill.getStatus()) // status 状态维持不变
                            .eq(Skill::getId, existSkill.getId())
                            .eq(Skill::getActive, 1);
                    this.update(updateWrapper);
                    savedCount++;
                } else {
                    // 如果技能不存在，创建新技能
                    log.info("技能不存在，执行创建：{}", skillData.getName());
                    Skill skill = new Skill();
                    skill.setName(skillData.getName());
                    skill.setRepositoryId(repositoryId);
                    skill.setDescription(skillData.getDescription());
                    skill.setSkillmd(skillData.getSkillmd());
                    skill.setResources(skillData.getResources());
                    skill.setStatus(1);  // 默认启用
                    skill.setActive(1);  // 默认生效
                    this.save(skill);
                    savedCount++;
                }
            } catch (Exception e) {
                log.error("保存技能失败：{}", skillData.getName(), e);
                // 继续处理下一个技能，不中断整个流程
            }
        }

        log.info("批量保存技能完成，成功保存：{} 个", savedCount);
        return savedCount;
    }
}
