package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.skill.EnableOutcome
import com.agnetix.harnax.harness.skill.EnabledSkill
import com.agnetix.harnax.harness.skill.SessionSkillStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

/**
 * The two calls a session panel makes. Both answer even when the container is gone, because "this session
 * has nothing enabled" and "this session's sandbox is stopped" look the same to a panel and only one of
 * them is a dead end for the operator.
 */
class SessionSkillControllerTest {

    private val launcher = Mockito.mock(HarnessAgentLauncher::class.java)

    private val store = Mockito.mock(SessionSkillStore::class.java)

    private fun controller(): SessionSkillController {
        Mockito.`when`(launcher.sessionSkillStore).thenReturn(store)
        return SessionSkillController(launcher)
    }

    @Test
    fun `a stopped container lists nothing`() {
        Mockito.`when`(store.listEnabled("ses-1")).thenReturn(emptyList())
        assertTrue(controller().list("ses-1").isSuccess())
    }

    @Test
    fun `an enable carries the verdict the operator is agreeing with`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Enabled("invoice-fill", "CAUTION", 2))
        val body = controller().enable("ses-1", "invoice-fill").data!!
        assertTrue(body.ok)
        assertEquals("CAUTION", body.verdict)
        assertEquals(2, body.findings)
    }

    @Test
    fun `a blocked enable is a refusal the panel can name`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Blocked("DANGEROUS", listOf("rm -rf")))
        val vo = controller().enable("ses-1", "invoice-fill")
        assertFalse(vo.isSuccess())
        assertTrue(vo.message.orEmpty().contains("DANGEROUS"), vo.message)
    }

    @Test
    fun `a session over the cap says so with the number`() {
        Mockito.`when`(store.enable(any(), any()))
            .thenReturn(EnableOutcome.Full(10))
        val vo = controller().enable("ses-1", "invoice-fill")
        assertFalse(vo.isSuccess())
        assertTrue(vo.message.orEmpty().contains("10"), vo.message)
    }

    // ─── the code each refusal carries, since the proxy passes it through untouched ───

    @Test
    fun `a blocked enable is refused with 403`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Blocked("DANGEROUS", listOf("rm -rf")))
        val vo = controller().enable("ses-1", "invoice-fill")
        assertEquals(403, vo.code)
        assertTrue(vo.message.contains("rm -rf"), vo.message)
        assertNull(vo.data)
    }

    @Test
    fun `a session over the cap is refused with 409`() {
        Mockito.`when`(store.enable(any(), any())).thenReturn(EnableOutcome.Full(10))
        assertEquals(409, controller().enable("ses-1", "invoice-fill").code)
    }

    @Test
    fun `a missing draft is refused with 404 and names what is missing`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill")).thenReturn(EnableOutcome.SourceMissing)
        val vo = controller().enable("ses-1", "invoice-fill")
        assertEquals(404, vo.code)
        assertTrue(vo.message.contains("invoice-fill"), vo.message)
    }

    @Test
    fun `a refused copy is refused with 500 and carries the container's reason`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Failed("no space left on device"))
        val vo = controller().enable("ses-1", "invoice-fill")
        assertEquals(500, vo.code)
        assertTrue(vo.message.contains("no space left on device"), vo.message)
    }

    @Test
    fun `no running sandbox is refused with 410 because only restarting the session fixes it`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill")).thenReturn(EnableOutcome.NoSandbox)
        val vo = controller().enable("ses-1", "invoice-fill")
        assertEquals(410, vo.code)
        assertTrue(vo.message.contains("sandbox"), vo.message)
    }

    // ─── what the panel renders ───

    @Test
    fun `the list hands over the name the description and when it was enabled`() {
        Mockito.`when`(store.listEnabled("ses-1")).thenReturn(
            listOf(EnabledSkill("invoice-fill", "Fills the invoice form", "2026-10-08 10:00:00")),
        )
        val view = controller().list("ses-1").data!!.single()
        assertEquals("invoice-fill", view.name)
        assertEquals("Fills the invoice form", view.description)
        assertEquals("2026-10-08 10:00:00", view.enabledAt)
    }

    @Test
    fun `a successful enable reports how many skills this session now has`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Enabled("invoice-fill", "SAFE", 0))
        Mockito.`when`(store.listEnabledNames("ses-1")).thenReturn(listOf("invoice-fill", "pdf-summarize"))
        val body = controller().enable("ses-1", "invoice-fill").data!!
        assertEquals("invoice-fill", body.name)
        assertEquals(2, body.count)
    }

    @Test
    fun `a caller that sends no body still enables`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Enabled("invoice-fill", "SAFE", 0))
        assertTrue(controller().enable("ses-1", "invoice-fill", null).isSuccess())
    }
}
