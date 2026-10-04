package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.SkillDraftProposal
import com.agnetix.harnax.agent.service.client.AdminApiClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * The one promise this layer adds over the HTTP client: an answer, never an exception.
 *
 * The promotion gate reads the result to decide between deferring a draft and refusing it, and a thrown
 * exception would force it to guess. So a client fault becomes `Unavailable`, which is the reading that
 * cannot lose a proposal, while a refusal and a queue id travel through unchanged — a refusal that came back
 * as an outage would have the agent propose the same rejected skill on every turn forever.
 */
class SkillDraftAdaptorImplTest {

    private val client = mock(AdminApiClient::class.java)
    private val adaptor = SkillDraftAdaptorImpl(client)

    private val proposal = SkillDraftProposal(
        sessionId = "web-42",
        name = "invoice-fill",
        description = "Fill an invoice from a table",
        skillmd = "# invoice-fill",
        resources = mapOf("scripts/run.sh" to "echo first\n"),
        scanVerdict = "SAFE",
        scanFindings = emptyList(),
    )

    @Test
    fun `a queued draft reports the row a reviewer will open`() {
        `when`(client.submitSkillDraft(proposal)).thenReturn(SkillDraftIntake.Queued(77L))

        val intake = adaptor.submit(proposal)

        assertEquals(SkillDraftIntake.Queued(77L), intake)
    }

    @Test
    fun `a refusal stays a refusal`() {
        `when`(client.submitSkillDraft(proposal)).thenReturn(SkillDraftIntake.Refused("skillmd is required"))

        val intake = adaptor.submit(proposal)

        assertTrue(
            intake is SkillDraftIntake.Refused,
            "a refusal the gate read as an outage would be proposed again every turn",
        )
        assertEquals("skillmd is required", (intake as SkillDraftIntake.Refused).reason)
    }

    @Test
    fun `an outage stays an outage`() {
        `when`(client.submitSkillDraft(proposal)).thenReturn(SkillDraftIntake.Unavailable("connection refused"))

        val intake = adaptor.submit(proposal)

        assertEquals(SkillDraftIntake.Unavailable("connection refused"), intake)
    }

    @Test
    fun `a client that throws is reported as unavailable rather than thrown at the gate`() {
        `when`(client.submitSkillDraft(proposal)).thenThrow(RuntimeException("admin is down"))

        val intake = adaptor.submit(proposal)

        assertEquals("admin is down", (intake as SkillDraftIntake.Unavailable).reason)
    }

    @Test
    fun `a client that throws with no message still answers with something a log can read`() {
        `when`(client.submitSkillDraft(proposal)).thenThrow(RuntimeException())

        val intake = adaptor.submit(proposal)

        assertEquals("RuntimeException", (intake as SkillDraftIntake.Unavailable).reason)
    }
}
