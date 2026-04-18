package com.vipamp.vipclaw.admin.service

import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.IService
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.entity.SysUser

/**
 * 用户服务接口
 *
 * @author vipamp
 * @since 2026-03-05
 */
interface SysUserService : IService<SysUser> {

    /**
     * 分页查询用户列表
     *
     * @param keyword 模糊查询字段
     * @param status  状态筛选字段
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    fun getUserPage(keyword: String?, status: Int?, current: Int, size: Int): Page<SysUser>

    /**
     * 获取单个用户详情
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    fun getUserById(id: Long): SysUser

    /**
     * 创建用户
     *
     * @param request 用户创建请求对象
     * @return 创建结果
     */
    fun createUser(request: SysUserCreateRequest): Boolean

    /**
     * 更新用户
     *
     * @param id      用户 ID
     * @param request 用户更新请求对象
     * @return 更新结果
     */
    fun updateUser(id: Long, request: SysUserUpdateRequest): Boolean

    /**
     * 切换用户启用状态
     *
     * @param id     用户 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleUserStatus(id: Long, status: Int): Boolean

    /**
     * 删除用户
     *
     * @param id 用户 ID
     * @return 删除结果
     */
    fun deleteUser(id: Long): Boolean

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户实体
     */
    fun getByUsername(username: String): SysUser
}
