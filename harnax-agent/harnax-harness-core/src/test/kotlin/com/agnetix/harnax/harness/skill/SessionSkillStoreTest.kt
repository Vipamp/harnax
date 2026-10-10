package com.agnetix.harnax.harness.skill

import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.model.GlobResult
import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any

class SessionSkillStoreTest {

    private val root = "/workspace"

    private fun storeWith(handle: Sandbox?): SessionSkillStore = SessionSkillStore(SandboxHandleProvider { handle }, root)

    @Test
    fun `a session whose container is gone has no enabled list to read`() {
        val store = storeWith(null)
        assertNull(store.filesystemFor("chn-1"))
        assertTrue(store.listDraftNames("chn-1").isEmpty())
        // Null, not empty: empty is what a running container with nothing in its enabled zone answers, and a caller
        // that cannot tell the two apart tells the operator their session wrote nothing when it in fact has nowhere
        // to write to.
        assertNull(store.listEnabled("chn-1"))
        assertEquals(EnableOutcome.NoSandbox, store.enable("chn-1", "invoice-fill"))
    }

    @Test
    fun `a live container with nothing enabled answers an empty list rather than a refusal`() {
        val filesystem = Mockito.mock(AbstractFilesystem::class.java)
        Mockito.`when`(filesystem.glob(any(), anyString(), anyString())).thenReturn(GlobResult.fail("none"))
        val store = SessionSkillStore(
            SandboxHandleProvider { Mockito.mock(Sandbox::class.java) },
            root,
            pinnedFilesystem = { filesystem },
        )
        assertEquals(emptyList<EnabledSkill>(), store.listEnabled("ses-1"))
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
