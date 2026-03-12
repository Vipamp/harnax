package com.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipclaw.admin.dto.SysUserCreateRequest;
import com.vipclaw.admin.dto.SysUserUpdateRequest;
import com.vipclaw.admin.entity.SysUser;
import com.vipclaw.admin.exception.BizException;
import com.vipclaw.admin.mapper.SysUserMapper;
import com.vipclaw.admin.service.SysUserService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户服务实现类
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Override
    public Page<SysUser> getUserPage(@Nullable String keyword,
                                     @Nullable Integer status,
                                     Integer current,
                                     Integer size) {
        log.info("分页查询用户列表，current: {}, size: {}, keyword: {}, status: {}", current, size, keyword, status);

        Page<SysUser> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();

        // 模糊查询
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
                    .or().like(SysUser::getEmail, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }

        // 强制校验 active 字段
        wrapper.eq(SysUser::getActive, 1);
        wrapper.orderByDesc(SysUser::getUpdateTime);
        return this.page(page, wrapper);
    }

    @Override
    public SysUser getUserById(Long id) {
        log.info("查询用户详情，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getId, id)
                .eq(SysUser::getActive, 1);
        wrapper.last("LIMIT 1");

        SysUser user = this.getOne(wrapper);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createUser(SysUserCreateRequest request) {
        log.info("创建用户，username: {}", request.getUsername());

        // 检查用户名是否存在（需要同时校验 active 字段）
        SysUser existUser = getByUsername(request.getUsername());
        if (existUser != null) {
            throw new BizException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setGender(request.getGender() != null ? request.getGender() : 2);
        user.setStatus(request.getStatus() != null ? request.getStatus() : 1); // 默认启用
        user.setActive(1);  // 默认生效
        user.setAvatar(request.getAvatar());

        boolean success = this.save(user);
        log.info("用户创建{}，userId: {}", success ? "成功" : "失败", user.getId());
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUser(Long id, SysUserUpdateRequest request) {
        log.info("更新用户，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getId, id)
                .eq(SysUser::getActive, 1);
        queryWrapper.last("LIMIT 1");

        SysUser user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        // 如果请求中包含用户名且与当前用户名不同，检查新用户名是否已被使用
        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            SysUser existUser = getByUsername(request.getUsername());
            if (existUser != null) {
                throw new BizException("用户名已存在");
            }
            user.setUsername(request.getUsername());
        }

        // 选择性更新字段
        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }

        boolean success = this.updateById(user);
        log.info("用户更新{}，id: {}", success ? "成功" : "失败", id);
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleUserStatus(Long id, Integer status) {
        log.info("切换用户状态，id: {}, status: {}", id, status);

        // 强制校验 active 字段
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getId, id)
                .eq(SysUser::getActive, 1);
        queryWrapper.last("LIMIT 1");

        SysUser user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SysUser::getStatus, status)
                .eq(SysUser::getId, id)
                .eq(SysUser::getActive, 1);
        return this.update(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteUser(Long id) {
        log.info("删除用户，id: {}", id);

        // 强制校验 active 字段
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getId, id)
                .eq(SysUser::getActive, 1);
        queryWrapper.last("LIMIT 1");

        SysUser user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SysUser::getActive, 0)
                .eq(SysUser::getId, id)
                .eq(SysUser::getActive, 1);
        return this.update(wrapper);
    }

    @Override
    public SysUser getByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username)
                .eq(SysUser::getActive, 1);
        wrapper.last("LIMIT 1");
        return getOne(wrapper);
    }
}
