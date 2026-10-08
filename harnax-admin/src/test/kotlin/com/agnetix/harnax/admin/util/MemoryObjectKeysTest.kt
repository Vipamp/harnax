package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The memory bucket keys, asserted against the strings the agent runtime writes.
 *
 * `MinioBaseStore` (harnax-harness-core) builds every object key as `keyPrefix + namespace + "/" + itemKey`
 * and `MemoryFilesystemRoutes` builds the namespace as `tenants/<id>/users/<uid>/agents/<agentId>/<tail>`.
 * Admin reads that same bucket, so a key that differs by one character is a memory file admin cannot see,
 * and a key that differs in the wrong direction is somebody else's file. The exact strings below are the
 * contract; they are written out rather than computed, so a change in either module breaks this test.
 * The write side measures the same strings against a live MinIO in `MemoryObjectKeyCrossCheckTest`
 * (harnax-harness-core); the two tests share no code, so moving one without the other is a real risk.
 */
@DisplayName("MemoryObjectKeys - the store layout admin reads memory out of")
class MemoryObjectKeysTest {

    private val prefix = MemoryObjectKeys.DEFAULT_KEY_PREFIX

    @Nested
    @DisplayName("Exact object keys")
    inner class Keys {

        @Test
        fun `the curated layer is keyed under the root route segment`() {
            assertEquals(
                "store/tenants/4/users/u-1/agents/Research/root/MEMORY.md",
                MemoryObjectKeys.memoryMdKey(prefix, 4L, "u-1", "Research"),
            )
        }

        @Test
        fun `one daily ledger is keyed under the memory route segment`() {
            assertEquals(
                "store/tenants/4/users/u-1/agents/Research/memory/2026-10-05.md",
                MemoryObjectKeys.dailyKey(prefix, 4L, "u-1", "Research", "2026-10-05"),
            )
        }

        @Test
        fun `the namespace is tenants users agents route in that order`() {
            assertEquals(
                listOf("tenants", "4", "users", "u-1", "agents", "Research", "root"),
                MemoryObjectKeys.rootNamespace(4L, "u-1", "Research"),
            )
            assertEquals(
                listOf("tenants", "4", "users", "u-1", "agents", "Research", "memory"),
                MemoryObjectKeys.memoryNamespace(4L, "u-1", "Research"),
            )
        }

        /** The rule `MinioBaseStore.buildKey` documents: the item key's leading slash is dropped. */
        @Test
        fun `an item key keeps no leading slash in the object key`() {
            assertEquals(
                "store/agents/myAgent/sessions/sess-123/MEMORY.md",
                MemoryObjectKeys.buildKey(
                    "store/",
                    listOf("agents", "myAgent", "sessions", "sess-123"),
                    "/MEMORY.md",
                ),
            )
        }

        @Test
        fun `the owner prefix ends with a slash and stops at the owner`() {
            assertEquals("store/tenants/4/users/u-1/", MemoryObjectKeys.ownerPrefix(prefix, 4L, "u-1"))
            assertEquals(
                "store/tenants/4/users/u-1/agents/Research/",
                MemoryObjectKeys.agentPrefix(prefix, 4L, "u-1", "Research"),
            )
        }

        /** The runtime's owner id is the numeric `sys_user.id`; this is the one place that says so. */
        @Test
        fun `the user segment is the numeric id as the runtime carries it`() {
            assertEquals("7", MemoryObjectKeys.userSegment(7L))
            assertEquals(
                "store/tenants/4/users/7/agents/Research/root/MEMORY.md",
                MemoryObjectKeys.memoryMdKey(prefix, 4L, MemoryObjectKeys.userSegment(7L), "Research"),
            )
        }
    }

    /**
     * `harness.memory.tenant-scoped` decides whether the tenant is in the key at all, and the same env var
     * has to decide it here: a deployment that turned it off writes `store/users/<uid>/…`, and an admin that
     * kept prefixing `tenants/<id>` would list an empty area and report that owner as having no memory.
     */
    @Nested
    @DisplayName("The tenant-scoped switch")
    inner class TenantScope {

        @Test
        fun `an unscoped namespace drops the tenant pair from the front`() {
            assertEquals(
                listOf("users", "u-1", "agents", "Research", "root"),
                MemoryObjectKeys.rootNamespace(4L, "u-1", "Research", tenantScoped = false),
            )
            assertEquals(
                listOf("users", "u-1", "agents", "Research", "memory"),
                MemoryObjectKeys.memoryNamespace(4L, "u-1", "Research", tenantScoped = false),
            )
        }

        @Test
        fun `an unscoped key and prefix stop at the user`() {
            assertEquals(
                "store/users/u-1/agents/Research/root/MEMORY.md",
                MemoryObjectKeys.memoryMdKey(prefix, 4L, "u-1", "Research", tenantScoped = false),
            )
            assertEquals("store/users/u-1/", MemoryObjectKeys.ownerPrefix(prefix, 4L, "u-1", tenantScoped = false))
            assertEquals(
                "store/users/u-1/agents/Research/",
                MemoryObjectKeys.agentPrefix(prefix, 4L, "u-1", "Research", tenantScoped = false),
            )
        }

        /** Off means one owner shares a bucket area across tenants, so a sweep of that owner is one prefix. */
        @Test
        fun `two tenants of one unscoped owner are the same prefix`() {
            assertEquals(
                MemoryObjectKeys.ownerPrefix(prefix, 4L, "u-1", tenantScoped = false),
                MemoryObjectKeys.ownerPrefix(prefix, 5L, "u-1", tenantScoped = false),
            )
        }
    }

