package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.MemoryDraft
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `memory_draft` on a real MySQL: the merge target one conversation's repeat proposal relies on, the
 * conditional transition two approvals race on, and the owner predicate the queue screen carries.
 *
 * The service decides what a legal candidate is; this class pins what the storage does with one. Three
 * behaviours are only visible against a real engine: `status = 'PENDING'` inside the UPDATE, which is what
 * makes a second proposal arriving after a decision become a new row instead of reopening the one a person
 * already decided; the newest-touched merge target; and the `user_id` predicate, which is the whole reason a
 * queue of other people's memories cannot be read through this screen.
 *
 * The round trip matters here more than in most tables: `merged_md`, `base_md` and `targets` are the texts the
 * reviewer reads and the approval writes — one candidate goes for the conclusion layer and every daily file it
 * produced — and a resultMap that quietly drops one leaves an approval that writes half of what it showed.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class MemoryDraftMapperTest {

    companion object {
        /** The tenant every row of this class is stored under, except the ones proving both screens are scoped. */
        private const val TENANT = 7L

        /** The owner of the queue this class reads, as `sys_user.id`, which is also the memory bucket segment. */
        private const val OWNER = 42L

        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var mapper: MemoryDraftMapper

    /** Only the table under test is poked through SQL, to date a row to a chosen second. */
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun store(
        sessionId: String,
        tenantId: Long = TENANT,
        userId: Long = OWNER,
        agentName: String = "Research",
        mergedMd: String = "- prefers Chinese",
        baseMd: String? = "- already curated",
        baseVersion: Long = 3L,
        targets: String? = null,
    ): MemoryDraft = MemoryDraft().apply {
        this.tenantId = tenantId
        this.userId = userId
        this.agentName = agentName
        this.sessionId = sessionId
        this.mergedMd = mergedMd
        this.baseMd = baseMd
        this.baseVersion = baseVersion
        sources = """[{"path":"MEMORY.md","content":"- prefers Chinese"}]"""
        this.targets = targets
    }.also { mapper.insert(it) }

    @Test
    @DisplayName("every column a candidate carries survives the round trip")
    fun `a stored candidate reads back whole`() {
        val daily = """[{"path":"memory/2026-10-08.md","expectedVersion":2,"baseText":"- that night","mergedText":"- that night, finished the store"}]"""
        val saved = store("web-round", mergedMd = "# owner layer\n- line two", targets = daily)

        val read = assertNotNull(mapper.selectById(saved.id))

        assertEquals(TENANT, read.tenantId)
        assertEquals(OWNER, read.userId)
        assertEquals("Research", read.agentName, "the bucket segment is an agent name, not its id")
        assertEquals("web-round", read.sessionId)
        assertEquals("# owner layer\n- line two", read.mergedMd, "`merged_md` is what the approval writes; a dropped column means an empty layer")
        assertEquals("- already curated", read.baseMd)
        assertEquals(3L, read.baseVersion, "the precondition the approval applies the candidate against")
        assertEquals("""[{"path":"MEMORY.md","content":"- prefers Chinese"}]""", read.sources)
        assertEquals(daily, read.targets, "the daily texts are objects this same approval writes, so a dropped column means approving only half the candidate")
        assertEquals(MemoryDraft.STATUS_PENDING, read.status, "a proposal never arrives decided")
        assertNull(read.reviewedBy)
        assertNull(read.rejectReason)
        assertNotNull(read.createTime)
    }

    @Test
    @DisplayName("a first merge of an owner with nothing curated keeps both nulls")
    fun `a create-shaped candidate keeps an absent base layer`() {
        val saved = store("web-first", mergedMd = "- first thing curated", baseMd = null, baseVersion = 0L)

        val read = assertNotNull(mapper.selectById(saved.id))

        assertNull(read.baseMd, "null is the difference between \"nothing curated yet\" and \"curated empty\"")
        assertEquals(0L, read.baseVersion, "0 is the version the approval must create against, so it cannot round-trip as null or 1")
        assertEquals("- first thing curated", read.mergedMd, "NOT NULL on the base text says nothing about the candidate text, which is the half that gets written")
    }

    @Test
    @DisplayName("the merge target is this conversation's open candidate only")
    fun `a repeat proposal finds the candidate of its own conversation`() {
        val first = store("web-twice")
        store("web-other")

        val open = assertNotNull(mapper.selectPendingBySession(TENANT, "web-twice"))

        assertEquals(first.id, open.id, "one conversation has one candidate, and a queue of copies of it is a queue nobody reads")
    }

    @Test
    @DisplayName("the merge target skips a candidate somebody already decided")
    fun `a decided candidate is not a merge target`() {
        val decided = store("web-decided")
        mapper.markReviewed(decided.id, MemoryDraft.STATUS_REJECTED, "linqing", "too broad")

        assertNull(mapper.selectPendingBySession(TENANT, "web-decided"))
        val kept = assertNotNull(mapper.selectById(decided.id), "rejecting decides a candidate, it does not delete it")
        assertEquals(MemoryDraft.STATUS_REJECTED, kept.status)
    }

    @Test
    @DisplayName("the merge target is the candidate touched last")
    fun `the most recently patched duplicate wins`() {
        val older = store("web-old")
        val newer = store("web-old")
        // Same second on two inserts, so the tie is broken by hand: the order is what is under test, not the
        // container's clock.
        jdbc.update(
            "UPDATE memory_draft SET update_time = ? WHERE id = ?",
            LocalDateTime.now().minusDays(1),
            older.id,
        )

        assertEquals(newer.id, mapper.selectPendingBySession(TENANT, "web-old")?.id)
    }

    @Test
    @DisplayName("a patch replaces the whole candidate, base version and all")
    fun `updateContent rewrites every column a proposal carries`() {
        val draft = store("web-patched")

        val rows = mapper.updateContent(
            MemoryDraft().apply {
                id = draft.id
                mergedMd = "- prefers Chinese\n- works nights"
                baseMd = null
                baseVersion = 0L
                sources = """[{"path":"memory/2026-10-05.md","content":"- worked nights"}]"""
                targets = """[{"path":"memory/2026-10-05.md","expectedVersion":0,"baseText":"","mergedText":"- worked nights"}]"""
            },
        )

        assertEquals(1, rows)
        val read = assertNotNull(mapper.selectById(draft.id))
        assertEquals("- prefers Chinese\n- works nights", read.mergedMd)
        assertNull(read.baseMd, "a merge against a layer that has since been curated away must not keep the old base text")
        assertEquals(0L, read.baseVersion, "the stale precondition is the bug this column exists to prevent")
        assertEquals("""[{"path":"memory/2026-10-05.md","content":"- worked nights"}]""", read.sources)
        assertEquals(
            """[{"path":"memory/2026-10-05.md","expectedVersion":0,"baseText":"","mergedText":"- worked nights"}]""",
            read.targets,
        )
        assertEquals("web-patched", read.sessionId)
        assertEquals("Research", read.agentName)
        assertEquals(OWNER, read.userId)
        assertEquals(TENANT, read.tenantId)

        // A proposal that merged no daily file has to clear the column, not leave the previous candidate's
        // targets behind: those texts belong to a merge no reviewer of this one read.
        assertEquals(
            1,
            mapper.updateContent(
                MemoryDraft().apply {
                    id = draft.id
                    mergedMd = "- prefers Chinese"
                    baseMd = "- already curated"
                    baseVersion = 4L
                    sources = """[{"path":"MEMORY.md","content":"- prefers Chinese"}]"""
                },
            ),
        )
        assertNull(assertNotNull(mapper.selectById(draft.id)).targets)
    }

    @Test
    @DisplayName("a patch cannot reopen a decided candidate")
    fun `updateContent lands on nothing once the candidate is decided`() {
        val draft = store("web-approved")
        mapper.markReviewed(draft.id, MemoryDraft.STATUS_APPROVED, "linqing")

        val rows = mapper.updateContent(
            MemoryDraft().apply {
                id = draft.id
                mergedMd = "- smuggled in after approval"
            },
        )

        assertEquals(0, rows, "the decision outranks a late proposal")
        assertEquals(MemoryDraft.STATUS_APPROVED, assertNotNull(mapper.selectById(draft.id)).status)
    }

    @Test
    @DisplayName("only the first decision on a candidate wins")
    fun `the second conditional transition changes nothing`() {
        val draft = store("web-race")

        assertEquals(1, mapper.markReviewed(draft.id, MemoryDraft.STATUS_REJECTED, "first", "keep it private"))
        assertEquals(0, mapper.markReviewed(draft.id, MemoryDraft.STATUS_APPROVED, "second"))

        val read = assertNotNull(mapper.selectById(draft.id))
        assertEquals(MemoryDraft.STATUS_REJECTED, read.status, "the first decision stands")
        assertEquals("first", read.reviewedBy)
        assertEquals("keep it private", read.rejectReason)
        assertNotNull(read.reviewedAt)
    }

    @Test
    @DisplayName("approving clears the reason a previous rejection would have left")
    fun `a decision writes both review columns`() {
        val draft = store("web-flip")

        mapper.markReviewed(draft.id, MemoryDraft.STATUS_APPROVED, "linqing")

        val read = assertNotNull(mapper.selectById(draft.id))
        assertNull(read.rejectReason, "an approval carries no reason, so the column must not keep an old one")
        assertEquals("linqing", read.reviewedBy)
    }

    @Test
    @DisplayName("the queue screen is the owner's, and only theirs")
    fun `selectDraftList is owner scoped and dynamically filtered`() {
        store("web-alpha", agentName = "Alpha")
        store("web-beta", agentName = "Beta")
        val rejected = store("web-rejected", agentName = "Alpha")
        mapper.markReviewed(rejected.id, MemoryDraft.STATUS_REJECTED, "linqing", "no")
        val ofNeighbour = store("web-neighbour", userId = 99L)

        val pending = mapper.selectDraftList(TENANT, OWNER, status = MemoryDraft.STATUS_PENDING, agentName = "Alpha")
        assertEquals(listOf("web-alpha"), pending.map { it.sessionId })

        assertEquals(listOf("web-rejected"), mapper.selectDraftList(TENANT, OWNER, status = MemoryDraft.STATUS_REJECTED).map { it.sessionId })
        assertTrue(
            mapper.selectDraftList(TENANT, OWNER).none { it.id == ofNeighbour.id },
            "another person's memory queue is not this screen's to show",
        )
        assertEquals(listOf("web-neighbour"), mapper.selectDraftList(TENANT, 99L).map { it.sessionId })
        assertTrue(
            mapper.selectDraftList(8L, OWNER).isEmpty(),
            "the tenant predicate is not decoration: another workspace cannot read this one's queue by guessing an owner id",
        )
    }

    @Test
    @DisplayName("the screen can be narrowed to one conversation")
    fun `selectDraftList filters by session`() {
        store("web-looked-up")
        store("web-other")

        assertEquals(listOf("web-looked-up"), mapper.selectDraftList(TENANT, OWNER, sessionId = "web-looked-up").map { it.sessionId })
    }
}
