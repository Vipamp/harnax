package com.vipamp.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest;
import com.vipamp.vipclaw.admin.dto.ChannelResponse;
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Channel;
import org.springframework.lang.Nullable;

/**
 * Channel 服务接口
 *
 * @author vipamp
 * @since 2026-04-08
 */
public interface ChannelService extends IService<Channel> {

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
    Page<Channel> getChannelPage(@Nullable String keyword, @Nullable String type, 
                                  @Nullable Integer status, Integer current, Integer size);

    /**
     * 获取单个 Channel 详情
     *
     * @param id Channel ID
     * @return Channel 实体
     */
    Channel getChannelById(Long id);

    /**
     * 创建 Channel
     *
     * @param request Channel 创建请求对象
     * @return 创建结果
     */
    boolean createChannel(ChannelCreateRequest request);

    /**
     * 更新 Channel
     *
     * @param id      Channel ID
     * @param request Channel 更新请求对象
     * @return 更新结果
     */
    boolean updateChannel(Long id, ChannelUpdateRequest request);

    /**
     * 切换 Channel 启用状态
     *
     * @param id     Channel ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    boolean toggleChannelStatus(Long id, Integer status);

    /**
     * 删除 Channel
     *
     * @param id Channel ID
     * @return 删除结果
     */
    boolean deleteChannel(Long id);

    /**
     * 根据回调标识查询 Channel
     *
     * @param callbackKey 回调标识
     * @return Channel 实体
     */
    Channel getByCallbackKey(String callbackKey);

    /**
     * 将 Channel 实体转换为响应 DTO
     *
     * @param channel Channel 实体
     * @return 响应 DTO
     */
    ChannelResponse convertToResponse(Channel channel);
}
