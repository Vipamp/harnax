package com.vipamp.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipamp.vipclaw.admin.dto.SessionCreateRequest;
import com.vipamp.vipclaw.admin.dto.SessionResponse;
import com.vipamp.vipclaw.admin.entity.Session;
import org.springframework.lang.Nullable;

/**
 * 会话服务接口
 *
 * @author vipamp
 * @since 2026-03-25
 */
public interface SessionService extends IService<Session> {

    /**
     * 分页查询会话列表
     *
     * @param keyword 模糊查询字段
     * @param status  状态筛选字段
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    Page<Session> getSessionPage(@Nullable String keyword, @Nullable Integer status, Integer current, Integer size);

    /**
     * 获取单个会话详情
     *
     * @param id 会话 ID
     * @return 会话实体
     */
    Session getSessionById(Long id);

    /**
     * 创建会话
     *
     * @param request 会话创建请求对象
     * @return 创建结果
     */
    boolean createSession(SessionCreateRequest request);

    /**
     * 切换会话启用状态
     *
     * @param id     会话 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    boolean toggleSessionStatus(Long id, Integer status);

    /**
     * 删除会话
     *
     * @param id 会话 ID
     * @return 删除结果
     */
    boolean deleteSession(Long id);

    /**
     * 将 Session 实体转换为响应 DTO（包含完整的技能和 MCP 信息）
     *
     * @param session 会话实体
     * @return 响应 DTO
     */
    SessionResponse convertToResponse(Session session);
}
