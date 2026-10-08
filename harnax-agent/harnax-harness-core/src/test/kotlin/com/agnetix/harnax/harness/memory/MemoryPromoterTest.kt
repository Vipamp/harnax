package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.agent.adaptor.MemoryDraftAdaptor
import com.agnetix.harnax.agent.adaptor.MemoryDraftIntake
import com.agnetix.harnax.agent.adaptor.MemoryDraftProposal
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
import reactor.core.publisher.Flux

/**
 * What one conversation's promotion pass hands a person to decide (design 11.4).
 *
 * Nothing here writes. The long-term layer moves only when an approval applies a candidate, and the
 * conversation's layer is emptied only by that same approval, so the assertions that matter are the negative
 * ones: after every outcome — filed, refused, unreachable, a failed model, an unreadable store — both layers
 * are byte-identical, versions included.
 *
 * The positive half is about the candidate itself. A reviewer decides the owner's text from
 * [MemoryDraftProposal.mergedMarkdown], and an approval can only be safe to act on while it carries the bytes
 * this pass actually read: [MemoryDraftProposal.baseVersion] says which long-term text the merge was made
 * against, and [MemoryDraftProposal.sources] says which objects of the conversation's layer it may clear.
 * Both are checked against a store that moved underneath the model call, because that window is where a stale
 * candidate would otherwise let an approval overwrite or delete what no merge ever saw.
 */
class MemoryPromoterTest {

    private val tenantId = 4L
    private val owner = "1"
    private val agentId = "Research"
    private val sessionId = "sess-A"

    private fun domain(store: BaseStore) = MemoryDomain(store, tenantId, owner, agentId, true)

    private fun rc() = RuntimeContext.builder().sessionId(sessionId).build()

    /** Every object of one namespace, keyed and stamped, so an attempt can be compared exactly. */
    private fun layer(store: BaseStore, namespace: List<String>): List<String> = store.search(namespace, 100, 0).map { "${it.key()}=${it.value()["content"]}@${it.version()}" }

    private fun sessionNamespaces(store: BaseStore): List<List<String>> = listOf(domain(store).curatedNamespace(sessionId), domain(store).ledgerNamespace(sessionId))

