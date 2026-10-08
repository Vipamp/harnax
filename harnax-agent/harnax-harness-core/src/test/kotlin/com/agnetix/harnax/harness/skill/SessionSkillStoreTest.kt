package com.agnetix.harnax.harness.skill

import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class SessionSkillStoreTest {

    private val root = "/workspace"

    private fun storeWith(handle: Sandbox?): SessionSkillStore = SessionSkillStore(SandboxHandleProvider { handle }, root)

    @Test
    fun `a session whose container is gone answers nothing and does not throw`() {
        val store = storeWith(null)
        assertNull(store.filesystemFor("chn-1"))
        assertTrue(store.listDraftNames("chn-1").isEmpty())
        assertTrue(store.listEnabled("chn-1").isEmpty())
        assertEquals(EnableOutcome.NoSandbox, store.enable("chn-1", "invoice-fill"))
    }

    @Test
    fun `a name that is not a relative path is refused before it reaches a shell`() {
        val sandbox = Mockito.mock(DockerSandbox::class.java)
        Mockito.`when`(sandbox.exec(Mockito.isNull(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(ExecResult(0, "", "", false))
        val outcome = storeWith(sandbox).enable("ses-1", "../../etc")
        assertTrue(outcome is EnableOutcome.SourceMissing, "got $outcome")
        Mockito.verifyNoInteractions(sandbox)
    }
}
