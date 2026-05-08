package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.SessionChatUpdateRequest
import com.vipamp.vipclaw.admin.dto.SessionCreateRequest
import com.vipamp.vipclaw.admin.dto.SessionResponse
import com.vipamp.vipclaw.admin.entity.Session
import com.vipamp.vipclaw.ascopagent.dto.SessionConfigResponse

/**
 * 会话服务接口
 *
 * @author vipamp
 * @since 2026-03-25
 */
interface SessionService {

    /**
     * 分页查询会话列表
     *
     * @param keyword  模糊查询字段
     * @param status   状态筛选字段
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    fun page(keyword: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Session>

    /**
     * 获取单个会话详情
     *
     * @param id 会话 ID
     * @return 会话实体
     */
    fun getSession(id: Long): Session?

    /**
     * 创建会话
     *
     * @param request 会话创建请求对象
     * @return 创建结果
     */
    fun createSession(request: SessionCreateRequest): Boolean

    /**
     * 更新会话
     *
     * @param id      会话 ID
     * @param request 会话更新请求对象
     * @return 更新结果
     */
    fun updateSession(id: Long, request: SessionCreateRequest): Boolean

    /**
     * 更新会话配置
     *
     * @param sessionId 会话 ID
     * @param request   会话配置更新请求对象
     */
    fun updateSessionChatConfig(sessionId: String, request: SessionChatUpdateRequest)

    /**
     * 获取会话配置
     *
     * @param sessionId 会话 ID
     * @return 会话配置响应对象
     */
    fun getSessionChatConfig(sessionId: String): SessionConfigResponse

    /**
     * 切换会话启用状态
     *
     * @param id     会话 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleSessionStatus(id: Long, status: Int): Boolean

    /**
     * 删除会话
     *
     * @param id 会话 ID
     * @return 删除结果
     */
    fun deleteSession(id: Long): Boolean

    /**
     * 检查会话名称是否存在
     *
     * @param title 会话名称
     * @return 是否存在
     */
    fun existsByTitle(title: String): Boolean

    /**
     * 将 Session 实体转换为响应 DTO（包含完整的技能和 MCP 信息）
     *
     * @param session 会话实体
     * @return 响应 DTO
     */
    fun convertToResponse(session: Session): SessionResponse
}
