package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillDraft
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
 * `skill_draft` on a real MySQL: the merge read an agent's repeated patch relies on, and the conditional
 * transition two reviewers race each other on.
 *
 * The service decides what a legal proposal is; this class pins what the storage does with one. Three
 * behaviours are only visible against a real engine: `status = 'PENDING'` inside the UPDATE, which is what
 * makes the second reviewer's write land on nothing instead of reopening a decided draft; the
 * newest-touched merge target; and the tenant predicate the queue screen is the only read carrying.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class SkillDraftMapperTest {

    companion object {
        /** The tenant every row of this class is stored under, except the one proving the queue is scoped. */
        private const val TENANT = 7L

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
    private lateinit var mapper: SkillDraftMapper

    /** Only the table under test is poked through SQL, to date a row to a chosen second. */
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun store(
        name: String,
        tenantId: Long = TENANT,
        sessionId: String = "web-merge",
        skillmd: String = "# $name",
    ): SkillDraft = SkillDraft().apply {
        this.tenantId = tenantId
        this.name = name
        description = "proposed by an agent"
        this.skillmd = skillmd
        resources = """{"scripts/run.sh":"echo hi"}"""
        scriptPreviews = """[{"relPath":"scripts/run.sh","sha256":"ab"}]"""
        scanVerdict = "CAUTION"
        scanFindings = """[{"rule":"rm-rf"}]"""
        sourceSessionId = sessionId
        agentId = 3L
    }.also { mapper.insert(it) }

    @Test
    @DisplayName("every column the candidate carries survives the round trip")
    fun `a stored draft reads back whole`() {
        val saved = store("merge-target", skillmd = "# body\nline two")

        val read = assertNotNull(mapper.selectById(saved.id))

        assertEquals(TENANT, read.tenantId)
        assertEquals("merge-target", read.name)
        assertEquals("# body\nline two", read.skillmd, "`skillmd` has no underscore, so a resultMap slip is silent")
        assertEquals("""{"scripts/run.sh":"echo hi"}""", read.resources)
        assertEquals("""[{"relPath":"scripts/run.sh","sha256":"ab"}]""", read.scriptPreviews)
        assertEquals("CAUTION", read.scanVerdict)
        assertEquals("web-merge", read.sourceSessionId)
        assertEquals(3L, read.agentId)
        assertEquals(SkillDraft.STATUS_PENDING, read.status, "a submit never arrives decided")
        assertNull(read.reviewedBy)
        assertNull(read.rejectReason)
    }

    @Test
    @DisplayName("the merge target skips a draft somebody already decided")
    fun `a decided draft is not a merge target`() {
        val decided = store("gone-decided")
        mapper.markReviewed(decided.id, SkillDraft.STATUS_REJECTED, "linqing", "not useful")

        assertNull(mapper.selectPendingByTenantAndName(TENANT, "gone-decided"))
        val kept = assertNotNull(mapper.selectById(decided.id), "rejecting decides a draft, it does not delete it")
        assertEquals(SkillDraft.STATUS_REJECTED, kept.status)
    }

    @Test
    @DisplayName("the merge target is the draft the agent touched last")
    fun `the most recently patched duplicate wins`() {
        val older = store("twice", sessionId = "web-1")
        val newer = store("twice", sessionId = "web-2")
        // Same second on two inserts, so the tie is broken by hand: the order is what is under test, not the
        // container's clock.
        jdbc.update(
            "UPDATE skill_draft SET update_time = ? WHERE id = ?",
            LocalDateTime.now().minusDays(1),
            older.id,
        )

        assertEquals(newer.id, mapper.selectPendingByTenantAndName(TENANT, "twice")?.id)
    }

    @Test
    @DisplayName("a patch replaces the whole body of an open draft")
    fun `updateContent rewrites every proposed column`() {
        val draft = store("patched")

        val rows = mapper.updateContent(
            SkillDraft().apply {
                id = draft.id
                description = "rewritten"
                skillmd = "# v2"
                resources = "{}"
                scriptPreviews = "[]"
                scanVerdict = "SAFE"
                scanFindings = "[]"
            },
        )

        assertEquals(1, rows)
        val read = assertNotNull(mapper.selectById(draft.id))
        assertEquals("rewritten", read.description)
        assertEquals("# v2", read.skillmd)
        assertEquals("{}", read.resources, "files the new body does not have must not survive the patch")
        assertEquals("[]", read.scriptPreviews)
        assertEquals("SAFE", read.scanVerdict)
        assertEquals("[]", read.scanFindings, "a preview of the previous patch must not be left beside the new body")
        assertEquals("patched", read.name, "a patch rewrites the body, never the name the merge and the queue screen use")
        assertEquals("web-merge", read.sourceSessionId)
        assertEquals(TENANT, read.tenantId)
    }

    @Test
    @DisplayName("a patch cannot reopen a decided draft")
    fun `updateContent lands on nothing once the draft is decided`() {
        val draft = store("decided-first")
        mapper.markReviewed(draft.id, SkillDraft.STATUS_APPROVED, "linqing")

        val rows = mapper.updateContent(
            SkillDraft().apply {
                id = draft.id
                skillmd = "# smuggled in after approval"
            },
        )

        assertEquals(0, rows, "the decision outranks a late patch")
        assertEquals(SkillDraft.STATUS_APPROVED, assertNotNull(mapper.selectById(draft.id)).status)
    }

    @Test
    @DisplayName("only the first reviewer of a draft gets a row")
    fun `the second conditional transition changes nothing`() {
        val draft = store("race")

        assertEquals(1, mapper.markReviewed(draft.id, SkillDraft.STATUS_REJECTED, "first", "too broad"))
        assertEquals(0, mapper.markReviewed(draft.id, SkillDraft.STATUS_APPROVED, "second"))

        val read = assertNotNull(mapper.selectById(draft.id))
        assertEquals(SkillDraft.STATUS_REJECTED, read.status, "the first decision stands")
        assertEquals("first", read.reviewedBy)
        assertEquals("too broad", read.rejectReason)
        assertNotNull(read.reviewedAt)
    }

    @Test
    @DisplayName("approving clears the reason a previous rejection would have left")
    fun `a decision writes both review columns`() {
        val draft = store("flipped")

        mapper.markReviewed(draft.id, SkillDraft.STATUS_APPROVED, "linqing")

        val read = assertNotNull(mapper.selectById(draft.id))
        assertNull(read.rejectReason, "an approval carries no reason, so the column must not keep an old one")
        assertEquals("linqing", read.reviewedBy)
    }

    @Test
    @DisplayName("the queue screen filters by status and name inside one tenant only")
    fun `selectDraftList is tenant scoped and dynamically filtered`() {
        store("alpha-one")
        store("alpha-two")
        val other = store("beta-other-tenant", tenantId = 8L)
        val rejected = store("alpha-rejected")
        mapper.markReviewed(rejected.id, SkillDraft.STATUS_REJECTED, "linqing", "no")

        val pending = mapper.selectDraftList(TENANT, status = SkillDraft.STATUS_PENDING, name = "alpha")
        assertEquals(listOf("alpha-two", "alpha-one"), pending.map { it.name })

        assertEquals(listOf("alpha-rejected"), mapper.selectDraftList(TENANT, status = SkillDraft.STATUS_REJECTED).map { it.name })
        assertTrue(
            mapper.selectDraftList(TENANT).none { it.id == other.id },
            "another tenant's queue is not this screen's to show",
        )
        assertEquals(listOf("beta-other-tenant"), mapper.selectDraftList(8L).map { it.name })
    }
}
