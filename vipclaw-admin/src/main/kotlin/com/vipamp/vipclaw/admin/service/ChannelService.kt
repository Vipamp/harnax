package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest
import com.vipamp.vipclaw.admin.dto.ChannelResponse
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest
import com.vipamp.vipclaw.admin.entity.Channel

/**
 * Channel 服务接口
 *
 * @author vipamp
 * @since 2026-04-08
 */
interface ChannelService {

    /**
     * 分页查询 Channel 列表
     *
     * @param keyword 模糊查询字段
     * @param type    类型筛选
     * @param status  状态筛选
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    fun getChannelPage(keyword: String?, type: String?, status: Int?, current: Int, size: Int): Page<Channel>

    /**
     * 获取单个 Channel 详情
     *
     * @param id Channel ID
     * @return Channel 实体
     */
    fun getChannelById(id: Long): Channel?

    /**
     * 创建 Channel
     *
     * @param request Channel 创建请求对象
     * @return 创建结果
     */
    fun createChannel(request: ChannelCreateRequest): Boolean

    /**
     * 更新 Channel
     *
     * @param id      Channel ID
     * @param request Channel 更新请求对象
     * @return 更新结果
     */
    fun updateChannel(id: Long, request: ChannelUpdateRequest): Boolean

    /**
     * 切换 Channel 启用状态
     *
     * @param id     Channel ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleChannelStatus(id: Long, status: Int): Boolean

    /**
     * 删除 Channel
     *
     * @param id Channel ID
     * @return 删除结果
     */
    fun deleteChannel(id: Long): Boolean

    /**
     * 根据回调标识查询 Channel
     *
     * @param callbackKey 回调标识
     * @return Channel 实体
     */
    fun getByCallbackKey(callbackKey: String): Channel?

    /**
     * 将 Channel 实体转换为响应 DTO
     *
     * @param channel Channel 实体
     * @return 响应 DTO
     */
    fun convertToResponse(channel: Channel?): ChannelResponse?
}
