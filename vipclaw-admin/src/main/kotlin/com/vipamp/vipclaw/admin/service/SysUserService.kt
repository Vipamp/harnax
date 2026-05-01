package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserResponse
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.common.page.Page

/**
 * 用户服务接口
 *
 * @author vipamp
 * @since 2026-03-05
 */
interface SysUserService {

    /**
     * 分页查询用户列表
     *
     * @param keyword  模糊查询字段
     * @param status   状态筛选字段
     * @param tenantId 租户ID过滤
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    fun page(keyword: String?, status: Int?, tenantId: Long?, pageNum: Int, pageSize: Int): Page<SysUser>

    /**
     * 获取单个用户详情
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    fun getSysUser(id: Long): SysUser?

    /**
     * 创建用户
     *
     * @param request 用户创建请求对象
     * @param isPersonal 是否是个人版
     * @return 创建结果
     */
    fun createUser(request: SysUserCreateRequest, isPersonal: Boolean = false): Boolean

    /**
     * 更新用户
     *
     * @param id      用户 ID
     * @param request 用户更新请求对象
     * @param isPersonal 是否是个人版
     * @return 更新结果
     */
    fun updateUser(id: Long, request: SysUserUpdateRequest, isPersonal: Boolean = false): Boolean

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
    fun getByUsername(username: String): SysUser?

    /**
     * 检查用户名是否存在
     *
     * @param username 用户名
     * @return 是否存在
     */
    fun existsByUsername(username: String): Boolean

    /**
     * 检查手机号是否存在
     *
     * @param phone 手机号
     * @return 是否存在
     */
    fun existsByPhone(phone: String): Boolean

    /**
     * 检查邮箱是否存在
     *
     * @param email 邮箱
     * @return 是否存在
     */
    fun existsByEmail(email: String): Boolean

    /**
     * 将用户实体转换为响应对象
     *
     * @param sysUser 用户实体
     * @return 用户响应对象
     */
    fun convertToResponse(sysUser: SysUser): SysUserResponse
}