    @Nested
    @DisplayName("agentId allow-pattern")
    inner class AgentIdValidation {

        @Test
        fun `an ordinary agent name is accepted`() {
            listOf("Research", "assistant", "my-agent", "ops_agent", "v2.1", "研究助手").forEach { name ->
                assertEquals(true, MemoryObjectKeys.isValidAgentId(name), "$name should be addressable")
            }
        }

        /**
         * `agent.name` is a free `varchar(100)` with no pattern on the create form, so these names exist in
         * real deployments. They are one object key segment as written, and refusing them here would drop
         * that agent's memory from the owner's page and make it undeletable — a name the runtime could write
         * is a name admin has to be able to address.
         */
        @Test
        fun `a name with punctuation or a space inside is still addressable`() {
            listOf("Ops Agent", "summariser (beta)", "研究 助手", "agent,name", "助手：周报", "a(b)c", "v1 - draft").forEach { name ->
                assertEquals(true, MemoryObjectKeys.isValidAgentId(name), "'$name' should be addressable")
            }
        }

        /**
         * The traversal boundary. Every one of these would add a path level, climb out of the caller's
         * namespace, or split a request line if it reached the key builder, so none may build a key.
         */
        @Test
        fun `anything that could name a path is rejected`() {
            listOf(
                "../secret",
                "..",
                "Research/../../other-user",
                "a/b",
                "/etc/passwd",
                "root/MEMORY.md",
                "agent\\name",
                "..\\..\\windows",
                "Research\r\nX-Injected: 1",
                "Research\u0000",
                " Research",
                "Research ",
                "",
                "   ",
            ).forEach { candidate ->
                assertEquals(false, MemoryObjectKeys.isValidAgentId(candidate), "'$candidate' must not be addressable")
            }
        }

        @Test
        fun `null and an over-long name are rejected`() {
            assertEquals(false, MemoryObjectKeys.isValidAgentId(null))
            // `agent.name` is a 100 character column; a namespace segment longer than that was not written
            // by anything this deployment knows about.
            assertEquals(false, MemoryObjectKeys.isValidAgentId("a".repeat(101)))
            assertEquals(true, MemoryObjectKeys.isValidAgentId("a".repeat(100)))
        }

        /** A dot-dot anywhere is refused, not only a leading one: `..` is the traversal, in any position. */
        @Test
        fun `a dot-dot sequence inside an otherwise legal name is rejected`() {
            assertEquals(false, MemoryObjectKeys.isValidAgentId("Research..archive"))
            assertEquals(false, MemoryObjectKeys.isValidAgentId("a...b"))
        }
    }

    @Nested
    @DisplayName("Decoding a listed key")
    inner class Locations {

        private val owner = "store/tenants/4/users/7/"

        @Test
        fun `a curated object decodes to its agent and route`() {
            val location = MemoryObjectKeys.locationOf(owner, "${owner}agents/Research/root/MEMORY.md")
            assertNotNull(location)
            assertEquals("Research", location!!.agentId)
            assertEquals("root", location.segment)
            assertEquals("/MEMORY.md", location.itemKey)
        }

        @Test
        fun `a ledger object decodes to its agent route and dated item key`() {
            val location = MemoryObjectKeys.locationOf(owner, "${owner}agents/Research/memory/2026-10-05.md")
            assertNotNull(location)
            assertEquals("Research", location!!.agentId)
            assertEquals("memory", location.segment)
            assertEquals("/2026-10-05.md", location.itemKey)
            assertEquals("2026-10-05", MemoryObjectKeys.dateOf(location.itemKey))
        }

        /**
         * A key the caller's prefix returned but that is not a memory object — some other writer's file, or
         * an agent whose own name contains a slash and therefore shifted the route segment out of place —
         * decodes to nothing. The gateway then neither reads nor deletes it.
         */
        @Test
        fun `anything that is not a memory object of this owner decodes to nothing`() {
            assertNull(MemoryObjectKeys.locationOf(owner, "store/tenants/5/users/8/agents/Research/root/MEMORY.md"))
            assertNull(MemoryObjectKeys.locationOf(owner, "${owner}sessions/sess-1/agent_state.json"))
            assertNull(MemoryObjectKeys.locationOf(owner, "${owner}agents/Research/notes.md"))
            assertNull(MemoryObjectKeys.locationOf(owner, "${owner}agents//root/MEMORY.md"))
        }

        @Test
        fun `only a dated markdown item is a ledger date`() {
            assertEquals("2026-10-05", MemoryObjectKeys.dateOf("/2026-10-05.md"))
            assertNull(MemoryObjectKeys.dateOf("/MEMORY.md"))
            assertNull(MemoryObjectKeys.dateOf("/notes.md"))
            assertNull(MemoryObjectKeys.dateOf("/2026-13-45.md"))
        }

        /**
         * How far a bucket's ledgers have been merged is one object the runtime keeps beside them (§11.7).
         *
         * It decodes, because it has to: an agent delete and a user delete sweep the prefix, and a progress
         * object left behind would silently suppress consolidation for whoever next got that bucket. And it
         * answers no date, because the page offers dated entries only — an operator reading their own memory
         * should not be shown a bookkeeping object as a day of it.
         */
        @Test
        fun `the bucket's consolidation progress decodes as a memory object with no date`() {
            val location = MemoryObjectKeys.locationOf(owner, "${owner}agents/Research/memory/watermark")

            assertNotNull(location, "the sweep that deletes this agent's memory has to be able to name it")
            assertEquals("memory", location!!.segment)
            assertEquals("/watermark", location.itemKey)
            assertNull(MemoryObjectKeys.dateOf(location.itemKey), "so no row of the page ever carries it")
        }
    }

