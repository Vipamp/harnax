package com.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipclaw.admin.dto.SysUserCreateRequest;
import com.vipclaw.admin.dto.SysUserUpdateRequest;
import com.vipclaw.admin.entity.SysUser;
import jakarta.annotation.Nullable;

/**
 * 用户服务接口
 *
 * @author vipamp
 * @since 2026-03-05
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 分页查询用户列表
     *
     * @param keyword 模糊查询字段
     * @param status  状态筛选字段
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    Page<SysUser> getUserPage(@Nullable String keyword, @Nullable Integer status, Integer current, Integer size);

    /**
     * 获取单个用户详情
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    SysUser getUserById(Long id);

    /**
     * 创建用户
     *
     * @param request 用户创建请求对象
     * @return 创建结果
     */
    boolean createUser(SysUserCreateRequest request);

    /**
     * 更新用户
     *
     * @param id      用户 ID
     * @param request 用户更新请求对象
     * @return 更新结果
     */
    boolean updateUser(Long id, SysUserUpdateRequest request);

    /**
     * 切换用户启用状态
     *
     * @param id     用户 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    boolean toggleUserStatus(Long id, Integer status);

    /**
     * 删除用户
     *
     * @param id 用户 ID
     * @return 删除结果
     */
    boolean deleteUser(Long id);

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户实体
     */
    SysUser getByUsername(String username);
}
