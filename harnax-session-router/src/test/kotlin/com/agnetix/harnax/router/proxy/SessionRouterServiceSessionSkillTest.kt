package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.auth.AuthContext
import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.AgentServiceClient
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionAccessGuard
import com.agnetix.harnax.router.service.SessionEvictor
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import java.time.Instant

/**
 * An unbound session answers empty instead of being placed on some other instance: the enabled skills are
 * files inside one container, and a second agent's container does not have them. That is the rule the
 * workspace reads already follow — `SessionRouterService.boundInstance`, whose first line is
 * `sessionAccessGuard.requireAccessible` — and these two inherit it.
 */
class SessionRouterServiceSessionSkillTest {

    @AfterEach
    fun clearAuthContext() {
        // The holder is a ThreadLocal; leaving a context set would decide the next test on this thread.
        AuthContextHolder.clear()
    }

    @Test
    fun `a session with no bound instance lists nothing and calls nobody`() = runBlocking {
        val (service, client) = fixture(bound = false)
        val result = service.proxySessionSkills("ses-1")
        assertNotNull(result)
        assertEquals(200, result.code)
        Mockito.verifyNoInteractions(client)
    }

    /**
     * Both enable cases are block-bodied on purpose. `fun x() = runBlocking { … }` gives the method the type
     * of the block's last expression, and these end on a mocked call that answers with a `ResultVo` — a
     * `@Test` that returns a value is not a test candidate, so Jupiter drops the case without a word. This
     * class ran one of its three cases for exactly that reason until it was pinned below.
     */
    @Test
    fun `an enable travels to the instance that holds the session and carries its operator`() {
        runBlocking {
            val (service, client) = fixture(bound = true)
            AuthContextHolder.set(AuthContext("webui-caller", userId = 42L))
            Mockito.`when`(client.sessionSkillEnable(any(), eq("ses-1"), eq("invoice-fill"), eq(42L)))
                .thenReturn(ResultVo.success(mapOf("ok" to true)))
            service.proxyEnableSessionSkill("ses-1", "invoice-fill")
            Mockito.verify(client).sessionSkillEnable(
                eq("http://agent:8082"),
                eq("ses-1"),
                eq("invoice-fill"),
                eq(42L),
            )
        }
    }

    @Test
    fun `a caller with no auth context forwards no operator rather than an invented one`() {
        runBlocking {
            val (service, client) = fixture(bound = true)
            assertNull(AuthContextHolder.get())
            Mockito.`when`(client.sessionSkillEnable(any(), any(), any(), isNull()))
                .thenReturn(ResultVo.success(mapOf("ok" to true)))
            service.proxyEnableSessionSkill("ses-1", "invoice-fill")
            Mockito.verify(client).sessionSkillEnable(any(), eq("ses-1"), eq("invoice-fill"), isNull())
        }
    }

    /**
     * The falsifier for the trap above, so it cannot come back as a quiet disappearance: JUnit Jupiter does
     * not discover a `@Test` that returns a value — Kotlin compiles such a method with a return type — so a
     * case here would stop running without failing anything, and a green module would say nothing about it.
     */
    @Test
    fun `every case in this class is one Jupiter can discover`() {
        val hidden = SessionRouterServiceSessionSkillTest::class.java.declaredMethods
            .filter { it.isAnnotationPresent(Test::class.java) && it.returnType != Void.TYPE }
            .map { it.name }
        assertEquals(emptyList<String>(), hidden, "these @Test methods return a value and are never run")
    }

    private fun fixture(bound: Boolean): Pair<SessionRouterService, AgentServiceClient> {
        // Same shape as SessionRouterServiceTest.kt:69-89: the breaker and the meter registry are real,
        // everything else is a mock. bound=false makes sessionMappingService.getInstanceId answer null,
        // which makes boundInstance answer null.
        val client = Mockito.mock(AgentServiceClient::class.java)
        val mappingService = Mockito.mock(SessionMappingService::class.java)
        val registry = Mockito.mock(InstanceRegistry::class.java)
        Mockito.`when`(mappingService.getInstanceId("ses-1"))
            .thenReturn(if (bound) "inst-1" else null)
        if (bound) {
            // Built like healthyInstance at SessionRouterServiceTest.kt:104-111, only with host=agent:
            // getBaseUrl() is "http://$host:$port" (AgentInstance.kt:173), which is where the asserted
            // http://agent:8082 comes from.
            Mockito.`when`(registry.getInstance("inst-1")).thenReturn(
                AgentInstance().apply {
                    instanceId = "inst-1"
                    host = "agent"
                    port = 8082
                    status = "UP"
                    active = 1
                    lastHeartbeat = Instant.now()
                },
            )
        }
        val service = SessionRouterService(
            registry,
            mappingService,
            Mockito.mock(IdempotencyService::class.java),
            LocalInstanceCircuitBreaker(failureThreshold = 3, openDurationMs = 30000),
            client,
            Mockito.mock(SessionEvictor::class.java),
            Mockito.mock(SessionAccessGuard::class.java),
            SimpleMeterRegistry(),
            30000L,
            2,
        )
        return service to client
    }
}
