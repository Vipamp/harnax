package com.vipamp.vipclaw.ascopagent.adaptor

import com.vipamp.vipclaw.admin.entity.TokenStats
import com.vipamp.vipclaw.admin.mapper.TokenStatsMapper
import com.vipamp.vipclaw.agent.adaptor.TokenStatAdaptor
import com.vipamp.vipclaw.agent.adaptor.token.TokenStat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * TokenStatAdaptor 实现类
 * 将 Token 消耗统计信息保存到数据库
 *
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Project: vipclaw
 */
@Component
class TokenStatAdaptorImpl(
    private val tokenStatsMapper: TokenStatsMapper,
) : TokenStatAdaptor {

    private val log = LoggerFactory.getLogger(TokenStatAdaptorImpl::class.java)

    override fun saveTokenStat(tokenStat: TokenStat) {
        try {
            // 将 TokenStat 转换为 TokenStats 实体
            val tokenStats = convertToEntity(tokenStat)

            // 保存到数据库
            val result = tokenStatsMapper.insert(tokenStats)

            if (result > 0) {
                log.info(
                    "Token stat saved successfully: agentId={}, modelId={}, sessionId={}, totalToken={}",
                    tokenStat.agentId,
                    tokenStat.modelId,
                    tokenStat.sessionId,
                    tokenStat.totalToken,
                )
            } else {
                log.warn(
                    "Failed to save token stat: agentId={}, modelId={}",
                    tokenStat.agentId,
                    tokenStat.modelId,
                )
            }
        } catch (e: Exception) {
            log.error(
                "Error saving token stat: agentId={}, modelId={}, sessionId={}",
                tokenStat.agentId,
                tokenStat.modelId,
                tokenStat.sessionId,
                e,
            )
            throw e
        }
    }

    /**
     * 将 TokenStat 转换为 TokenStats 实体
     */
    private fun convertToEntity(tokenStat: TokenStat): TokenStats {
        val tokenStats = TokenStats()
        tokenStats.agentId = tokenStat.agentId
        tokenStats.chatModelId = tokenStat.modelId
        tokenStats.sessionId = tokenStat.sessionId
        tokenStats.inputToken = tokenStat.inputToken.toLong()
        tokenStats.outputToken = tokenStat.outputToken.toLong()
        tokenStats.totalToken = tokenStat.totalToken.toLong()

        // 费用暂时设为 0，后续可以根据模型计费规则计算
        tokenStats.fee = BigDecimal.ZERO

        // 将时间戳转换为 LocalDateTime
        val dateTime = Instant.ofEpochMilli(tokenStat.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        tokenStats.ts = dateTime

        return tokenStats
    }
}
