package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
