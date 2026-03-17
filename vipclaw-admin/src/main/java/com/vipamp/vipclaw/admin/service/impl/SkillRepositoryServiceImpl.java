package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipamp.vipclaw.admin.dto.SkillRepositoryCreateRequest;
import com.vipamp.vipclaw.admin.dto.SkillRepositoryUpdateRequest;
import com.vipamp.vipclaw.admin.dto.SyncSkillResponse;
import com.vipamp.vipclaw.admin.entity.SkillRepository;
import com.vipamp.vipclaw.admin.exception.BizException;
import com.vipamp.vipclaw.admin.mapper.SkillRepositoryMapper;
import com.vipamp.vipclaw.admin.service.SkillRepositoryService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能仓库服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRepositoryServiceImpl extends ServiceImpl<SkillRepositoryMapper, SkillRepository> implements SkillRepositoryService {

    @Override
    public Page<SkillRepository> getRepositoryPage(@Nullable String name,
                                                   @Nullable Integer status,
                                                   Integer current,
                                                   Integer size) {
        log.info("分页查询技能仓库列表，current: {}, size: {}, name: {}, status: {}", current, size, name, status);

        Page<SkillRepository> page = new Page<>(current, size);
        LambdaQueryWrapper<SkillRepository> wrapper = new LambdaQueryWrapper<>();

        // 仓库名称模糊查询
        if (StringUtils.hasText(name)) {
            wrapper.like(SkillRepository::getName, name);
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(SkillRepository::getStatus, status);
        }

        // 强制校验 active 字段
        wrapper.eq(SkillRepository::getActive, 1);
        wrapper.orderByDesc(SkillRepository::getStatus)
                .orderByDesc(SkillRepository::getUpdateTime);
        return this.page(page, wrapper);
    }

    @Override
    public List<SkillRepository> getActiveRepositories() {
        LambdaQueryWrapper<SkillRepository> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillRepository::getActive, 1)
                .eq(SkillRepository::getStatus, 1)
                .orderByDesc(SkillRepository::getUpdateTime);
        return this.list(wrapper);
    }

    @Override
    public SkillRepository getRepositoryById(Long id) {
        log.info("查询技能仓库详情，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<SkillRepository> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillRepository::getId, id)
                .eq(SkillRepository::getActive, 1);
        wrapper.last("LIMIT 1");

        SkillRepository repository = this.getOne(wrapper);
        if (repository == null) {
            throw new BizException("技能仓库不存在");
        }
        return repository;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createRepository(SkillRepositoryCreateRequest request) {
        log.info("创建技能仓库，name: {}", request.getName());

        // 检查仓库名称是否存在（需要同时校验 active 字段）
        SkillRepository existRepository = getByName(request.getName());
        if (existRepository != null) {
            throw new BizException("仓库名称已存在");
        }

        SkillRepository repository = new SkillRepository();
        repository.setName(request.getName());
        repository.setUrl(request.getUrl());
        repository.setBranch(request.getBranch());
        repository.setDescription(request.getDescription());
        repository.setStatus(request.getStatus() != null ? request.getStatus() : 1); // 默认启用
        repository.setActive(1);  // 默认生效

        boolean success = this.save(repository);
        log.info("技能仓库创建{}，repositoryId: {}", success ? "成功" : "失败", repository.getId());
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateRepository(Long id, SkillRepositoryUpdateRequest request) {
        log.info("更新技能仓库，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<SkillRepository> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SkillRepository::getId, id)
                .eq(SkillRepository::getActive, 1);
        queryWrapper.last("LIMIT 1");

        SkillRepository repository = this.getOne(queryWrapper);
        if (repository == null) {
            throw new BizException("技能仓库不存在");
        }

        // 如果请求中包含仓库名称且与当前仓库名称不同，检查新仓库名称是否已被使用
        if (request.getName() != null && !request.getName().equals(repository.getName())) {
            SkillRepository existRepository = getByName(request.getName());
            if (existRepository != null) {
                throw new BizException("仓库名称已存在");
            }
            repository.setName(request.getName());
        }

        // 选择性更新字段
        if (request.getUrl() != null) {
            repository.setUrl(request.getUrl());
        }
        if (request.getBranch() != null) {
            repository.setBranch(request.getBranch());
        }
        if (request.getDescription() != null) {
            repository.setDescription(request.getDescription());
        }
        if (request.getStatus() != null) {
            repository.setStatus(request.getStatus());
        }

        boolean success = this.updateById(repository);
        log.info("技能仓库更新{}，id: {}", success ? "成功" : "失败", id);
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleRepositoryStatus(Long id, Integer status) {
        log.info("切换技能仓库状态，id: {}, status: {}", id, status);

        // 强制校验 active 字段
        LambdaQueryWrapper<SkillRepository> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SkillRepository::getId, id)
                .eq(SkillRepository::getActive, 1);
        queryWrapper.last("LIMIT 1");

        SkillRepository repository = this.getOne(queryWrapper);
        if (repository == null) {
            throw new BizException("技能仓库不存在");
        }

        LambdaUpdateWrapper<SkillRepository> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SkillRepository::getStatus, status)
                .eq(SkillRepository::getId, id)
                .eq(SkillRepository::getActive, 1);
        return this.update(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRepository(Long id) {
        log.info("删除技能仓库，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<SkillRepository> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SkillRepository::getId, id)
                .eq(SkillRepository::getActive, 1);
        queryWrapper.last("LIMIT 1");

        SkillRepository repository = this.getOne(queryWrapper);
        if (repository == null) {
            throw new BizException("技能仓库不存在");
        }

        LambdaUpdateWrapper<SkillRepository> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SkillRepository::getActive, 0)
                .eq(SkillRepository::getId, id)
                .eq(SkillRepository::getActive, 1);
        return this.update(wrapper);
    }

    @Override
    public SkillRepository getByName(String name) {
        LambdaQueryWrapper<SkillRepository> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillRepository::getName, name)
                .eq(SkillRepository::getActive, 1);
        wrapper.last("LIMIT 1");
        return getOne(wrapper);
    }

    @Override
    public List<SyncSkillResponse> fetchRemoteSkills(Long repositoryId) {
        log.info("获取远程技能列表，repositoryId: {}", repositoryId);
        
        // TODO: 实现真实的 Git 仓库拉取和 skill.md 解析逻辑
        // 目前返回 Mock 测试数据
        
        List<SyncSkillResponse> mockData = new ArrayList<>();
        
        SyncSkillResponse skill1 = new SyncSkillResponse();
        skill1.setName("Java 编程助手");
        skill1.setDescription("提供 Java 编程相关的技能帮助，包括代码编写、调试、优化等");
        skill1.setSkillmd("# Java 编程助手\n\n我可以帮助你：\n- Java 基础语法\n- Spring 框架\n- 多线程编程\n- JVM 调优");
        skill1.setResources("[]");
        skill1.setExists(false);
        mockData.add(skill1);
        
        SyncSkillResponse skill2 = new SyncSkillResponse();
        skill2.setName("Python 脚本专家");
        skill2.setDescription("Python 脚本编写和问题解答，涵盖数据分析、自动化等领域");
        skill2.setSkillmd("# Python 脚本专家\n\n擅长领域：\n- Python 基础\n- Django/Flask\n- 数据处理\n- 自动化脚本");
        skill2.setResources("[]");
        skill2.setExists(false);
        mockData.add(skill2);
        
        SyncSkillResponse skill3 = new SyncSkillResponse();
        skill3.setName("前端 UI 设计师");
        skill3.setDescription("前端界面设计和样式咨询，精通 React、Vue 等主流框架");
        skill3.setSkillmd("# 前端 UI 设计师\n\n专业技能：\n- React/Vue\n- CSS/Tailwind\n- 响应式设计\n- 用户体验优化");
        skill3.setResources("[]");
        skill3.setExists(true); // 模拟已存在的技能
        mockData.add(skill3);
        
        log.info("Mock 数据返回，共 {} 个技能", mockData.size());
        return mockData;
    }
}
