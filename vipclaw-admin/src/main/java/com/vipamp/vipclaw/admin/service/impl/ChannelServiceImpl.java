package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest;
import com.vipamp.vipclaw.admin.dto.ChannelResponse;
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Agent;
import com.vipamp.vipclaw.admin.entity.Channel;
import com.vipamp.vipclaw.admin.mapper.ChannelMapper;
import com.vipamp.vipclaw.admin.service.AgentService;
import com.vipamp.vipclaw.admin.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Channel 服务实现类
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelServiceImpl extends ServiceImpl<ChannelMapper, Channel> implements ChannelService {

    private final AgentService agentService;
    
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Override
    public Page<Channel> getChannelPage(String keyword, String type, Integer status, Integer current, Integer size) {
        LambdaQueryWrapper<Channel> wrapper = new LambdaQueryWrapper<>();
        
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w
                    .like(Channel::getName, keyword)
                    .or()
                    .like(Channel::getDescription, keyword)
            );
        }
        
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Channel::getType, type);
        }

        if (status != null) {
            wrapper.eq(Channel::getStatus, status);
        }

        // 强制校验 active 字段
        wrapper.eq(Channel::getActive, 1);
        wrapper.orderByDesc(Channel::getCreateTime);
        
        return page(new Page<>(current, size), wrapper);
    }

    @Override
    public Channel getChannelById(Long id) {
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createChannel(ChannelCreateRequest request) {
        try {
            Channel channel = new Channel();
            channel.setName(request.getName());
            channel.setType(request.getType());
            channel.setAgentId(request.getAgentId());
            channel.setWebhookUrl(request.getWebhookUrl());
            channel.setToken(request.getToken());
            channel.setEncodingAesKey(request.getEncodingAesKey());
            channel.setAppId(request.getAppId());
            channel.setAppSecret(request.getAppSecret());
            channel.setDescription(request.getDescription());
            channel.setStatus(request.getStatus() != null ? request.getStatus() : 1);
            
            // 生成唯一的回调标识
            String callbackKey = generateCallbackKey(request.getType());
            channel.setCallbackKey(callbackKey);
            
            return save(channel);
        } catch (Exception e) {
            log.error("创建 Channel 失败", e);
            throw new RuntimeException("创建 Channel 失败：" + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateChannel(Long id, ChannelUpdateRequest request) {
        try {
            Channel channel = getById(id);
            if (channel == null) {
                throw new RuntimeException("Channel 不存在");
            }
            
            if (request.getName() != null) {
                channel.setName(request.getName());
            }
            if (request.getType() != null) {
                channel.setType(request.getType());
            }
            if (request.getAgentId() != null) {
                channel.setAgentId(request.getAgentId());
            }
            if (request.getWebhookUrl() != null) {
                channel.setWebhookUrl(request.getWebhookUrl());
            }
            if (request.getToken() != null) {
                channel.setToken(request.getToken());
            }
            if (request.getEncodingAesKey() != null) {
                channel.setEncodingAesKey(request.getEncodingAesKey());
            }
            if (request.getAppId() != null) {
                channel.setAppId(request.getAppId());
            }
            if (request.getAppSecret() != null) {
                channel.setAppSecret(request.getAppSecret());
            }
            if (request.getDescription() != null) {
                channel.setDescription(request.getDescription());
            }
            if (request.getStatus() != null) {
                channel.setStatus(request.getStatus());
            }
            
            return updateById(channel);
        } catch (Exception e) {
            log.error("更新 Channel 失败", e);
            throw new RuntimeException("更新 Channel 失败：" + e.getMessage());
        }
    }

    @Override
    public boolean toggleChannelStatus(Long id, Integer status) {
        Channel channel = getById(id);
        if (channel == null) {
            throw new RuntimeException("Channel 不存在");
        }
        channel.setStatus(status);
        return updateById(channel);
    }

    @Override
    public boolean deleteChannel(Long id) {
        return removeById(id);
    }

    @Override
    public Channel getByCallbackKey(String callbackKey) {
        LambdaQueryWrapper<Channel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Channel::getCallbackKey, callbackKey);
        wrapper.eq(Channel::getActive, 1);
        return getOne(wrapper);
    }

    @Override
    public ChannelResponse convertToResponse(Channel channel) {
        if (channel == null) {
            return null;
        }
        
        ChannelResponse response = ChannelResponse.fromEntity(channel);
        
        // 查询智能体名称
        if (channel.getAgentId() != null) {
            Agent agent = agentService.getById(channel.getAgentId());
            if (agent != null) {
                response.setAgentName(agent.getName());
            }
        }
        
        // 生成回调 URL
        if (channel.getCallbackKey() != null) {
            response.setCallbackUrl(baseUrl + "/api/channel/callback/" + channel.getCallbackKey());
        }
        
        return response;
    }
    
    /**
     * 生成唯一的回调标识
     */
    private String generateCallbackKey(String type) {
        String prefix = type != null ? type.toLowerCase() : "ch";
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return prefix + "-" + uuid;
    }
}