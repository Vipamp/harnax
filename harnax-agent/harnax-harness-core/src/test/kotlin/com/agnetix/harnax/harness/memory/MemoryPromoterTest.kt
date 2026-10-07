package com.agnetix.harnax.harness.memory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.model.ChatResponse
import io.agentscope.core.model.Model
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.agentscope.harness.agent.memory.MemoryConsolidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * The one path that moves a conversation's memory into the layer its owner keeps (design 11.4).
 *
 * The conversation layer holds the only copy of what has not been curated yet, so the assertions that matter
 * here are the negative ones: a conflict, a model error and a refused write each have to leave every object of
 * that layer byte-identical, version included. The success path is checked through the read side a *later*
 * conversation uses — [MemoryDomain.longTermCurated] — rather than against the raw map, because a value this
 * pass writes in a shape the route cannot decode would promote memory nobody can ever read again.
 */
class MemoryPromoterTest {

    private val tenantId = 4L
    private val owner = "1"
    private val agentId = "Research"
    private val sessionId = "sess-A"

    private fun domain(store: BaseStore) = MemoryDomain(store, tenantId, owner, agentId, true)

    private fun rc() = RuntimeContext.builder().sessionId(sessionId).build()

    /** Every object of one namespace, keyed and stamped, so a failed attempt can be compared exactly. */
    private fun layer(store: BaseStore, namespace: List<String>): List<String> = store.search(namespace, 100, 0).map { "${it.key()}=${it.value()["content"]}@${it.version()}" }

    private fun sessionNamespaces(store: BaseStore): List<List<String>> = listOf(domain(store).curatedNamespace(sessionId), domain(store).ledgerNamespace(sessionId))

    private fun writeCurated(store: BaseStore, sessionId: String, text: String) {
        domain(store).routes(sessionId).getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            // The leading slash is what the composite hands a route in production, on both spellings of the
            // path the model uses, so the fixture writes the shape the promotion has to find.
            .write(rc(), MemoryFilesystemRoutes.CURATED_ITEM_KEY, text)
    }

