package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillVisibilityPolicy
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `skill_visibility_policy` on a real MySQL: the one-row-per-skill upsert the control plane writes through,
 * and the batch read the agent spec is served from.
 *
 * The service layer decides what a legal policy is; this class pins what the storage does with one. Two of
 * its behaviours are only visible against a real engine: `ON DUPLICATE KEY UPDATE` replacing the previous
 * mode's column with NULL, and the explicit `update_time` write that makes a re-save of unchanged values
 * still a dated event.
 *
 * @author agnetix
 * @since 2026-10-05
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class SkillVisibilityPolicyMapperTest {

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
    private lateinit var mapper: SkillVisibilityPolicyMapper

    /** Only the table under test is counted through SQL; the row count is the unique key's own answer. */
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun store(
        skillId: Long,
        mode: String,
        canaryPct: Int? = null,
        userIds: String? = null,
        environments: String? = null,
    ): Int = mapper.upsert(
        SkillVisibilityPolicy().apply {
            this.skillId = skillId
            tenantId = 7L
            this.mode = mode
            this.canaryPct = canaryPct
            this.userIds = userIds
            this.environments = environments
        },
    )

    /** `queryForObject` is nullable to Kotlin, but a COUNT always answers with exactly one row. */
    private fun rowsOf(skillId: Long): Int = checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM skill_visibility_policy WHERE skill_id = ?", Int::class.java, skillId))

    @Test
    @DisplayName("a stored policy reads back with every column it carries")
    fun storesAndReadsOnePolicy() {
        store(101L, SkillVisibilityPolicy.MODE_CANARY, canaryPct = 20)

        val row = assertNotNull(mapper.selectBySkillId(101L))
        assertEquals(SkillVisibilityPolicy.MODE_CANARY, row.mode)
        assertEquals(20, row.canaryPct)
        assertEquals(7L, row.tenantId)
        assertNull(row.userIds, "a mode with no list stores no list, rather than an empty one")
    }

    @Test
    @DisplayName("a skill nobody configured has no row, which is not the same as a row saying ALL")
    fun anUnconfiguredSkillHasNoRow() {
        assertNull(mapper.selectBySkillId(999_999L))
    }

    @Test
    @DisplayName("the second save of a skill replaces its row, because skill_id is unique")
    fun oneRowPerSkill() {
        store(102L, SkillVisibilityPolicy.MODE_CANARY, canaryPct = 20)
        store(102L, SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = "[42,43]")

        assertEquals(1, rowsOf(102L), "ON DUPLICATE KEY UPDATE, not a second insert")

        val row = assertNotNull(mapper.selectBySkillId(102L))
        assertEquals(SkillVisibilityPolicy.MODE_ALLOW_LIST, row.mode)
        assertEquals("[42,43]", row.userIds)
        assertNull(row.canaryPct, "the replaced mode's column has to go, or the row holds two rollout answers")
    }

    @Test
    @DisplayName("opening a restricted skill clears every parameter column")
    fun allClearsTheColumns() {
        store(103L, SkillVisibilityPolicy.MODE_ENV, environments = "staging,dev")
        store(103L, SkillVisibilityPolicy.MODE_ALL)

        val row = assertNotNull(mapper.selectBySkillId(103L))
        assertEquals(SkillVisibilityPolicy.MODE_ALL, row.mode)
        assertNull(row.environments)
        assertNull(row.canaryPct)
        assertNull(row.userIds)
    }

    @Test
    @DisplayName("a full 255-character environment column is stored whole, not truncated")
    fun aFullColumnValueSurvives() {
        // 4 distinct 63-character labels plus their separators is exactly the column width
        val labels = List(4) { (it.toString() + "x".repeat(63)).take(63) }
        val joined = labels.joinToString(",")
        assertEquals(255, joined.length, "the fixture is the boundary the admin guard measures against")

        store(104L, SkillVisibilityPolicy.MODE_ENV, environments = joined)

        assertEquals(joined, assertNotNull(mapper.selectBySkillId(104L)).environments)
    }

    @Test
    @DisplayName("one batched read answers the ids that have rows and leaves the rest out")
    fun readsTheBatch() {
        store(105L, SkillVisibilityPolicy.MODE_CANARY, canaryPct = 5)
        store(106L, SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = "[42]")

        val rows = mapper.selectBySkillIds(listOf(105L, 106L, 107L))

        assertEquals(setOf(105L, 106L), rows.map { it.skillId }.toSet())
        assertEquals(
            SkillVisibilityPolicy.MODE_CANARY,
            rows.first { it.skillId == 105L }.mode,
            "the batch answers per skill, so the reader can key by id",
        )
    }

    @Test
    @DisplayName("deleting a policy takes only that skill's row")
    fun deletesOneRow() {
        store(108L, SkillVisibilityPolicy.MODE_CANARY, canaryPct = 5)
        store(109L, SkillVisibilityPolicy.MODE_CANARY, canaryPct = 5)

        assertEquals(1, mapper.deleteBySkillId(108L))

        assertNull(mapper.selectBySkillId(108L))
        assertNotNull(mapper.selectBySkillId(109L))
        assertEquals(0, mapper.deleteBySkillId(108L), "a second delete has nothing left to take")
    }

    @Test
    @DisplayName("saving the same values again still dates the row, which is what the audit entry reads")
    fun aReSaveBumpsTheTimestamp() {
        store(110L, SkillVisibilityPolicy.MODE_CANARY, canaryPct = 20)
        val first = assertNotNull(mapper.selectBySkillId(110L)).updateTime
        Thread.sleep(1_500)

        store(110L, SkillVisibilityPolicy.MODE_CANARY, canaryPct = 20)

        val second = assertNotNull(mapper.selectBySkillId(110L)).updateTime
        assertTrue(second.isAfter(first), "update_time is written explicitly: $first -> $second")
    }
}
