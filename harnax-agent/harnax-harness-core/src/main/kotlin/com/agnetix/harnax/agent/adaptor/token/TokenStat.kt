package com.agnetix.harnax.agent.adaptor.token

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: TokenStat
 * @Project: harnax
 */
class TokenStat(
    /** Null when no `agent` row stands behind the run — a team's lead. */
    val agentId: Long?,
    /** Tenant owning the run, from `AgentSpec.tenantId`; null when admin delivered none (V50). */
    val tenantId: Long? = null,
    val modelId: Long,
    val sessionId: String,
    val inputToken: Int,
    val outputToken: Int,
    val totalToken: Int,
    val timestamp: Long,
) {
    companion object {
        @JvmStatic
        fun builder() = TokenStatBuilder()
    }
}

class TokenStatBuilder {
    private var agentId: Long? = null
    private var tenantId: Long? = null
    private var modelId: Long = 0L
    private var sessionId: String = ""
    private var inputToken: Int = 0
    private var outputToken: Int = 0
    private var totalToken: Int = 0
    private var timestamp: Long = System.currentTimeMillis()

    fun agentId(agentId: Long?) = apply { this.agentId = agentId }

    /** The tenant the consumption belongs to — set once, from the spec, where [agentId] is set. */
    fun tenantId(tenantId: Long?) = apply { this.tenantId = tenantId }
    fun modelId(modelId: Long) = apply { this.modelId = modelId }
    fun sessionId(sessionId: String) = apply { this.sessionId = sessionId }
    fun inputToken(inputToken: Int) = apply { this.inputToken = inputToken }
    fun outputToken(outputToken: Int) = apply { this.outputToken = outputToken }
    fun totalToken(totalToken: Int) = apply { this.totalToken = totalToken }
    fun timestamp(timestamp: Long) = apply { this.timestamp = timestamp }

    fun build() = TokenStat(
        agentId = agentId,
        tenantId = tenantId,
        modelId = modelId,
        sessionId = sessionId,
        inputToken = inputToken,
        outputToken = outputToken,
        totalToken = totalToken,
        timestamp = timestamp,
    )
}
