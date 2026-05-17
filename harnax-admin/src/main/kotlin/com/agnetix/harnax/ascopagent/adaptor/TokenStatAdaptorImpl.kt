package com.agnetix.harnax.ascopagent.adaptor

import com.agnetix.harnax.admin.entity.TokenStats
import com.agnetix.harnax.admin.mapper.TokenStatsMapper
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * TokenStatAdaptor Implementation
 * Saves token consumption statistics to database
 */
@Component
class TokenStatAdaptorImpl(
    private val tokenStatsMapper: TokenStatsMapper,
) : TokenStatAdaptor {

    private val log = LoggerFactory.getLogger(TokenStatAdaptorImpl::class.java)

    override fun saveTokenStat(tokenStat: TokenStat) {
        try {
            // Convert TokenStat to TokenStats entity
            val tokenStats = convertToEntity(tokenStat)

            // Save to database
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
     * Convert TokenStat to TokenStats entity
     */
    private fun convertToEntity(tokenStat: TokenStat): TokenStats {
        val tokenStats = TokenStats()
        tokenStats.agentId = tokenStat.agentId
        tokenStats.chatModelId = tokenStat.modelId
        tokenStats.sessionId = tokenStat.sessionId
        tokenStats.inputToken = tokenStat.inputToken.toLong()
        tokenStats.outputToken = tokenStat.outputToken.toLong()
        tokenStats.totalToken = tokenStat.totalToken.toLong()

        // Fee temporarily set to 0, can be calculated based on model billing rules later
        tokenStats.fee = BigDecimal.ZERO

        // Convert timestamp to LocalDateTime
        val dateTime = Instant.ofEpochMilli(tokenStat.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        tokenStats.ts = dateTime

        return tokenStats
    }
}
