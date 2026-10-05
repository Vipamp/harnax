package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.SkillUsage
import com.agnetix.harnax.entity.dto.SkillUsageAggregate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the skill usage stream.
 *
 * The three things worth pinning down here are all properties of the one aggregate statement, not of
 * Java code: an event is counted for the tenant that raised it, a skill with no events still comes
 * back at zero, and the window only ever moves the counts — never which skills are listed.
 *
 * @author agnetix
 * @since 2026-10-05
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class SkillUsageMapperTest {

    companion object {
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
    private lateinit var skillUsageMapper: SkillUsageMapper

    @Autowired
    private lateinit var skillMapper: SkillMapper

    @Autowired
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    /** Tenant 1's seeded skills are ids 1 (web-search), 2 (code-review) and 3 (data-analysis), all public. */
    private fun rows(
        tenantId: Long,
        since: LocalDateTime,
        builtinRepositoryId: Long? = null,
        viewer: String? = "admin",
    ) = skillUsageMapper.selectUsageByTenant(tenantId, since, builtinRepositoryId, viewer)

    private fun rowOf(result: List<SkillUsageAggregate>, skillId: Long) = result.first { it.skillId == skillId }

    private fun recordEvent(
        skillId: Long,
        tenantId: Long,
        kind: String,
        at: LocalDateTime,
    ): Int = skillUsageMapper.insert(
        SkillUsage().apply {
            this.tenantId = tenantId
            this.skillId = skillId
            this.event = kind
            this.sessionId = "web-usage-it"
            this.occurredAt = at
        },
    )

    /**
     * A skill of another tenant living in a repository of its own, which is what the builtin exemption
     * is for: a row every tenant may load, so its counts must not collapse into one global number.
     */
    private fun sharedSkill(): Skill {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val repository = SkillRepository().apply {
            tenantId = 2L
            name = "Usage Shared Repo"
            url = "https://example.invalid/usage-shared"
            sourceType = "GIT"
            description = "Stands in for the platform builtin repository"
            status = 1
            isPublic = 1
            creator = "user2"
            active = 1
            createTime = now
            updateTime = now
        }
        skillRepositoryMapper.insert(repository)
        val skill = Skill().apply {
            tenantId = 2L
            name = "shared-usage-skill"
            repositoryId = repository.id
            description = "Owned by tenant 2, loaded by both"
            skillmd = "# Shared"
            resources = "{}"
            version = "1.0.0"
            status = 1
            isPublic = 1
            creator = "user2"
            active = 1
            origin = Skill.ORIGIN_AGENT_PROMOTED
            createTime = now
            updateTime = now
        }
        skillMapper.insert(skill)
        return skill
    }

    /**
     * A private skill of tenant 1 belonging to [creator], which is the row the skill list shows to that
     * user alone. The usage aggregate reads `skill` itself, so it has to reach the same answer.
     */
    private fun privateSkillOf(creator: String): Skill {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val skill = Skill().apply {
            tenantId = 1L
            name = "private-of-$creator"
            repositoryId = 1L
            description = "Belongs to one user of this tenant"
            skillmd = "# Private"
            resources = "{}"
            version = "1.0.0"
            status = 1
            isPublic = 0
            this.creator = creator
            active = 1
            origin = Skill.ORIGIN_HUMAN
            createTime = now
            updateTime = now
        }
        skillMapper.insert(skill)
        return skill
    }

    @Nested
    @DisplayName("Event intake")
    inner class IntakeTests {

        @Test
        @DisplayName("insert - persists one event and gives it an id")
        fun `insert should persist an event and stamp its id`() {
            val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            assertEquals(1, recordEvent(skillId = 1L, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at))

            val row = rowOf(rows(1L, at.minusSeconds(1)), 1L)
            assertEquals(1, row.viewCount)
            assertEquals(0, row.useCount)
            assertEquals(at, row.lastUsedAt)
        }

        @Test
        @DisplayName("selectUsageByTenant - lists every visible skill at zero when nothing was loaded")
        fun `selectUsageByTenant should list unused skills at zero`() {
            val result = rows(1L, LocalDateTime.now().minusDays(30))

            assertEquals(3, result.size)
            result.forEach {
                assertEquals(0, it.viewCount)
                assertEquals(0, it.useCount)
                assertNull(it.lastUsedAt)
            }
        }

        @Test
        @DisplayName("selectUsageByTenant - keeps another tenant's skills out without an exemption")
        fun `selectUsageByTenant should exclude other tenants without the exemption`() {
            val result = rows(1L, LocalDateTime.now().minusDays(30))

            assertFalse(result.any { it.name == "tenant2-skill" })
            assertTrue(result.all { it.skillId in setOf(1L, 2L, 3L) })
        }
    }

    @Nested
    @DisplayName("Per-tenant counting of a shared skill")
    inner class SharedSkillTests {

        @Test
        @DisplayName("a skill both tenants load answers with each tenant's own counts")
        fun `a shared skill should count each tenant separately`() {
            val skill = sharedSkill()
            val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            recordEvent(skill.id, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at)
            recordEvent(skill.id, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at)
            recordEvent(skill.id, tenantId = 2L, kind = SkillUsage.EVENT_USE, at = at)

            val tenant1 = rowOf(rows(1L, at.minusSeconds(1), skill.repositoryId), skill.id)
            assertEquals(2, tenant1.viewCount)
            assertEquals(0, tenant1.useCount, "tenant 2's USE must not land on tenant 1's row")

            val tenant2 = rowOf(rows(2L, at.minusSeconds(1), skill.repositoryId), skill.id)
            assertEquals(0, tenant2.viewCount)
            assertEquals(1, tenant2.useCount)
        }

        @Test
        @DisplayName("without a builtin repository id the shared skill is not this tenant's to report")
        fun `the shared skill should be absent when no builtin repository is given`() {
            val skill = sharedSkill()
            val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            recordEvent(skill.id, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at)

            val result = rows(1L, at.minusSeconds(1), builtinRepositoryId = null)

            assertFalse(result.any { it.skillId == skill.id })
        }

        @Test
        @DisplayName("the projection carries origin so a page can tell an agent's skill from a human's")
        fun `the projection should carry origin`() {
            val skill = sharedSkill()

            val row = rowOf(rows(1L, LocalDateTime.now().minusDays(30), skill.repositoryId), skill.id)

            assertEquals(Skill.ORIGIN_AGENT_PROMOTED, row.origin)
        }
    }

    @Nested
    @DisplayName("Who the aggregate is read for")
    inner class ViewerScopeTests {

        @Test
        @DisplayName("another user's private skill stays out even when it was loaded")
        fun `a private skill of another user should stay out`() {
            val skill = privateSkillOf(creator = "colleague")
            val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            recordEvent(skill.id, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at)

            val result = rows(1L, at.minusSeconds(1))

            // The list hides this row from "admin", so the analytics page must not be the place where its
            // name and its counts appear anyway; counts are not a reason to widen the read.
            assertFalse(result.any { it.skillId == skill.id }, "a same-tenant private skill leaked: $result")
        }

        @Test
        @DisplayName("its creator's own read lists the private skill, at zero when nothing was loaded")
        fun `the creator should see their own private skill`() {
            val skill = privateSkillOf(creator = "owner")

            val row = rowOf(rows(1L, LocalDateTime.now().minusDays(30), viewer = "owner"), skill.id)

            assertEquals(0, row.viewCount)
            assertEquals(0, row.useCount)
        }

        @Test
        @DisplayName("a read naming nobody reports only the public skills")
        fun `a null viewer should read only public skills`() {
            val skill = privateSkillOf(creator = "owner")

            val result = rows(1L, LocalDateTime.now().minusDays(30), viewer = null)

            assertTrue(result.all { it.skillId != skill.id })
            assertEquals(3, result.size, "the seeded public skills are the whole answer: $result")
        }
    }

    @Nested
    @DisplayName("Window and scope")
    inner class WindowTests {

        @Test
        @DisplayName("events before the window start are ignored but the skill stays listed at zero")
        fun `events outside the window should leave the skill at zero`() {
            val old = LocalDateTime.now().minusDays(45).truncatedTo(ChronoUnit.SECONDS)
            recordEvent(skillId = 1L, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = old)

            val narrow = rowOf(rows(1L, LocalDateTime.now().minusDays(30)), 1L)
            assertEquals(0, narrow.viewCount)
            assertNull(narrow.lastUsedAt, "an out-of-window event must not look like recent activity")

            val wide = rowOf(rows(1L, LocalDateTime.now().minusDays(60)), 1L)
            assertEquals(1, wide.viewCount)
            assertEquals(old, wide.lastUsedAt)
        }

        @Test
        @DisplayName("lastUsedAt is the newest event of either kind inside the window")
        fun `lastUsedAt should be the newest event`() {
            val earlier = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS)
            val later = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS)
            recordEvent(skillId = 1L, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = earlier)
            recordEvent(skillId = 1L, tenantId = 1L, kind = SkillUsage.EVENT_USE, at = later)

            val row = rowOf(rows(1L, LocalDateTime.now().minusDays(30)), 1L)

            assertEquals(later, row.lastUsedAt)
            assertEquals(1, row.viewCount)
            assertEquals(1, row.useCount)
        }

        @Test
        @DisplayName("a logically deleted skill is never reported, events or not")
        fun `a deleted skill should never be reported`() {
            // Skill 4 is seeded with active = 0; its events would otherwise keep a removed skill on the page
            val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            recordEvent(skillId = 4L, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at)

            val result = rows(1L, at.minusSeconds(1))

            assertFalse(result.any { it.skillId == 4L })
        }

        @Test
        @DisplayName("the most loaded skill comes first")
        fun `rows should be ordered by load count`() {
            val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            recordEvent(skillId = 2L, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at)
            recordEvent(skillId = 1L, tenantId = 1L, kind = SkillUsage.EVENT_VIEW, at = at)
            recordEvent(skillId = 1L, tenantId = 1L, kind = SkillUsage.EVENT_USE, at = at)

            val result = rows(1L, at.minusSeconds(1))

            assertEquals(1L, result.first().skillId)
            assertEquals(2L, result[1].skillId)
        }
    }
}