    private fun longTermNamespace(store: BaseStore) = domain(store).curatedNamespace(null)

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
            onAsked?.invoke()
            return Flux.just(
                ChatResponse.builder().content(listOf<ContentBlock>(TextBlock.builder().text(answer).build())).build(),
            )
        }

        /** Runs at the model, after the layer and the owner's text were read: the window a sibling write lands in. */
        var onAsked: (() -> Unit)? = null

        override fun getModelName(): String = "scripted"

        val systemText: String get() = asked.first { it.role == MsgRole.SYSTEM }.textContent
        val userText: String get() = asked.first { it.role == MsgRole.USER }.textContent
    }

    /** A queue that remembers what it was handed and answers with whatever the test needs. */
    private class RecordingAdaptor(
        var intake: MemoryDraftIntake = MemoryDraftIntake.Queued(7L),
    ) : MemoryDraftAdaptor {
        val proposals = mutableListOf<MemoryDraftProposal>()

        override fun propose(proposal: MemoryDraftProposal): MemoryDraftIntake {
            proposals.add(proposal)
            return intake
        }

        val filed: MemoryDraftProposal get() = proposals.single()
    }

    /** One attempt against a queue that accepts, remembering both the answer and the candidate. */
    private class Attempt(val outcome: MemoryPromoter.Outcome, val queue: RecordingAdaptor)

    private fun attempt(
        store: BaseStore,
        model: Model,
        intake: MemoryDraftIntake = MemoryDraftIntake.Queued(7L),
    ): Attempt {
        val queue = RecordingAdaptor(intake)
        val outcome = MemoryPromoter(domain(store), sessionId, model, queue).proposeNow()
        return Attempt(outcome, queue)
    }

    /** Every object of the conversation's layer and of the owner's long-term layer, keyed and stamped. */
    private fun snapshot(store: BaseStore): List<List<String>> = sessionNamespaces(store).map { layer(store, it) } + listOf(layer(store, longTermNamespace(store)))

    /** What no outcome of this pass is allowed to change: both layers, every object, every version. */
    private fun assertNothingMoved(store: BaseStore, before: List<List<String>>) {
        assertEquals(
            before,
            snapshot(store),
            "an approval moves these objects, not a proposal — and a failed attempt must not cost the only copy of what has not been curated",
        )
    }

    @Test
    fun `a merge is filed with the queue and neither layer moves`() {
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeCurated(store, sessionId, "- the user is testing a research agent")
        writeLedger(store, sessionId, "2026-10-04.md", "- started with the arxiv tool")
        val before = snapshot(store)
        val merged = "- prefers Chinese\n- the user is testing a research agent\n- started with the arxiv tool"

        val model = ScriptedModel(merged)
        val run = attempt(store, model)

        assertEquals(MemoryPromoter.Outcome.QUEUED, run.outcome)
        assertEquals(merged, run.queue.filed.mergedMarkdown)
        assertEquals(sessionId, run.queue.filed.sessionId)
        assertEquals(agentId, run.queue.filed.agentName, "the bucket segment is the agent's name, not its id")
        assertNothingMoved(store, before)

        // Both inputs, labelled so the model can tell the owner's text from this conversation's draft.
        assertTrue(model.userText.contains("Current MEMORY.md:\n- prefers Chinese"), model.userText)
        assertTrue(model.userText.contains("- the user is testing a research agent"), model.userText)
        assertTrue(model.userText.contains("### 2026-10-04.md\n- started with the arxiv tool"), model.userText)
        assertTrue(model.userText.contains(sessionId), "the merge input names which conversation it came from")
    }

    @Test
    fun `the candidate carries every object it merged with the bytes it read there`() {
        val store = InMemoryStore()
        writeCurated(store, sessionId, "- the user is testing a research agent")
        writeLedger(store, sessionId, "2026-10-04.md", "- started with the arxiv tool")

        val run = attempt(store, ScriptedModel("- merged"))

        assertEquals(
            listOf(
                "MEMORY.md" to "- the user is testing a research agent",
                "memory/2026-10-04.md" to "- started with the arxiv tool",
            ),
            run.queue.filed.sources.map { it.path to it.content },
            "an approval clears exactly these objects, so it has to name them the way the layer holds them",
        )
    }

    @Test
    fun `the candidate is filed against the long-term text the merge read`() {
        // The merge input is the owner's current MEMORY.md, so the approval that applies it has to say which
        // version of that object it was made against — whoever decides first would otherwise land on the other
        // one's bytes.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val seen = requireNotNull(store.get(longTermNamespace(store), MemoryFilesystemRoutes.CURATED_ITEM_KEY))

        val run = attempt(store, ScriptedModel("- prefers Chinese\n- from this conversation"))

        assertEquals("- prefers Chinese", run.queue.filed.baseMarkdown)
        assertEquals(seen.version(), run.queue.filed.baseVersion)
    }

    @Test
    fun `an owner with nothing curated yet is proposed as a create`() {
        // No long-term object is the ordinary state of a first conversation, and the candidate has to say
        // "there was nothing to merge into" rather than carry a stale text or version.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- the owner wants terse answers")

        val run = attempt(store, ScriptedModel("- terse answers"))

        assertEquals(MemoryPromoter.Outcome.QUEUED, run.outcome)
        assertNull(run.queue.filed.baseMarkdown)
        assertEquals(0L, run.queue.filed.baseVersion, "the version an absent object is proposed against")
        assertNull(domain(store).longTermCurated(), "and it stays absent until somebody approves")
    }

    @Test
    fun `a conversation with nothing of its own proposes nothing`() {
        // The throttle fires on every turn's completion, so the empty case is the common one: it must not spend
        // a model call and must not hand a reviewer a candidate built from an empty layer.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        val model = ScriptedModel("should not be asked")

        val run = attempt(store, model)

        assertEquals(MemoryPromoter.Outcome.NOTHING, run.outcome)
        assertEquals(0, model.calls)
        assertEquals(0, run.queue.proposals.size, "an empty layer files nothing")
    }

    @Test
    fun `a long-term layer that cannot be read is not proposed as an empty one`() {
        // The owner's text is one of the two merge inputs. Reading it as blank would have the model curate a
        // layer down to one conversation's entries, and a reviewer would approve the result as a shrink.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val unreadable = object : BaseStore by store {
            override fun get(namespace: List<String>, key: String): StoreItem? = if (namespace == longTermNamespace(store)) {
                throw IllegalStateException("minio is down")
            } else {
                store.get(namespace, key)
            }
        }

        val run = attempt(unreadable, ScriptedModel("- merged"), MemoryDraftIntake.Queued(1L))

        assertEquals(MemoryPromoter.Outcome.STORE_FAILED, run.outcome)
        assertEquals(0, run.queue.proposals.size, "nothing was merged, so there is nothing to file")
    }

    @Test
    fun `a store that cannot be read at all is not a conversation with no memory`() {
        // A read failure has to look different from an empty layer: the answer the queue would take next is a
        // candidate built out of nothing.
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

        val run = attempt(unreadable, ScriptedModel("- merged"))

        assertEquals(MemoryPromoter.Outcome.STORE_FAILED, run.outcome)
        assertEquals(0, run.queue.proposals.size)
    }

    @Test
    fun `a model that fails leaves the only copy of the draft alone`() {
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeCurated(store, sessionId, "- not curated yet")
        writeLedger(store, sessionId, "2026-10-05.md", "- not merged yet")
        val before = snapshot(store)

        val failing = object : Model {
            override fun stream(
                messages: List<Msg>,
                tools: List<io.agentscope.core.model.ToolSchema>?,
                options: io.agentscope.core.model.GenerateOptions?,
            ): Flux<ChatResponse> = throw IllegalStateException("model is down")

            override fun getModelName(): String = "failing"
        }

        val run = attempt(store, failing)

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, run.outcome)
        assertEquals(0, run.queue.proposals.size)
        assertNothingMoved(store, before)
    }

    @Test
    fun `a model that answers with nothing files nothing`() {
        // An empty completion is a failure, not an instruction to curate the owner's layer away.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val before = snapshot(store)

        val run = attempt(store, ScriptedModel("   "))

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, run.outcome)
        assertEquals(0, run.queue.proposals.size)
        assertNothingMoved(store, before)
    }

    @Test
    fun `a merge that shrinks a curated layer already inside its budget is not filed`() {
        // The prompt asks for the complete new MEMORY.md. A model that answers with only this conversation's
        // entries has failed the task, and a reviewer would approve the collapse thinking it a curation.
        val store = InMemoryStore()
        val curated = "- owner inside budget\n".repeat(20)
        writeLongTerm(store, curated)
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val before = snapshot(store)

        val run = attempt(store, ScriptedModel("- from this conversation"))

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, run.outcome)
        assertEquals(0, run.queue.proposals.size, "a collapse never reaches the queue")
        assertEquals(curated, domain(store).longTermCurated(), "the owner's text is not replaced by a fragment")
        assertNothingMoved(store, before)
    }

    @Test
    fun `a curated layer that overran its budget is still not open to a collapse`() {
        // How big the owner's text is says nothing about what the model may replace it with, and a layer that
        // has drifted past the budget is the one a model answering with a quarter of it looks most like careful
        // curation.
        val store = InMemoryStore()
        val line = "- owner over budget\n"
        val curated = line.repeat(1_200)
        assertTrue(
            curated.length > MemoryConfigFactory.CONSOLIDATION_MAX_TOKENS * 4,
            "the fixture is the over-budget shape the floor used to lift itself for",
        )
        writeLongTerm(store, curated)
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")

        val run = attempt(store, ScriptedModel("- compacted owner"))

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, run.outcome)
        assertEquals(0, run.queue.proposals.size)
        assertEquals(curated, domain(store).longTermCurated(), "the owner keeps all of an oversized layer")
    }

    @Test
    fun `a curated layer that overran its budget is brought back by a halving`() {
        // Curating an oversized layer down is still one candidate's work: half of it is well inside the budget,
        // so the floor costs convergence rather than memory.
        val store = InMemoryStore()
        val line = "- owner over budget\n"
        writeLongTerm(store, line.repeat(1_200))
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val compacted = line.repeat(700).trim()

        val run = attempt(store, ScriptedModel(compacted))

        assertEquals(MemoryPromoter.Outcome.QUEUED, run.outcome)
        assertEquals(compacted, run.queue.filed.mergedMarkdown)
        assertEquals(line.repeat(1_200), domain(store).longTermCurated(), "a halving is a candidate, not a write")
        assertEquals(1, layer(store, domain(store).ledgerNamespace(sessionId)).size, "and the conversation keeps its own copy")
    }

    @Test
    fun `a model that never answers costs the merge and releases the thread`() {
        // This runs on a blocking scheduler with a process-wide in-flight count, so a stream that stops
        // answering has to end on a clock rather than hold the count until the JVM does.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeCurated(store, sessionId, "- not curated yet")
        val before = snapshot(store)
        val silent = object : Model {
            override fun stream(
                messages: List<Msg>,
                tools: List<io.agentscope.core.model.ToolSchema>?,
                options: io.agentscope.core.model.GenerateOptions?,
            ): Flux<ChatResponse> = Flux.never()

            override fun getModelName(): String = "silent"
        }

        val queue = RecordingAdaptor()
        val outcome = MemoryPromoter(domain(store), sessionId, silent, queue, modelTimeout = java.time.Duration.ofMillis(100)).proposeNow()

        assertEquals(MemoryPromoter.Outcome.MODEL_FAILED, outcome)
        assertEquals(0, queue.proposals.size)
        assertNothingMoved(store, before)
    }

    @Test
    fun `what the flush wrote while this merge ran is not in the candidate`() {
        // The daily ledger is written by the flush of every turn, and a later turn of the same conversation can
        // land while this pass is at the model. The candidate carries the bytes this pass read, so the approval
        // that compares them finds a ledger that moved and leaves it for the next window (design 11.4's first
        // rule: this layer holds the only copy of what has not been curated).
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val ledgers = domain(store).ledgerNamespace(sessionId)
        val model = ScriptedModel("- prefers Chinese\n- from this conversation")
        model.onAsked = {
            // upstream reads the file, appends and writes it back unconditionally
            val seen = store.get(ledgers, "/2026-10-05.md")!!.value()["content"]
            store.put(ledgers, "/2026-10-05.md", mapOf("content" to "$seen\n- flushed after this pass read"))
        }

        val run = attempt(store, model)

        assertEquals(MemoryPromoter.Outcome.QUEUED, run.outcome)
        assertEquals(
            "- from this conversation",
            run.queue.filed.sources.single().content,
            "the candidate offers to clear only what this merge saw",
        )
        assertTrue(
            store.get(ledgers, "/2026-10-05.md")!!.value()["content"].toString().contains("- flushed after this pass read"),
            "and the entry no merge saw is still in the bucket",
        )
    }

    @Test
    fun `what a sibling approval wrote while this merge ran is not what the candidate offers to replace`() {
        // Two conversations of one owner propose independently, so an approval can land on the owner's object
        // while this merge is at the model. The candidate keeps the version it read, and the approval that acts
        // on it finds a mismatch and refuses rather than dropping the sibling's entries.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val longTerm = domain(store).curatedNamespace(null)
        val seen = requireNotNull(store.get(longTerm, MemoryFilesystemRoutes.CURATED_ITEM_KEY)).version()
        val model = ScriptedModel("- prefers Chinese\n- from this conversation")
        model.onAsked = { store.put(longTerm, MemoryFilesystemRoutes.CURATED_ITEM_KEY, mapOf("content" to "- approved by a sibling")) }

        val run = attempt(store, model)

        assertEquals(MemoryPromoter.Outcome.QUEUED, run.outcome)
        assertEquals("- prefers Chinese", run.queue.filed.baseMarkdown)
        assertEquals(seen, run.queue.filed.baseVersion)
        assertEquals("- approved by a sibling", domain(store).longTermCurated(), "this pass writes nothing")
    }

    @Test
    fun `only this conversation's daily ledgers are proposed`() {
        // Three things live in the ledger namespace that are not ledger material: the consolidation state file
        // and the bucket's own progress object, and the archive directory upstream moves an expired day into.
        // The last one matters most — an archived entry is history the long-term layer already curates, and a
        // candidate that offered it would have an approval delete the only copy of it.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- today")
        writeLedger(store, sessionId, MemoryConsolidator.STATE_FILE, "2026-10-04T10:00:00Z")
        writeLedger(store, sessionId, "notes.txt", "- not markdown")
        writeLedger(store, sessionId, "archive/2026-08-01.md", "- already archived")
        store.put(domain(store).ledgerNamespace(sessionId), "watermark", mapOf("lastClaimAt" to "2026-10-04T10:00:00Z"))
        val model = ScriptedModel("- merged")

        val run = attempt(store, model)

        assertFalse(model.userText.contains("- not markdown"), model.userText)
        assertFalse(model.userText.contains("- already archived"), model.userText)
        assertEquals(listOf("memory/2026-10-05.md"), run.queue.filed.sources.map { it.path })
        assertEquals(
            5,
            layer(store, domain(store).ledgerNamespace(sessionId)).size,
            "and nothing leaves the bucket on this side",
        )
    }

    @Test
    fun `one conversation's proposal leaves its sibling's layer alone`() {
        val store = InMemoryStore()
        writeCurated(store, "sess-B", "- another conversation's draft")
        writeLedger(store, "sess-B", "2026-10-05.md", "- another conversation's day")
        writeLedger(store, sessionId, "2026-10-05.md", "- this conversation")
        val sibling = sessionNamespacesOf(store, "sess-B")

        assertEquals(MemoryPromoter.Outcome.QUEUED, attempt(store, ScriptedModel("- merged")).outcome)

        assertEquals(sibling, sessionNamespacesOf(store, "sess-B"), "promotion proposes for one conversation, not for the agent")
    }

    @Test
    fun `a queue that cannot be reached keeps everything for the next window`() {
        // An outage is not a decision: the layer stays whole and the next window merges the same material
        // against whatever the owner's text then holds.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeCurated(store, sessionId, "- not curated yet")
        val before = snapshot(store)

        val run = attempt(
            store,
            ScriptedModel("- prefers Chinese\n- not curated yet"),
            MemoryDraftIntake.Unavailable("admin returned code 503"),
        )

        assertEquals(MemoryPromoter.Outcome.UNAVAILABLE, run.outcome)
        assertEquals(1, run.queue.proposals.size, "the candidate was built and handed over, then lost")
        assertNothingMoved(store, before)
    }

    @Test
    fun `a queue that refuses a candidate still does not clear the layer`() {
        // A refusal is product-level — a session admin cannot place, a name that does not match — and the answer
        // here is only to stop proposing it on this conversation's clock. Throwing the layer away on a refusal
        // would lose memory on the strength of a validation error.
        val store = InMemoryStore()
        writeLongTerm(store, "- prefers Chinese")
        writeLedger(store, sessionId, "2026-10-05.md", "- from this conversation")
        val before = snapshot(store)

        val run = attempt(
            store,
            ScriptedModel("- prefers Chinese\n- from this conversation"),
            MemoryDraftIntake.Refused("session not found"),
        )

        assertEquals(MemoryPromoter.Outcome.REFUSED, run.outcome)
        assertNothingMoved(store, before)
    }

    @Test
    fun `the merge carries the two prohibitions of the extraction prompts`() {
        // An approved line becomes durable context for every later conversation of this owner, so the rules
        // that keep another tenant's name or a pasted key out of a ledger apply to the merge as well.
        val store = InMemoryStore()
        writeLedger(store, sessionId, "2026-10-05.md", "- today")
        val model = ScriptedModel("- merged")

        attempt(store, model)

        assertTrue(model.systemText.contains("another user or another tenant"), model.systemText)
        assertTrue(model.systemText.contains("Never record credentials or secrets"), model.systemText)
        assertTrue(
            model.systemText.contains("one conversation's own memory layer"),
            "the merge has to say what its input is, or the model curates a whole conversation away",
        )
    }

    private fun sessionNamespacesOf(store: BaseStore, other: String) = listOf(domain(store).curatedNamespace(other), domain(store).ledgerNamespace(other)).map { layer(store, it) }
}