    /**
     * The paths a merge proposal names, resolved to the objects an approval would clear.
     *
     * `MemoryPromoter` writes each source as the route it read it through: `MEMORY.md` for the conversation's
     * own curated draft and `memory/<file>.md` for its ledgers. Those strings are the only way the approval
     * knows which objects the merge actually took material out of, so this resolves exactly them and refuses
     * everything else — a path that resolved to something the merge never read would delete a conversation's
     * memory on the strength of a proposal that does not describe it.
     */
    @Nested
    @DisplayName("Resolving a merge proposal's source paths")
    inner class SourcePaths {

        private fun keyOf(path: String): String? = MemoryObjectKeys.sessionSourceKey(prefix, 4L, "7", "Research", "sess-1", path)

        @Test
        fun `the curated draft path is the conversation's own root object`() {
            assertEquals(
                "store/tenants/4/users/7/agents/Research/sessions/sess-1/root/MEMORY.md",
                keyOf("MEMORY.md"),
            )
        }

        @Test
        fun `a ledger path is the conversation's own memory object`() {
            assertEquals(
                "store/tenants/4/users/7/agents/Research/sessions/sess-1/memory/2026-10-05.md",
                keyOf("memory/2026-10-05.md"),
            )
        }

        /** The same switch the write side reads: off moves the source keys exactly as it moves every other key. */
        @Test
        fun `with the tenant segment off a source key starts at users`() {
            assertEquals(
                "store/users/7/agents/Research/sessions/sess-1/memory/2026-10-05.md",
                MemoryObjectKeys.sessionSourceKey(prefix, 4L, "7", "Research", "sess-1", "memory/2026-10-05.md", tenantScoped = false),
            )
        }

        /**
         * Every spelling the merge does not emit. The first group is the writer's own path under a different
         * shape (a leading slash, a different case, the route name rather than the file), the second reaches
         * to another level of the bucket, and the last two are bookkeeping objects that live in the ledger
         * namespace but hold no conversation memory — clearing them would be a delete no proposal asked for.
         */
        @Test
        fun `a path the merge never writes resolves to nothing`() {
            listOf(
                "",
                " ",
                "/MEMORY.md",
                "MEMORY.md.md",
                "root/MEMORY.md",
                "memory/",
                "memory/../MEMORY.md",
                "memory/2026-10-05.md/extra",
                "memory/archive/2026-01-01.md",
                "notes.md",
                "memory/.consolidation_state",
                "memory/watermark",
            ).forEach { path ->
                assertNull(keyOf(path), "'$path' is not a path the merge emits, so it must not resolve to an object")
            }
        }

        /**
         * A ledger route holds whatever `.md` name the flush wrote under it, and the merge reads every one of
         * those — so the shape rule is the writer's filter, not a list of names that look like dates. A file
         * called `MEMORY.md` under `memory/` is odd but it is one conversation's own object, and refusing it
         * would leave it in the bucket after the approval that describes it.
         */
        @Test
        fun `any markdown file of the ledger route resolves`() {
            assertEquals(
                "store/tenants/4/users/7/agents/Research/sessions/sess-1/memory/MEMORY.md",
                keyOf("memory/MEMORY.md"),
            )
            assertEquals(
                "store/tenants/4/users/7/agents/Research/sessions/sess-1/memory/2026-10-05.md",
                keyOf("memory/2026-10-05.md"),
            )
        }

        /** The two ids a source key is built from are addressable or there is no key to build. */
        @Test
        fun `an unaddressable owner or conversation resolves to nothing`() {
            assertNull(MemoryObjectKeys.sessionSourceKey(prefix, 4L, "7", "Research/x", "sess-1", "MEMORY.md"))
            assertNull(MemoryObjectKeys.sessionSourceKey(prefix, 4L, "7", "Research", "sess-1/2", "MEMORY.md"))
            assertNull(MemoryObjectKeys.sessionSourceKey(prefix, 4L, "7", "Research", "", "MEMORY.md"))
        }
    }
}
