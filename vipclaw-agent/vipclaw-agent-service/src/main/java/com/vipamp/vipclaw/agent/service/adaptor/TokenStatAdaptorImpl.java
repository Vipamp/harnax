package com.vipamp.vipclaw.agent.service.adaptor;

import com.vipamp.vipclaw.agent.adaptor.TokenStatAdaptor;
import com.vipamp.vipclaw.agent.adaptor.token.TokenStat;
import com.vipamp.vipclaw.common.entity.TokenStats;
import com.vipamp.vipclaw.common.mapper.TokenStatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * TokenStatAdaptor 实现类
 * 将 Token 消耗统计信息保存到数据库
 *
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Project: vipclaw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenStatAdaptorImpl implements TokenStatAdaptor {

    private final TokenStatsMapper tokenStatsMapper;

    @Override
    public void saveTokenStat(@NotNull TokenStat tokenStat) {
        try {
            // 将 TokenStat 转换为 TokenStats 实体
            TokenStats tokenStats = convertToEntity(tokenStat);

            // 保存到数据库
            int result = tokenStatsMapper.insert(tokenStats);

            if (result > 0) {
                log.info("Token stat saved successfully: agentId={}, modelId={}, sessionId={}, totalToken={}",
                        tokenStat.getAgentId(), tokenStat.getModelId(), tokenStat.getSessionId(), tokenStat.getTotalToken());
            } else {
                log.warn("Failed to save token stat: agentId={}, modelId={}",
                        tokenStat.getAgentId(), tokenStat.getModelId());
            }
        } catch (Exception e) {
            log.error("Error saving token stat: agentId={}, modelId={}, sessionId={}",
                    tokenStat.getAgentId(), tokenStat.getModelId(), tokenStat.getSessionId(), e);
            throw e;
        }
    }

    /**
     * 将 TokenStat 转换为 TokenStats 实体
     */
    private TokenStats convertToEntity(TokenStat tokenStat) {
        TokenStats tokenStats = new TokenStats();
        tokenStats.setAgentId(tokenStat.getAgentId());
        tokenStats.setChatModelId(tokenStat.getModelId());
        tokenStats.setSessionId(tokenStat.getSessionId());
        tokenStats.setInputToken((long) tokenStat.getInputToken());
        tokenStats.setOutputToken((long) tokenStat.getOutputToken());
        tokenStats.setTotalToken((long) tokenStat.getTotalToken());

        // 费用暂时设为 0，后续可以根据模型计费规则计算
        tokenStats.setFee(BigDecimal.ZERO);

        // 将时间戳转换为 LocalDateTime
        LocalDateTime dateTime = Instant.ofEpochMilli(tokenStat.getTimestamp())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        tokenStats.setTs(dateTime);

        return tokenStats;
    }
}