    private fun writeLedger(store: BaseStore, sessionId: String, name: String, text: String) {
        domain(store).routes(sessionId).getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE).write(rc(), "/$name", text)
    }

    private fun writeLongTerm(store: BaseStore, text: String) {
        domain(store).routes().getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc(), MemoryFilesystemRoutes.CURATED_ITEM_KEY, text)
    }

    /** Answers with one fixed merge and keeps what it was asked, so the prompt itself can be asserted. */
    private class ScriptedModel(private val answer: String) : Model {
        val asked = mutableListOf<Msg>()
        var calls = 0

        override fun stream(
            messages: List<Msg>,
            tools: List<io.agentscope.core.model.ToolSchema>?,
            options: io.agentscope.core.model.GenerateOptions?,
        ): Flux<ChatResponse> {
            calls++
            asked.addAll(messages)
            return Flux.just(
                ChatResponse.builder().content(listOf<ContentBlock>(TextBlock.builder().text(answer).build())).build(),
            )
        }

        override fun getModelName(): String = "scripted"

        val systemText: String get() = asked.first { it.role == MsgRole.SYSTEM }.textContent
        val userText: String get() = asked.first { it.role == MsgRole.USER }.textContent
    }

    /** The two merge inputs of one prepared conversation. */
    private fun promoted(store: BaseStore, model: Model): MemoryPromoter.Outcome = MemoryPromoter(domain(store), sessionId, model).promoteNow()

    @Test
    fun `a merge writes the owner's layer and empties the conversation's`() {
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeCurated(store, sessionId, "- the user is testing a research agent")
        writeLedger(store, sessionId, "2026-10-04.md", "- started with the arxiv tool")
        val merged = "- prefers Chinese\n- the user is testing a research agent\n- started with the arxiv tool"

        val model = ScriptedModel(merged)
        assertEquals(MemoryPromoter.Outcome.PROMOTED, promoted(store, model))

        // Read back the way the next conversation reads it: the route has to decode what this pass wrote.
        assertEquals(merged, domain(store).longTermCurated())
        sessionNamespaces(store).forEach {
            assertEquals(emptyList<String>(), layer(store, it), "the layer that was merged has to be empty")
        }

        // Both inputs, labelled so the model can tell the owner's text from this conversation's draft.
        assertTrue(model.userText.contains("Current MEMORY.md:\n- prefers Chinese"), model.userText)
        assertTrue(model.userText.contains("- the user is testing a research agent"), model.userText)
        assertTrue(model.userText.contains("### 2026-10-04.md\n- started with the arxiv tool"), model.userText)
        assertTrue(model.userText.contains(sessionId), "the merge input names which conversation it came from")
    }

    @Test
    fun `an owner with nothing curated yet still takes the merge`() {
        // No long-term object is the ordinary state of a first conversation, and the versioned write has to
        // mean "must not exist yet" there rather than fail forever.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- the owner wants terse answers")

        assertEquals(MemoryPromoter.Outcome.PROMOTED, promoted(store, ScriptedModel("- terse answers")))

        assertEquals("- terse answers", domain(store).longTermCurated())
        assertEquals(emptyList<String>(), layer(store, domain(store).ledgerNamespace(sessionId)))
    }

    @Test
    fun `a conversation with nothing of its own promotes nothing`() {
        // The throttle fires on every turn's completion, so the empty case is the common one and it must not
        // spend a model call, rewrite the owner's text or bump its version.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        val longTerm = layer(store, domain(store).curatedNamespace(null))
        val model = ScriptedModel("should not be asked")

        assertEquals(MemoryPromoter.Outcome.NOTHING, promoted(store, model))

        assertEquals(0, model.calls)
        assertEquals(longTerm, layer(store, domain(store).curatedNamespace(null)))
    }

    @Test
    fun `a sibling merge that lands first costs this conversation nothing`() {
        // Design 11.4's concurrency rule: the long-term write is a compare-and-swap, and on a mismatch this
        // pass gives up instead of hard-writing — a last-write-wins merge would drop whatever the sibling
        // just added to the one object this pass exists to grow.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val before = sessionNamespaces(store).map { layer(store, it) }

        val interleaved = object : BaseStore by store {
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ): Boolean {
                // The sibling commits between this pass's read and its write.
                store.put(namespace, key, mapOf("content" to "- merged by a sibling"))
                return store.putIfVersion(namespace, key, value, expectedVersion)
            }
        }

        assertEquals(
            MemoryPromoter.Outcome.CONFLICT,
            MemoryPromoter(domain(interleaved), sessionId, ScriptedModel("- would have been dropped")).promoteNow(),
        )

        assertEquals(before, sessionNamespaces(interleaved).map { layer(interleaved, it) }, "the draft survives to merge next turn")
        assertEquals("- merged by a sibling", domain(interleaved).longTermCurated())
    }

    @Test
    fun `an entry the flush wrote while this merge ran is not cleared with the rest`() {
        // The daily ledger is written by the flush of every turn, and a later turn of the same conversation can
        // land while this pass is at the model. Clearing the file because this pass read it once would destroy
        // an entry that was never merged, and this layer holds the only copy of it (design 11.4's first rule).
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val ledgers = domain(store).ledgerNamespace(sessionId)

        val interleaved = object : BaseStore by store {
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ): Boolean {
                // The turn that started after this pass read flushes its own entry into the same day: upstream
                // reads the file, appends and writes it back unconditionally.
                val seen = store.get(ledgers, "/2026-10-05.md")!!.value()["content"]
                store.put(ledgers, "/2026-10-05.md", mapOf("content" to "$seen\n- flushed after this pass read"))
                return store.putIfVersion(namespace, key, value, expectedVersion)
            }
        }

        assertEquals(
            MemoryPromoter.Outcome.PROMOTED,
            MemoryPromoter(
                domain(interleaved),
                sessionId,
                ScriptedModel("- prefers Chinese\n- from this conversation"),
            ).promoteNow(),
        )

        // What this pass merged went to the owner's layer, and what it never saw is still here for the next one.
        val surviving = layer(interleaved, ledgers)
        assertEquals(1, surviving.size, "a ledger that took a write during the merge keeps its place: $surviving")
        assertTrue(surviving.single().contains("- flushed after this pass read"), surviving.toString())
        assertTrue(surviving.single().contains("- from this conversation"), surviving.toString())
    }

    @Test
    fun `a draft that was rewritten while this merge ran is not cleared with the rest`() {
        // The same window on the other object of the layer: upstream's consolidation rewrites this conversation's
        // MEMORY.md, and a draft that moved since this pass read it is not this pass's to delete.
        val store = InMemoryStore()
        writeCurated(store, sessionId, "- first draft")
        val draft = domain(store).curatedNamespace(sessionId)

        val interleaved = object : BaseStore by store {
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ): Boolean {
                store.put(draft, MemoryFilesystemRoutes.CURATED_ITEM_KEY, mapOf("content" to "- rewritten while the merge ran"))
                return store.putIfVersion(namespace, key, value, expectedVersion)
            }
        }

        assertEquals(
            MemoryPromoter.Outcome.PROMOTED,
            MemoryPromoter(domain(interleaved), sessionId, ScriptedModel("- prefers Chinese\n- first draft")).promoteNow(),
        )

        val surviving = layer(interleaved, draft)
        assertEquals(1, surviving.size, "a draft that took a write during the merge keeps its place: $surviving")
        assertTrue(
            surviving.single().contains("- rewritten while the merge ran"),
            "the draft this pass did not merge is left for the next one: $surviving",
        )
    }

    @Test
    fun `a model that fails leaves the only copy of the draft alone`() {
        val store = InMemoryStore()
        writeCurated(store, sessionId, "- not curated yet")
        writeLedger(store, sessionId, "2026-10-05.md", "- not merged yet")
        val before = sessionNamespaces(store).map { layer(store, it) }

        val failing = object : Model {
            override fun stream(
                messages: List<Msg>,
                tools: List<io.agentscope.core.model.ToolSchema>?,
                options: io.agentscope.core.model.GenerateOptions?,
            ): Flux<ChatResponse> = throw IllegalStateException("model is down")

            override fun getModelName(): String = "failing"
        }

        assertEquals(
            MemoryPromoter.Outcome.MODEL_FAILED,
            MemoryPromoter(domain(store), sessionId, failing).promoteNow(),
        )

        assertEquals(before, sessionNamespaces(store).map { layer(store, it) })
        assertNull(domain(store).longTermCurated())
    }

    @Test
    fun `a model that answers with nothing never overwrites the owner's memory`() {
        // An empty completion is a failure, not a curate-everything-away instruction: writing it would empty
        // the one block every later conversation reads, from a conversation that had plenty to keep.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val longTerm = layer(store, domain(store).curatedNamespace(null))

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, promoted(store, ScriptedModel("   ")))

        assertEquals(longTerm, layer(store, domain(store).curatedNamespace(null)))
        assertEquals(1, layer(store, domain(store).ledgerNamespace(sessionId)).size)
    }

    @Test
    fun `a merge that shrinks a curated layer already inside its budget is refused`() {
        // The prompt asks for the complete new MEMORY.md, and this pass overwrites the one block every later
        // conversation reads and then deletes this conversation's copy of what it dropped. A model that answers
        // with only this conversation's entries has failed the task, so it is handled as one.
        val store = InMemoryStore()
        val curated = "- owner inside budget\n".repeat(20)
        writeLongTerm(store, curated)
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val before = sessionNamespaces(store).map { layer(store, it) }

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, promoted(store, ScriptedModel("- from this conversation")))

        assertEquals(curated, domain(store).longTermCurated(), "the owner's text is not replaced by a fragment")
        assertEquals(before, sessionNamespaces(store).map { layer(store, it) }, "nothing is cleared on a refused merge")
    }

    @Test
    fun `a curated layer that overran its budget is still not open to a collapse`() {
        // How big the owner's text is says nothing about what the model may replace it with: this pass deletes
        // the conversation's copy of whatever it drops, and a layer that has drifted past the budget is the one
        // a model answering with a quarter of it looks most like careful curation.
        val store = InMemoryStore()
        val line = "- owner over budget\n"
        val curated = line.repeat(1_200)
        assertTrue(
            curated.length > MemoryConfigFactory.CONSOLIDATION_MAX_TOKENS * 4,
            "the fixture is the over-budget shape the floor used to lift itself for",
        )
        writeLongTerm(store, curated)
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val before = sessionNamespaces(store).map { layer(store, it) }

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, promoted(store, ScriptedModel("- compacted owner")))

        assertEquals(curated, domain(store).longTermCurated(), "the owner keeps all of an oversized layer")
        assertEquals(before, sessionNamespaces(store).map { layer(store, it) }, "and a refused compact clears nothing")
    }

    @Test
    fun `a curated layer that overran its budget is brought back by a halving`() {
        // Curating an oversized layer down is still one window's work: half of it is well inside the budget,
        // so the floor costs convergence rather than memory.
        val store = InMemoryStore()
        val line = "- owner over budget\n"
        writeLongTerm(store, line.repeat(1_200))
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val compacted = line.repeat(700).trim()

        assertEquals(MemoryPromoter.Outcome.PROMOTED, promoted(store, ScriptedModel(compacted)))

        assertEquals(compacted, domain(store).longTermCurated())
        assertEquals(emptyList<String>(), layer(store, domain(store).ledgerNamespace(sessionId)))
    }

    @Test
    fun `a model that never answers costs the merge and releases the thread`() {
        // This runs on a blocking scheduler with a process-wide in-flight count, so a stream that stops
        // answering has to end on a clock rather than hold the count until the JVM does.
        val store = InMemoryStore()
        writeCurated(store, sessionId, "- not curated yet")
        val before = sessionNamespaces(store).map { layer(store, it) }
        val silent = object : Model {
            override fun stream(
                messages: List<Msg>,
                tools: List<io.agentscope.core.model.ToolSchema>?,
                options: io.agentscope.core.model.GenerateOptions?,
            ): Flux<ChatResponse> = Flux.never()

            override fun getModelName(): String = "silent"
        }

        assertEquals(
            MemoryPromoter.Outcome.MODEL_FAILED,
            MemoryPromoter(
                domain(store),
                sessionId,
                silent,
                modelTimeout = java.time.Duration.ofMillis(100),
            ).promoteNow(),
        )

        assertEquals(before, sessionNamespaces(store).map { layer(store, it) })
        assertNull(domain(store).longTermCurated())
    }

    @Test
    fun `a store that refuses the versioned write leaves the layer intact`() {
        val store = InMemoryStore()
        writeCurated(store, sessionId, "- not curated yet")
        val before = sessionNamespaces(store).map { layer(store, it) }

        val refusing = object : BaseStore by store {
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ): Boolean = throw IllegalStateException("minio returned 503")
        }

        assertEquals(
            MemoryPromoter.Outcome.STORE_FAILED,
            MemoryPromoter(domain(refusing), sessionId, ScriptedModel("- merged")).promoteNow(),
        )

        assertEquals(before, sessionNamespaces(refusing).map { layer(refusing, it) })
    }

    @Test
    fun `a store that cannot be read is not a conversation with no memory`() {
        // A read failure has to look different from an empty layer: clearing on the strength of a listing that
        // never answered is how this path deletes the draft it is supposed to protect.
        val unreadable = object : BaseStore {
            override fun get(namespace: List<String>, key: String): StoreItem = throw IllegalStateException("minio is down")

            override fun put(namespace: List<String>, key: String, value: Map<String, Any>) = Unit
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ) = true

            override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> = throw IllegalStateException("minio is down")

            override fun delete(namespace: List<String>, key: String) = throw AssertionError("nothing may be cleared")
        }

        assertEquals(
            MemoryPromoter.Outcome.STORE_FAILED,
            MemoryPromoter(domain(unreadable), sessionId, ScriptedModel("- merged")).promoteNow(),
        )
    }

    @Test
    fun `only this conversation's daily ledgers are merged`() {
        // Three things live in the ledger namespace that are not ledger material: the consolidation state file
        // and the bucket's own progress object, and the archive directory upstream moves an expired day into.
        // The last one matters most — it is the only copy of an old day, and merging it would delete it.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- today")
        writeLedger(store, sessionId, MemoryConsolidator.STATE_FILE, "2026-10-04T10:00:00Z")
        writeLedger(store, sessionId, "notes.txt", "- not markdown")
        writeLedger(store, sessionId, "archive/2026-08-01.md", "- already archived")
        store.put(domain(store).ledgerNamespace(sessionId), "watermark", mapOf("lastClaimAt" to "2026-10-04T10:00:00Z"))
        val ledgerNs = domain(store).ledgerNamespace(sessionId)

        val model = ScriptedModel("- merged")
        assertEquals(MemoryPromoter.Outcome.PROMOTED, promoted(store, model))

        assertFalse(model.userText.contains("- not markdown"), model.userText)
        assertFalse(model.userText.contains("- already archived"), model.userText)
        val remaining = layer(store, ledgerNs).map { it.substringBefore("=") }
        assertTrue(remaining.containsAll(listOf("/.consolidation_state", "/archive/2026-08-01.md", "watermark")), "$remaining")
        assertFalse(remaining.contains("/2026-10-05.md"), "the day that was merged is the day that goes: $remaining")
    }

    @Test
    fun `one conversation's promotion leaves its sibling's layer alone`() {
        // The clear is scoped to the conversation being merged, and every conversation of this owner is
        // extracting into a bucket of its own at the same time.
        val store = InMemoryStore()
        writeCurated(store, "sess-B", "- another conversation's draft")
        writeLedger(store, "sess-B", "2026-10-05.md", "- another conversation's day")
        writeLedger(store, sessionId, "2026-10-05.md", "- this conversation")
        val sibling = sessionNamespacesOf(store, "sess-B")

        assertEquals(MemoryPromoter.Outcome.PROMOTED, promoted(store, ScriptedModel("- merged")))

        assertEquals(sibling, sessionNamespacesOf(store, "sess-B"), "promotion clears one conversation, not the agent")
    }

    @Test
    fun `the merge carries the two prohibitions of the extraction prompts`() {
        // A promoted line becomes durable context for every later conversation of this owner, so the rules
        // that keep another tenant's name or a pasted key out of a ledger apply to the merge as well.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- today")
        val model = ScriptedModel("- merged")

        promoted(store, model)

        assertTrue(model.systemText.contains("another user or another tenant"), model.systemText)
        assertTrue(model.systemText.contains("Never record credentials or secrets"), model.systemText)
        assertTrue(
            model.systemText.contains("one conversation's own memory layer"),
            "the merge has to say what its input is, or the model curates a whole conversation away",
        )
    }

    @Test
    fun `an object holding other bytes is not cleared because its number did not move`() {
        // The version a store stamps is not the identity of what it holds: an unguarded write can land any
        // bytes on any number, and this pass is about to remove the only copy of what it believes it merged.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val ledgers = domain(store).ledgerNamespace(sessionId)
        val seen = store.get(ledgers, "/2026-10-05.md")!!
        var rewritten = false
        val lying = object : BaseStore by store {
            override fun get(namespace: List<String>, key: String): StoreItem? = if (rewritten && namespace == ledgers && key == "/2026-10-05.md") {
                StoreItem(key, mapOf("content" to "- bytes no merge ever saw"), seen.version())
            } else {
                store.get(namespace, key)
            }

            // A Kotlin class that implements a Java interface by delegation does not inherit that interface's
            // default methods, and this one's default answers false: without the forwarding the owner's write
            // is a conflict before the clean-up step under test is ever reached. Flipping the object's body on
            // the same number is the unguarded sibling write the version comparison was never able to see.
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ): Boolean {
                val written = store.putIfVersion(namespace, key, value, expectedVersion)
                rewritten = written
                return written
            }
        }

        assertEquals(
            MemoryPromoter.Outcome.PROMOTED,
            MemoryPromoter(domain(lying), sessionId, ScriptedModel("- from this conversation")).promoteNow(),
        )

        assertEquals(
            listOf("/2026-10-05.md=- from this conversation@${seen.version()}"),
            layer(store, ledgers),
            "a ledger whose body moved is left for the next window even when its version reads the same",
        )
    }

    @Test
    fun `a delete the store swallows is not counted as cleared`() {
        // The object store this runs against answers a failed delete with a log line of its own and no error,
        // and how much of the layer left is the one signal that says whether this pass did what it reported.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val ignoring = object : BaseStore by store {
            override fun delete(namespace: List<String>, key: String) = Unit

            // Not forwarded by the delegation, and the interface's default answers false: without this the pass
            // never gets past its own write to the delete it is being tested on.
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ): Boolean = store.putIfVersion(namespace, key, value, expectedVersion)
        }

        val lines = reporting {
            assertEquals(
                MemoryPromoter.Outcome.PROMOTED,
                MemoryPromoter(domain(ignoring), sessionId, ScriptedModel("- merged")).promoteNow(),
            )
        }

        assertTrue(
            lines.any { it.contains("Promoted conversation '$sessionId'") && it.contains("0 of 1 layer object(s) cleared") },
            "a merge that drained nothing must not report one: $lines",
        )
        assertEquals(1, layer(store, domain(store).ledgerNamespace(sessionId)).size, "the object is still in the bucket")
    }

    private fun sessionNamespacesOf(store: BaseStore, other: String) = listOf(domain(store).curatedNamespace(other), domain(store).ledgerNamespace(other)).map { layer(store, it) }

    /** [block] with every INFO and WARN the promotion pass emits while it runs. */
    private fun reporting(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(MemoryPromoter::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.INFO
        try {
            block()
            return appender.list.filter { it.level == Level.INFO || it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }
}
