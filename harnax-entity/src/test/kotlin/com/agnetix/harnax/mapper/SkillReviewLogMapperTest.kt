package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillReviewLog
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the skill-domain audit trail.
 *
 * What the operations question actually needs from this table is an ordered, tenant-scoped read of one
 * subject's history, so that is what is pinned here rather than the column types.
 *
 * @author agnetix
 * @since 2026-10-05
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class SkillReviewLogMapperTest {

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
    private lateinit var skillReviewLogMapper: SkillReviewLogMapper

    private fun record(
        tenantId: Long = 1L,
        subject: String = SkillReviewLog.SUBJECT_SKILL,
        subjectId: Long = 1L,
        actor: String = "admin",
        action: String = SkillReviewLog.ACTION_ENABLE,
        detail: String? = null,
    ): Int = skillReviewLogMapper.insert(
        SkillReviewLog().apply {
            this.tenantId = tenantId
            this.subject = subject
            this.subjectId = subjectId
            this.actor = actor
            this.action = action
            this.detail = detail
            this.createTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        },
    )

    @Nested
    @DisplayName("Write and read back")
    inner class WriteReadTests {

        @Test
        @DisplayName("insert - persists a full entry and reads every field back")
        fun `insert should persist an entry and read it back`() {
            val at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            assertEquals(1, record(detail = """{"name":"web-search"}""", action = SkillReviewLog.ACTION_DISABLE))

            val logs = skillReviewLogMapper.selectBySubject(1L, SkillReviewLog.SUBJECT_SKILL, 1L)
            assertEquals(1, logs.size)
            val log = logs.first()
            assertEquals(1L, log.tenantId)
            assertEquals("admin", log.actor)
            assertEquals(SkillReviewLog.ACTION_DISABLE, log.action)
            assertEquals("""{"name":"web-search"}""", log.detail)
            assertEquals(at, log.createTime)
            assertTrue(log.id > 0)
        }

        @Test
        @DisplayName("insert - keeps an absent detail absent instead of an empty string")
        fun `insert should keep a null detail null`() {
            record()

            val log = skillReviewLogMapper.selectBySubject(1L, SkillReviewLog.SUBJECT_SKILL, 1L).first()

            assertNull(log.detail)
        }

        @Test
        @DisplayName("selectBySubject - answers newest first so the last action reads off the top")
        fun `selectBySubject should return newest first`() {
            record(action = SkillReviewLog.ACTION_ENABLE)
            record(action = SkillReviewLog.ACTION_DISABLE)
            record(actor = "ops", action = SkillReviewLog.ACTION_APPROVE)

            val actions = skillReviewLogMapper.selectBySubject(1L, SkillReviewLog.SUBJECT_SKILL, 1L).map { it.action }

            assertEquals(
                listOf(
                    SkillReviewLog.ACTION_APPROVE,
                    SkillReviewLog.ACTION_DISABLE,
                    SkillReviewLog.ACTION_ENABLE,
                ),
                actions,
            )
        }
    }

    @Nested
    @DisplayName("Scoping")
    inner class ScopeTests {

        @Test
        @DisplayName("selectBySubject - keeps another tenant's history out of the answer")
        fun `selectBySubject should not cross tenants`() {
            record(tenantId = 1L, subjectId = 7L)
            record(tenantId = 2L, subjectId = 7L, actor = "user2")

            assertEquals(1, skillReviewLogMapper.selectBySubject(1L, SkillReviewLog.SUBJECT_SKILL, 7L).size)

            val tenant2 = skillReviewLogMapper.selectBySubject(2L, SkillReviewLog.SUBJECT_SKILL, 7L)
            assertEquals(1, tenant2.size)
            assertEquals("user2", tenant2.first().actor)
        }

        @Test
        @DisplayName("selectBySubject - a draft's entries are not a skill's history")
        fun `selectBySubject should separate subject kinds`() {
            // Both tables key on a row id that can coincide; a promoted draft must not appear as the
            // skill's own trail
            record(subject = SkillReviewLog.SUBJECT_SKILL, subjectId = 9L)
            record(subject = SkillReviewLog.SUBJECT_DRAFT, subjectId = 9L, action = SkillReviewLog.ACTION_PROPOSE)

            val skillTrail = skillReviewLogMapper.selectBySubject(1L, SkillReviewLog.SUBJECT_SKILL, 9L)
            assertEquals(1, skillTrail.size)
            assertEquals(SkillReviewLog.ACTION_ENABLE, skillTrail.first().action)

            val draftTrail = skillReviewLogMapper.selectBySubject(1L, SkillReviewLog.SUBJECT_DRAFT, 9L)
            assertEquals(1, draftTrail.size)
            assertEquals(SkillReviewLog.ACTION_PROPOSE, draftTrail.first().action)
        }

        @Test
        @DisplayName("selectBySubject - an unknown subject answers empty rather than failing")
        fun `selectBySubject should answer empty for an unknown subject`() {
            record(subjectId = 1L)

            assertTrue(skillReviewLogMapper.selectBySubject(1L, SkillReviewLog.SUBJECT_SKILL, 424242L).isEmpty())
        }
    }
}
