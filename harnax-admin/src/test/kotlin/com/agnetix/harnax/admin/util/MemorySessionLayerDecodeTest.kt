package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decoding a conversation's own memory layer out of a bucket listing.
 *
 * The runtime keys the session layer one segment deeper than the long-term layer, inside the agent prefix.
 * If [MemoryObjectKeys.locationOf] refuses that shape the page keeps showing only the long-term layer,
 * which looks right, while both delete sweeps leave every un-promoted session object behind — a "deleted"
 * user whose memory is still in the bucket is the failure this decoder has to make impossible.
 */
class MemorySessionLayerDecodeTest {

    private val ownerPrefix = MemoryObjectKeys.ownerPrefix(
        MemoryObjectKeys.DEFAULT_KEY_PREFIX,
        tenantId = 4L,
        userId = "7",
    )

    @Test
    fun `a session object decodes with its session and its route`() {
        val key = "$ownerPrefix" + "agents/Research/sessions/sess-A/root/MEMORY.md"

        val location = MemoryObjectKeys.locationOf(ownerPrefix, key)

        assertEquals("Research", location?.agentId, "the agent still owns the session layer")
        assertEquals("root", location?.segment)
        assertEquals("/MEMORY.md", location?.itemKey)
        assertEquals("sess-A", location?.sessionId)
    }

    @Test
    fun `a session ledger decodes too`() {
        val key = "$ownerPrefix" + "agents/Research/sessions/sess-A/memory/2026-10-05.md"

        val location = MemoryObjectKeys.locationOf(ownerPrefix, key)

        assertEquals("memory", location?.segment)
        assertEquals("/2026-10-05.md", location?.itemKey)
        assertEquals("sess-A", location?.sessionId)
    }

    @Test
    fun `a long-term object has no session`() {
        val key = "$ownerPrefix" + "agents/Research/root/MEMORY.md"

        val location = MemoryObjectKeys.locationOf(ownerPrefix, key)

        assertEquals("root", location?.segment)
        assertNull(location?.sessionId, "the long-term layer is the one the page lists")
    }

    @Test
    fun `a sessions directory that names no route is not memory`() {
        val key = "$ownerPrefix" + "agents/Research/sessions/sess-A/"

        assertNull(MemoryObjectKeys.locationOf(ownerPrefix, key), "a marker under sessions has no route tail")
    }

    @Test
    fun `an empty session segment is refused rather than guessed`() {
        val key = "$ownerPrefix" + "agents/Research/sessions//root/MEMORY.md"

        assertNull(
            MemoryObjectKeys.locationOf(ownerPrefix, key),
            "a key with no session id cannot be attributed to a conversation, so no sweep should name it",
        )
    }

    @Test
    fun `a session key under a foreign owner still decodes only under its own prefix`() {
        val foreign = MemoryObjectKeys.ownerPrefix(MemoryObjectKeys.DEFAULT_KEY_PREFIX, 4L, "8")
        val key = "$ownerPrefix" + "agents/Research/sessions/sess-A/root/MEMORY.md"

        assertNull(
            MemoryObjectKeys.locationOf(foreign, key),
            "user 8's sweep must not pick up user 7's conversation",
        )
    }

    @Test
    fun `the agent prefix a sweep lists by already covers the session layer`() {
        val agentPrefix = MemoryObjectKeys.agentPrefix(
            MemoryObjectKeys.DEFAULT_KEY_PREFIX,
            tenantId = 4L,
            userId = "7",
            agentId = "Research",
        )
        val sessionKey = MemoryObjectKeys.sessionMemoryMdKey(
            MemoryObjectKeys.DEFAULT_KEY_PREFIX,
            tenantId = 4L,
            userId = "7",
            agentId = "Research",
            sessionId = "sess-A",
        )

        assertEquals("store/tenants/4/users/7/agents/Research/sessions/sess-A/root/MEMORY.md", sessionKey)
        assertEquals(true, sessionKey.startsWith(agentPrefix), "so deleting an agent takes both layers")
    }

    @Test
    fun `the session key follows the tenant switch the writer follows`() {
        assertEquals(
            "store/users/7/agents/Research/sessions/sess-A/root/MEMORY.md",
            MemoryObjectKeys.sessionMemoryMdKey(
                MemoryObjectKeys.DEFAULT_KEY_PREFIX,
                tenantId = 4L,
                userId = "7",
                agentId = "Research",
                sessionId = "sess-A",
                tenantScoped = false,
            ),
        )
    }

    /**
     * Which conversation objects still mean "not merged into the long-term layer yet".
     *
     * The pending figure is the owner's only hint that the long-term listing is not the whole story, so it has
     * to count exactly what the merge takes. The consolidation pass's own state object and a day upstream
     * retired into `archive/` are still there after a merge that went well, and counting either would leave
     * every fully merged agent permanently behind.
     */
    @Test
    fun `a conversation draft and its dated ledgers are unmerged memory`() {
        assertTrue(unmerged("agents/Research/sessions/sess-A/root/MEMORY.md"))
        assertTrue(unmerged("agents/Research/sessions/sess-A/memory/2026-10-05.md"))
    }

    @Test
    fun `the passes state object and an archived day are not unmerged memory`() {
        assertFalse(
            unmerged("agents/Research/sessions/sess-A/memory/.consolidation_state"),
            "the pass keeps this object whether or not there is memory waiting",
        )
        assertFalse(
            unmerged("agents/Research/sessions/sess-A/memory/archive/2026-08-01.md"),
            "an archived day is history the long-term layer already curates",
        )
    }

    /** One of this owner's keys, decoded and then asked the question the listing asks. */
    private fun unmerged(relativeKey: String): Boolean {
        val location = MemoryObjectKeys.locationOf(ownerPrefix, "$ownerPrefix$relativeKey")
            ?: error("$relativeKey should decode as a memory object of this owner")
        return MemoryObjectKeys.hasUnmergedContent(location)
    }
}
