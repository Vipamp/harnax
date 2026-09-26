package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.token.TokenStat
import com.agnetix.harnax.entity.TokenStats
import com.agnetix.harnax.mapper.TokenStatsMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import java.time.Instant
import java.time.ZoneId

/**
 * Unit tests for TokenStatAdaptorImpl.
 *
 * This is the only place a run's tenant becomes a column: V50 made every aggregation read
 * `tenant_id = ?`, so dropping the field here does not error — the consumption simply stops showing up
 * in the workspace that paid for it. The tests read the row the mapper was handed rather than stubbing a
 * matcher, because the claim is about the whole written row.
 */
class TokenStatAdaptorImplTest {

    private val inserted = mutableListOf<TokenStats>()
    private lateinit var adaptor: TokenStatAdaptorImpl

    @BeforeEach
    fun setUp() {
        val tokenStatsMapper = mock(TokenStatsMapper::class.java)
        `when`(tokenStatsMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<TokenStats>(0).let { inserted += it }
            1
        }
        adaptor = TokenStatAdaptorImpl(tokenStatsMapper)
    }

    private fun stat(
        agentId: Long?,
        tenantId: Long?,
        timestamp: Long = 1_700_000_000_000L,
    ) = TokenStat(
        agentId = agentId,
        tenantId = tenantId,
        modelId = 2L,
        sessionId = "s-1",
        inputToken = 10,
        outputToken = 5,
        totalToken = 15,
        timestamp = timestamp,
    )

    @Test
    @DisplayName("the row carries the run's tenant next to its agent")
    fun saveTokenStatStoresTheRunTenant() {
        adaptor.saveTokenStat(stat(agentId = 11L, tenantId = 5L))

        val row = inserted.single()
        assertEquals(5L, row.tenantId)
        assertEquals(11L, row.agentId)
        assertEquals("s-1", row.sessionId)
        assertEquals(15L, row.totalToken)
    }

    @Test
    @DisplayName("a spec that delivered no tenant stores the row unattributed rather than guessing a workspace")
    fun saveTokenStatKeepsAnUnattributedRunUnattributed() {
        adaptor.saveTokenStat(stat(agentId = 11L, tenantId = null))

        assertEquals(null, inserted.single().tenantId)
    }

    @Test
    @DisplayName("a team lead has no agent row but still owns a tenant")
    fun saveTokenStatStoresTheTenantWithoutAnAgent() {
        adaptor.saveTokenStat(stat(agentId = null, tenantId = 5L))

        val row = inserted.single()
        assertEquals(null, row.agentId)
        assertEquals(5L, row.tenantId)
    }

    @Test
    @DisplayName("the row is stamped with its own call, not with the write")
    fun saveTokenStatStoresTheCallMoment() {
        // One seed serves every turn of a keep-alive session, so a row that took the write's clock instead
        // of the call's would pile a whole session into the first turn's bucket of every time series.
        val call = Instant.now().minusSeconds(3_600)
        adaptor.saveTokenStat(stat(agentId = 11L, tenantId = 5L, timestamp = call.toEpochMilli()))

        val stored = inserted.single().ts.atZone(ZoneId.systemDefault()).toInstant()
        assertEquals(call.epochSecond, stored.epochSecond, "the row must carry the second its call happened")
    }
}
