package com.agnetix.harnax.agent.service.client

import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.SkillDraftProposal
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * The envelope-to-intake mapping, tested against Admin's real wire shape rather than against `ResultVo`.
 *
 * Three of those shapes cannot be read off the DTO, and all three decide whether a proposal survives:
 * Admin drops null keys, so a refusal arrives with no `data` at all; the envelope carries an extra
 * `isSuccess` key the DTO does not declare; and a validation refusal arrives as HTTP 200 with `code` 400
 * in the body. Reading the HTTP status instead of the envelope would call every refusal a success, and
 * reading an unknown key as fatal would call every refusal an outage — which is what makes the agent
 * propose the same rejected skill on every turn forever.
 */
class AdminApiClientSkillDraftIntakeTest {

    private val proposal = SkillDraftProposal(
        sessionId = "web-42",
        name = "invoice-fill",
        description = "Fill an invoice from a table",
        skillmd = "# invoice-fill\n\nFill one row per line.",
        resources = mapOf("scripts/run.sh" to "echo first\n"),
        scanVerdict = "ALLOW",
        scanFindings = listOf("writes only inside the skill directory"),
    )

    /** Answers [body] with HTTP [status] on the intake path, and records the Authorization header it saw. */
    private fun withAdmin(
        status: Int = 200,
        body: String,
        block: (AdminApiClient, AtomicReference<String?>) -> Unit,
    ) {
        val authHeader = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/admin/internal/skills/drafts") { exchange ->
            authHeader.set(exchange.requestHeaders.getFirst(HttpHeaders.AUTHORIZATION))
            val bytes = body.toByteArray()
            exchange.responseHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(AdminApiClient("http://127.0.0.1:${server.address.port}", "test-secret"), authHeader)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a success envelope becomes Queued with the row id`() {
        // The extra isSuccess key is what a strict mapper would choke on; a draft that reads as an outage
        // is never stored and never reported, so the strictness has to be proven absent here.
        withAdmin(body = """{"code":200,"message":"success","data":7,"timestamp":1745712000000,"isSuccess":true}""") { client, auth ->
            val intake = client.submitSkillDraft(proposal)
            assertEquals(SkillDraftIntake.Queued(7L), intake)
            assertEquals("Bearer test-secret", auth.get())
        }
    }

    @Test
    fun `a validation refusal on http 200 becomes Refused with admin's reason`() {
        withAdmin(body = """{"code":400,"message":"skillmd is required: a draft with no body has nothing to review","timestamp":1745712000000,"isSuccess":false}""") { client, _ ->
            val intake = client.submitSkillDraft(proposal)
            assertTrue(
                intake is SkillDraftIntake.Refused &&
                    intake.reason == "skillmd is required: a draft with no body has nothing to review",
                "expected the refusal to carry Admin's own reason, got $intake",
            )
        }
    }

    @Test
    fun `a session admin cannot place becomes Refused`() {
        withAdmin(body = """{"code":404,"message":"session 'web-42' resolves to no tenant, so its proposal has no queue to enter","timestamp":1745712000000}""") { client, _ ->
            val intake = client.submitSkillDraft(proposal)
            assertTrue(intake is SkillDraftIntake.Refused, "code 404 must be a refusal, not an outage, got $intake")
        }
    }

    @Test
    fun `an internal fault on admin becomes Unavailable`() {
        withAdmin(body = """{"code":500,"message":"Skill draft intake failed","timestamp":1745712000000}""") { client, _ ->
            val intake = client.submitSkillDraft(proposal)
            assertTrue(
                intake is SkillDraftIntake.Unavailable && intake.reason.contains("code 500"),
                "expected an outage the caller may retry, got $intake",
            )
        }
    }

    @Test
    fun `a 200 with no row id is not reported as Queued`() {
        // ResultVo.success() without data: nothing was told us about where the draft went, and claiming a
        // queue row we never saw would hand the reviewer a link to nothing.
        withAdmin(body = """{"code":200,"message":"success","timestamp":1745712000000,"isSuccess":true}""") { client, _ ->
            val intake = client.submitSkillDraft(proposal)
            assertTrue(intake is SkillDraftIntake.Unavailable, "a success without an id must not queue, got $intake")
        }
    }

    @Test
    fun `a rejected secret becomes Unavailable instead of throwing`() {
        // The auth filter answers with a body ResultVo cannot read at all (success/errorCode/errorMessage,
        // no timestamp) on HTTP 401, so the transport raises here. All that is owed is that the call does
        // not throw and does not file a misconfigured secret as a content refusal the agent would retry.
        withAdmin(status = 401, body = """{"success":false,"code":401,"errorCode":"401","errorMessage":"invalid secret","message":"Unauthorized"}""") { client, _ ->
            val intake = client.submitSkillDraft(proposal)
            assertTrue(intake is SkillDraftIntake.Unavailable, "a rejected secret must read as an outage, got $intake")
        }
    }

    @Test
    fun `an unreachable admin becomes Unavailable`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.stop(0)
        val intake = AdminApiClient("http://127.0.0.1:$port", "test-secret").submitSkillDraft(proposal)
        assertTrue(intake is SkillDraftIntake.Unavailable, "connection refused must read as an outage, got $intake")
        assertTrue((intake as SkillDraftIntake.Unavailable).reason.isNotEmpty())
    }
}
