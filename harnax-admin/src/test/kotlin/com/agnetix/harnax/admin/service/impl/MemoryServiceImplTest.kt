package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.MemoryAgentResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Who the memory is read as, and therefore which namespace is addressed.
 *
 * These tests are about one thing: the tenant and user handed to the store layer come out of the session,
 * never out of a request. `GET /api/admin/memory` has no owner parameter at all, so the only way to leak
 * another person's memory is to resolve the caller wrongly — and the only way to reach another person's
 * *agent* is to let a path variable become a path segment. The assertions below capture the exact
 * `(tenantId, userId, agentId)` triple the gateway receives.
 *
 * The second thing they cover is the agent row each listing reads to explain its layers: it is read under the
 * same resolved tenant as the memory itself, and a failure there costs the explanation and not the memory.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryServiceImpl - the caller's own namespace only")
class MemoryServiceImplTest {

    @Mock
    private lateinit var memoryStoreGateway: MemoryStoreGateway

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    private lateinit var service: MemoryServiceImpl

    @BeforeEach
    fun setUp() {
        service = MemoryServiceImpl(memoryStoreGateway, agentMapper, jwtUtil)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()
        // The SecurityUtils singleton is process-wide; leaving it registered would show the next test class
        // an account that belongs to this one.
        val instanceField = SecurityUtils::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    /**
     * Logs in as `member` (id 7, tenant 4), the way `JwtAuthenticationFilter` leaves the context.
     *
     * The tenant context is deliberately left empty so the resolution chain has to walk to the account row:
     * a test that put a tenant in [TenantContext] first would pass even if the row were ignored.
     */
    private fun loginAsMember() {
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername(MEMBER)).thenReturn(
            SysUser().apply {
                id = 7L
                username = MEMBER
                tenantId = 4L
            },
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(MEMBER, null, ArrayList())
    }

    /** A principal with no account row: what a shared-secret service call looks like to this module. */
    private fun loginAsService(principal: String) {
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername(principal)).thenReturn(null)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, ArrayList())
    }

    /** The account the caller resolves to, with an explicit id so the bucket segment can be pinned. */
    private fun loginAsUserId(id: Long) {
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername(MEMBER)).thenReturn(
            SysUser().apply {
                username = MEMBER
                this.id = id
                tenantId = 4L
            },
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(MEMBER, null, ArrayList())
    }

    @Nested
    @DisplayName("Listing")
    inner class Listing {

        @Test
        @DisplayName("the listing addresses the caller's tenant and numeric id, not the username")
        fun `listMyMemory should address the caller's own namespace`() {
            loginAsMember()
            `when`(memoryStoreGateway.listAgents(4L, "7")).thenReturn(
                listOf(MemoryAgentResponse(agentId = "Research", content = "# Memory", dates = listOf("2026-10-05"))),
            )

            val agents = service.listMyMemory()

            assertEquals(listOf("Research"), agents.map { it.agentId })
            verify(memoryStoreGateway).listAgents(4L, "7")
        }

        @Test
        @DisplayName("a workspace switch moves the tenant but never the owner")
        fun `listMyMemory should follow the verified tenant and keep the caller's id`() {
            loginAsMember()
            TenantContext.setTenantId(9L)
            `when`(memoryStoreGateway.listAgents(9L, "7")).thenReturn(emptyList())

            service.listMyMemory()

            verify(memoryStoreGateway).listAgents(9L, "7")
        }

        @Test
        fun `a second account addresses a second prefix and nothing of the first`() {
            loginAsUserId(8L)
            `when`(memoryStoreGateway.listAgents(4L, "8")).thenReturn(emptyList())

            service.listMyMemory()

            verify(memoryStoreGateway).listAgents(4L, "8")
            verify(memoryStoreGateway, never()).listAgents(4L, "7")
        }

        /**
         * The page has to be able to say why an agent's long-term ledger stops short.
         *
         * An agent whose conversation layer is on writes its recent days into that layer and only reaches
         * `MEMORY.md` at a merge, so its long-term dates can look frozen while nothing is broken. Without the
         * switch the page would have to explain that with a guess about the dates it has.
         */
        @Test
        fun `an agent with the conversation layer on is reported as such`() {
            loginAsMember()
            `when`(memoryStoreGateway.listAgents(4L, "7")).thenReturn(
                listOf(MemoryAgentResponse(agentId = "Research", dates = listOf("2026-09-01"))),
            )
            `when`(agentMapper.selectByName("Research", 4L)).thenReturn(Agent().apply { sessionMemoryEnabled = 1 })

            assertEquals(true, service.listMyMemory().single().sessionMemory)
        }

        /**
         * Off and unknown are different answers, and only one of them is a fact about the agent.
         *
         * A row that has the column at 0 was never given a second layer; no row at all means the agent was
         * renamed or deleted since it wrote this memory, and calling that single-layer would tell the owner
         * their memory is complete. Each row keeps its own answer: one lookup applied to the whole listing
         * would make both of these wrong in the same way.
         */
        @Test
        fun `a single-layer agent says off and an agent with no row says nothing`() {
            loginAsMember()
            `when`(memoryStoreGateway.listAgents(4L, "7")).thenReturn(
                listOf(
                    MemoryAgentResponse(agentId = "Ops"),
                    MemoryAgentResponse(agentId = "Renamed away"),
                ),
            )
            `when`(agentMapper.selectByName("Ops", 4L)).thenReturn(Agent().apply { sessionMemoryEnabled = 0 })
            `when`(agentMapper.selectByName("Renamed away", 4L)).thenReturn(null)

            val layers = service.listMyMemory().associate { it.agentId to it.sessionMemory }

            assertEquals(mapOf("Ops" to false, "Renamed away" to null), layers)
        }

        /**
         * The layer hint is decoration on a compliance listing.
         *
         * An owner has to be able to see and delete their memory while the agent table is having a bad day,
         * so this lookup degrades to unknown instead of failing the page — and unknown costs the explanation,
         * never the memory itself.
         */
        @Test
        fun `an agent lookup that fails still lists the memory`() {
            loginAsMember()
            `when`(memoryStoreGateway.listAgents(4L, "7")).thenReturn(
                listOf(MemoryAgentResponse(agentId = "Research", content = "# Memory")),
            )
            `when`(agentMapper.selectByName("Research", 4L)).thenThrow(RuntimeException("db unreachable"))

            val rows = service.listMyMemory()

            assertEquals(listOf("Research"), rows.map { it.agentId })
            assertEquals("# Memory", rows.single().content)
            assertNull(rows.single().sessionMemory)
        }

        /** Agent names are unique per tenant, not across tenants, so an unqualified lookup would answer for another workspace. */
        @Test
        fun `the layer lookup is names by the tenant the memory was read from`() {
            loginAsMember()
            TenantContext.setTenantId(9L)
            `when`(memoryStoreGateway.listAgents(9L, "7")).thenReturn(
                listOf(MemoryAgentResponse(agentId = "Research")),
            )

            service.listMyMemory()

            verify(agentMapper).selectByName("Research", 9L)
            verify(agentMapper, never()).selectByName("Research", 4L)
        }
    }

    @Nested
    @DisplayName("Read and delete")
    inner class ReadAndDelete {

        @Test
        fun `readMyMemory should read the caller's own agent only`() {
            loginAsMember()

            service.readMyMemory("Research")

            verify(memoryStoreGateway).readAgent(4L, "7", "Research")
        }

        @Test
        fun `deleteMyMemory should delete the caller's own agent only`() {
            loginAsMember()
            `when`(memoryStoreGateway.deleteAgent(4L, "7", "Research")).thenReturn(3)

            assertEquals(3, service.deleteMyMemory("Research"))

            verify(memoryStoreGateway).deleteAgent(4L, "7", "Research")
        }

        @Test
        fun `a missing agent reads as absent instead of an invented answer`() {
            loginAsMember()
            `when`(memoryStoreGateway.readAgent(4L, "7", "Research")).thenReturn(null)

            assertNull(service.readMyMemory("Research"))
        }
    }

    @Nested
    @DisplayName("Agent id validation")
    inner class AgentIdValidation {

        /**
         * None of these can reach the store. Every one is either a traversal or a separator, so letting it
         * through would build a key outside `store/tenants/4/users/7/` — somebody else's memory.
         */
        private val refused = listOf(
            "../secret",
            "..",
            "a/b",
            "/etc/passwd",
            "Research/../../9",
            "agent\\name",
            "Research\u0000",
            "Research\u0001",
            "",
            " ",
        )

        @Test
        fun `an agent id that could escape the caller's prefix is refused before any key is built`() {
            loginAsMember()

            for (agentId in refused) {
                val error = assertThrows(BizException::class.java, { service.readMyMemory(agentId) })
                assertEquals(400, error.code, "refusing '$agentId' must be a client error, not a store error")
                assertThrows(BizException::class.java, { service.deleteMyMemory(agentId) })
            }

            verifyNoInteractions(memoryStoreGateway)
        }

        @Test
        fun `a well-formed agent id is passed through unchanged`() {
            loginAsMember()
            `when`(memoryStoreGateway.readAgent(4L, "7", "研究助手")).thenReturn(null)

            service.readMyMemory("研究助手")

            verify(memoryStoreGateway).readAgent(4L, "7", "研究助手")
        }
    }

    @Nested
    @DisplayName("Caller resolution")
    inner class CallerResolution {

        @Test
        fun `an anonymous caller is refused and the store is never listed`() {
            val error = assertThrows(BizException::class.java, { service.listMyMemory() })

            assertEquals(401, error.code)
            verifyNoInteractions(memoryStoreGateway)
        }

        @Test
        fun `a shared-secret service caller is refused instead of shown the default tenant`() {
            loginAsService("internal-service")

            val error = assertThrows(BizException::class.java, { service.listMyMemory() })

            assertEquals(401, error.code)
            verifyNoInteractions(memoryStoreGateway)
        }

        @Test
        fun `a principal with no account row is refused`() {
            loginAsService("nobody-here")

            val error = assertThrows(BizException::class.java, { service.listMyMemory() })

            assertEquals(401, error.code)
            verifyNoInteractions(memoryStoreGateway)
        }

        @Test
        fun `an account without a usable id is refused rather than bucketed under zero`() {
            loginAsUserId(0L)

            val error = assertThrows(BizException::class.java, { service.deleteMyMemory("Research") })

            assertEquals(401, error.code)
            verifyNoInteractions(memoryStoreGateway)
        }
    }

    private companion object {
        const val MEMBER = "member"
    }
}
