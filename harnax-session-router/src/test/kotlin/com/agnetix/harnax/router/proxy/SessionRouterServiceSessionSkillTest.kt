package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.auth.AuthContext
import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.controller.AgentProxyController
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.AgentServiceClient
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionAccessGuard
import com.agnetix.harnax.router.service.SessionEvictor
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import java.time.Instant
import kotlin.coroutines.Continuation

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
        assertEquals(200, result.code)
        // The name promises "lists nothing", so the payload is what has to be pinned: `assertNotNull(result)`
        // on a non-null return type said nothing, and a success carrying rows for a session that was never
        // bound anywhere would have stayed green, as would a null data the clients read as an empty box.
        assertEquals(emptyList<Map<String, Any>>(), result.data, "an unbound session must answer an empty list")
        Mockito.verifyNoInteractions(client)
    }

    /**
     * The list leg's half of the rule the enable cases below pin: the read follows the existing binding and
     * carries the agent's rows back unchanged. It needed its own case because the two legs are two methods,
     * and only one of them had a test naming the destination — swapping this leg to `resolveInstance`, or
     * hard-coding a base URL, kept every other case in the class green.
     */
    @Test
    fun `a bound session's list travels to the instance that holds it`() {
        runBlocking {
            val (service, client) = fixture(bound = true)
            val rows = listOf(mapOf("name" to "invoice-fill", "enabledAt" to "2026-10-09T01:02:03Z"))
            Mockito.`when`(client.sessionSkillList(any(), eq("ses-1")))
                .thenReturn(ResultVo.success(rows))

            val result = service.proxySessionSkills("ses-1")

            Mockito.verify(client).sessionSkillList(eq("http://agent:8082"), eq("ses-1"))
            assertEquals(rows, result.data, "the proxy rewrote or dropped rows the agent sent")
        }
    }

    /**
     * Every enable case here is block-bodied on purpose. `fun x() = runBlocking { … }` gives the method the type
     * of the block's last expression, and these end on a mocked call that answers with a `ResultVo` — a
     * `@Test` that returns a value is not a test candidate, so Jupiter drops the case without a word. This
     * class ran only one of its three original cases for exactly that reason, until the check below pinned it.
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
     * The property this proxy exists for: an upstream refusal is the agent's verdict, not the router's guess,
     * and it rides in the envelope's `code` while HTTP stays 200. The two enable cases that were here before
     * this one threw the returned value away, so nothing pinned it — deleting the pass-through (normalising
     * 403 to 200, re-minting it as 500, inventing a payload) kept all of them green. Stubbing a refusal and
     * asserting the code, the reason and the absent data falsifies every such edit, in the shape
     * `SessionRouterServiceTest."proxyCommandRequest passes a failed command through untouched"` already uses.
     */
    @Test
    fun `the agent's refusal arrives with the code and the reason the agent gave`() {
        runBlocking {
            val (service, client) = fixture(bound = true)
            AuthContextHolder.set(AuthContext("webui-caller", userId = 42L))
            Mockito.`when`(client.sessionSkillEnable(any(), eq("ses-1"), eq("invoice-fill"), eq(42L)))
                .thenReturn(ResultVo.error(403, "the security scan says DANGEROUS: rm -rf"))

            val result = service.proxyEnableSessionSkill("ses-1", "invoice-fill")

            assertEquals(403, result.code, "the proxy remapped the agent's refusal code")
            assertEquals(
                "the security scan says DANGEROUS: rm -rf",
                result.message,
                "the proxy replaced the agent's reason with its own",
            )
            assertNull(result.data, "a refusal must not carry a payload the agent did not send")
        }
    }

    /**
     * The one refusal the router mints itself rather than inheriting: with no binding there is no container to
     * copy into. Nothing pinned this arm — deleting the `?: return` handed a null target to `callBound`, and the
     * class stayed green because every other enable case stubs a bound instance.
     */
    @Test
    fun `an enable for a session with no bound instance is refused 410 without a hop`() {
        runBlocking {
            val (service, client) = fixture(bound = false)
            AuthContextHolder.set(AuthContext("webui-caller", userId = 42L))

            val result = service.proxyEnableSessionSkill("ses-1", "invoice-fill")

            assertEquals(410, result.code, "an unbound enable lost the 410 the clients' tables have a row for")
            assertTrue(
                result.message.contains("no sandbox"),
                "the refusal should name the missing container rather than fail generically: ${result.message}",
            )
            assertNull(result.data, "a refusal must not carry a payload")
            Mockito.verifyNoInteractions(client)
        }
    }

    /**
     * D11 names the operator of an enable from this request's own auth context, which means the endpoint must
     * not offer the caller a place to type one. Spring fills a handler parameter from the request body either
     * when it carries `@RequestBody` or when it is an unannotated complex type the body-argument resolver picks
     * up, and both shapes change this method's parameter list — so the whole list is pinned, not just the
     * annotation. The list also refuses a caller-typed operator that arrives as a query parameter, which carries
     * no body annotation at all and would otherwise pass the first check.
     * Derived from the controller by reflection the way `ApiCallLogFilterTest` derives the suspend
     * endpoint paths from its annotations, so it cannot drift from the handler it guards.
     */
    @Test
    fun `no parameter of the enable handler can carry a body that would name an operator`() {
        val handlers = AgentProxyController::class.java.declaredMethods
            .filter { !it.isSynthetic && it.name == "proxyEnableSessionSkill" }
        // Without this the assertions below would pass vacuously on a renamed or moved handler.
        assertEquals(
            1,
            handlers.size,
            "AgentProxyController must have exactly one enable handler, found ${handlers.map { it.name }}",
        )
        val handler = handlers.first()
        // A check on the check: reflection sees parameter annotations on this method at all, so the absence
        // of @RequestBody below is a finding rather than an artefact of the view.
        assertTrue(
            handler.parameterAnnotations.any { annotations -> annotations.any { it is PathVariable } },
            "reflection saw no parameter annotations on the enable handler",
        )
        val bodyBound = handler.parameterAnnotations
            .mapIndexedNotNull { index, annotations -> index.takeIf { annotations.any { it is RequestBody } } }
        assertTrue(
            bodyBound.isEmpty(),
            "the enable handler binds the request body at parameters $bodyBound; the operator may then be caller-typed",
        )
        assertEquals(
            listOf(String::class.java, String::class.java, HttpServletRequest::class.java, Continuation::class.java),
            handler.parameterTypes.toList(),
            "the enable handler takes more than the two path variables, the request and the continuation",
        )
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
