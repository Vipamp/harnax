package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillUsageReportRequest
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.SkillUsage
import com.agnetix.harnax.entity.dto.SkillUsageAggregate
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillUsageMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * SkillUsageServiceImpl Unit Tests
 *
 * The intake side is where a made-up session id or a guessed tenant becomes somebody else's analytics
 * number, so most of these tests assert what was *refused*; the aggregate side asserts that a skill with
 * no events is still reported, because that is the row the operations page exists to show.
 *
 * @author agnetix
 * @since 2026-10-05
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillUsageServiceImplTest {

    @Mock
    private lateinit var skillUsageMapper: SkillUsageMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var channelMapper: ChannelMapper

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @Mock
    private lateinit var userTenantService: UserTenantService

    @Mock
    private lateinit var jwtUtil: JwtUtil

    private lateinit var service: SkillUsageServiceImpl

    @BeforeEach
    fun setUp() {
        service = SkillUsageServiceImpl(
            skillUsageMapper = skillUsageMapper,
            skillMapper = skillMapper,
            sessionMapper = sessionMapper,
            channelMapper = channelMapper,
            agentMapper = agentMapper,
            skillRepositoryService = skillRepositoryService,
            userTenantService = userTenantService,
            jwtUtil = jwtUtil,
        )
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    private fun stubInsertAcks() {
        `when`(skillUsageMapper.insert(any())).thenReturn(1)
    }

    private fun stubSession(sessionId: String, tenantId: Long) {
        `when`(sessionMapper.selectBySessionIdAndStatus(sessionId, ACTIVE_STATUS)).thenReturn(
            Session().apply { this.tenantId = tenantId },
        )
    }

    private fun stubVisibleSkills(vararg skills: Skill) {
        `when`(skillMapper.selectByIds(any())).thenReturn(skills.toList())
    }

    private fun skill(
        id: Long,
        tenantId: Long,
        repositoryId: Long = 1L,
    ) = Skill().apply {
        this.id = id
        this.tenantId = tenantId
        this.repositoryId = repositoryId
        name = "skill-$id"
    }

    private fun report(
        sessionId: String?,
        vararg events: Pair<Long?, String?>,
        userId: Long? = null,
    ) = service.report(
        SkillUsageReportRequest(
            sessionId = sessionId,
            userId = userId,
            events = events.map { SkillUsageReportRequest.Event(skillId = it.first, event = it.second) },
        ),
    )

    private fun aggregate(
        skillId: Long,
        viewCount: Int = 0,
        useCount: Int = 0,
        repositoryId: Long = 1L,
        origin: String = Skill.ORIGIN_HUMAN,
        lastUsedAt: LocalDateTime? = null,
    ) = SkillUsageAggregate().apply {
        this.skillId = skillId
        name = "skill-$skillId"
        this.repositoryId = repositoryId
        status = 1
        this.origin = origin
        this.viewCount = viewCount
        this.useCount = useCount
        this.lastUsedAt = lastUsedAt
    }

    @Nested
    @DisplayName("Tenant resolution by session shape")
    inner class TenantResolutionTests {

        @Test
        @DisplayName("report - files a web session under the tenant of its session row")
        fun `report should file a web session under its own tenant`() {
            stubSession("web-42", 7L)
            stubVisibleSkills(skill(1L, 7L))
            stubInsertAcks()

            assertEquals(1, report("web-42", 1L to SkillUsage.EVENT_VIEW))

            val captor = argumentCaptor<SkillUsage>()
            verify(skillUsageMapper).insert(captor.capture())
            val stored = captor.firstValue
            assertEquals(7L, stored.tenantId)
            assertEquals(1L, stored.skillId)
            assertEquals(SkillUsage.EVENT_VIEW, stored.event)
            assertEquals("web-42", stored.sessionId)
            assertNull(stored.userId, "a report that names no user must not invent one")
        }

        @Test
        @DisplayName("report - reads a mini program session from the same table")
        fun `report should resolve a mini program session from the session table`() {
            stubSession("mp-9", 3L)
            stubVisibleSkills(skill(2L, 3L))
            stubInsertAcks()

            assertEquals(1, report("mp-9", 2L to SkillUsage.EVENT_USE))

            verify(sessionMapper).selectBySessionIdAndStatus("mp-9", ACTIVE_STATUS)
        }

        @Test
        @DisplayName("report - resolves a channel conversation from the channel table")
        fun `report should resolve a channel conversation from the channel table`() {
            // A chn- id has no session row at all, so the session lookup would answer null and drop a real event
            `when`(channelMapper.selectBySessionId("chn-77")).thenReturn(Channel().apply { tenantId = 8L })
            stubVisibleSkills(skill(1L, 8L))
            stubInsertAcks()

            assertEquals(1, report("chn-77", 1L to SkillUsage.EVENT_VIEW))

            verify(sessionMapper, never()).selectBySessionIdAndStatus(anyString(), anyInt())
        }

        @Test
        @DisplayName("report - resolves a scheduled run from the agent id its session id carries")
        fun `report should resolve a task session from the agent it names`() {
            val sessionId = TaskSessionId.of(taskId = 5L, agentId = 42L)
            `when`(agentMapper.selectById(42L)).thenReturn(Agent().apply { tenantId = 9L })
            stubVisibleSkills(skill(1L, 9L))
            stubInsertAcks()

            assertEquals(1, report(sessionId, 1L to SkillUsage.EVENT_VIEW))

            verify(sessionMapper, never()).selectBySessionIdAndStatus(anyString(), anyInt())
        }

        @Test
        @DisplayName("report - refuses an id of no known shape instead of guessing a tenant")
        fun `report should refuse an unknown session shape`() {
            // Guessing a tenant for an id this system never mints would count the events on the wrong workspace
            assertEquals(0, report("loose-string", 1L to SkillUsage.EVENT_VIEW))

            verify(skillUsageMapper, never()).insert(any())
        }

        @Test
        @DisplayName("report - drops the events of a session whose row is gone")
        fun `report should drop events when the session row is missing`() {
            `when`(sessionMapper.selectBySessionIdAndStatus("web-gone", ACTIVE_STATUS)).thenReturn(null)

            assertEquals(0, report("web-gone", 1L to SkillUsage.EVENT_VIEW))

            verify(skillUsageMapper, never()).insert(any())
        }

        @Test
        @DisplayName("report - a blank or absent session id stores nothing and touches no table")
        fun `report should store nothing without a session id`() {
            assertEquals(0, report(null, 1L to SkillUsage.EVENT_VIEW))
            assertEquals(0, report("   ", 1L to SkillUsage.EVENT_VIEW))

            verify(skillUsageMapper, never()).insert(any())
            verify(skillMapper, never()).selectByIds(any())
        }

        @Test
        @DisplayName("report - an empty event list is a no-op, not a tenant lookup")
        fun `report should be a no-op for an empty event list`() {
            assertEquals(0, service.report(SkillUsageReportRequest(sessionId = "web-42", events = emptyList())))

            verify(sessionMapper, never()).selectBySessionIdAndStatus(anyString(), anyInt())
            verify(skillUsageMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Which events are kept")
    inner class IntakeGuardTests {

        @Test
        @DisplayName("report - drops a skill of another tenant")
        fun `report should drop a skill the tenant cannot see`() {
            // Tenant 1 reporting skill 5 (tenant 2, repository 4) would put a number on tenant 2's page
            stubSession("web-1", 1L)
            stubVisibleSkills(skill(5L, tenantId = 2L, repositoryId = 4L))

            assertEquals(0, report("web-1", 5L to SkillUsage.EVENT_VIEW))

            verify(skillUsageMapper, never()).insert(any())
        }

        @Test
        @DisplayName("report - keeps a skill of the shared builtin repository for a tenant that does not own it")
        fun `report should keep a builtin repository skill`() {
            stubSession("web-1", 1L)
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(SkillRepository().apply { id = 100L })
            stubVisibleSkills(skill(5L, tenantId = 2L, repositoryId = 100L))
            stubInsertAcks()

            assertEquals(1, report("web-1", 5L to SkillUsage.EVENT_VIEW))

            val captor = argumentCaptor<SkillUsage>()
            verify(skillUsageMapper).insert(captor.capture())
            assertEquals(1L, captor.firstValue.tenantId, "the event belongs to the tenant that loaded it, not the owner")
        }

        @Test
        @DisplayName("report - drops a skill row that does not exist")
        fun `report should drop an unknown skill id`() {
            stubSession("web-1", 1L)
            stubVisibleSkills()

            assertEquals(0, report("web-1", 999L to SkillUsage.EVENT_USE))

            verify(skillUsageMapper, never()).insert(any())
        }

        @Test
        @DisplayName("report - drops an event kind the stream does not define")
        fun `report should drop an unknown event kind`() {
            stubSession("web-1", 1L)
            stubVisibleSkills(skill(1L, 1L))

            assertEquals(0, report("web-1", 1L to "DELETE"))

            verify(skillUsageMapper, never()).insert(any())
        }

        @Test
        @DisplayName("report - accepts the kind in any case because the runtime sends lowercase")
        fun `report should normalise the event kind`() {
            stubSession("web-1", 1L)
            stubVisibleSkills(skill(1L, 1L))
            stubInsertAcks()

            assertEquals(1, report("web-1", 1L to " use "))

            val captor = argumentCaptor<SkillUsage>()
            verify(skillUsageMapper).insert(captor.capture())
            assertEquals(SkillUsage.EVENT_USE, captor.firstValue.event)
        }

        @Test
        @DisplayName("report - drops events naming no skill without dropping the rest")
        fun `report should drop events with no usable skill id`() {
            stubSession("web-1", 1L)
            stubVisibleSkills(skill(1L, 1L))
            stubInsertAcks()

            assertEquals(
                1,
                report(
                    "web-1",
                    null to SkillUsage.EVENT_VIEW,
                    0L to SkillUsage.EVENT_VIEW,
                    1L to SkillUsage.EVENT_VIEW,
                ),
            )
        }

        @Test
        @DisplayName("report - caps one report so a broken caller cannot fan out unbounded inserts")
        fun `report should cap the events of one report`() {
            stubSession("web-1", 1L)
            stubVisibleSkills(skill(1L, 1L))
            stubInsertAcks()
            val oversized = SkillUsageReportRequest(
                sessionId = "web-1",
                events = List(201) { SkillUsageReportRequest.Event(skillId = 1L, event = SkillUsage.EVENT_VIEW) },
            )

            assertEquals(200, service.report(oversized))

            verify(skillUsageMapper, times(200)).insert(any())
        }

        @Test
        @DisplayName("report - counts the rows the store acknowledged, not the events it was handed")
        fun `report should count only acknowledged rows`() {
            stubSession("web-1", 1L)
            stubVisibleSkills(skill(1L, 1L))
            `when`(skillUsageMapper.insert(any())).thenReturn(0)

            assertEquals(0, report("web-1", 1L to SkillUsage.EVENT_VIEW))
        }
    }

    @Nested
    @DisplayName("Who a report is attributed to")
    inner class UserAttributionTests {

        @Test
        @DisplayName("report - stores the end user the runtime named when it belongs to this tenant")
        fun `report should keep a user of the session tenant`() {
            stubSession("web-42", 7L)
            stubVisibleSkills(skill(1L, 7L))
            stubInsertAcks()
            `when`(userTenantService.isUserInTenant(42L, 7L)).thenReturn(true)

            assertEquals(1, report("web-42", 1L to SkillUsage.EVENT_VIEW, userId = 42L))

            val captor = argumentCaptor<SkillUsage>()
            verify(skillUsageMapper).insert(captor.capture())
            assertEquals(42L, captor.firstValue.userId)
        }

        @Test
        @DisplayName("report - keeps the count but drops a user from another tenant")
        fun `report should not attribute a user outside the session tenant`() {
            // The events really happened in tenant 7; only the name attached to them is unverifiable. Refusing
            // the whole batch would lose real counts, and keeping the id would write them onto another tenant's
            // per-user read.
            stubSession("web-42", 7L)
            stubVisibleSkills(skill(1L, 7L))
            stubInsertAcks()
            `when`(userTenantService.isUserInTenant(99L, 7L)).thenReturn(false)

            assertEquals(1, report("web-42", 1L to SkillUsage.EVENT_VIEW, userId = 99L))

            val captor = argumentCaptor<SkillUsage>()
            verify(skillUsageMapper).insert(captor.capture())
            assertNull(captor.firstValue.userId)
            assertEquals(7L, captor.firstValue.tenantId)
        }

        @Test
        @DisplayName("report - treats a non-positive user id as no user and asks nothing about it")
        fun `report should ignore a user id that names no account`() {
            stubSession("web-42", 7L)
            stubVisibleSkills(skill(1L, 7L))
            stubInsertAcks()

            assertEquals(1, report("web-42", 1L to SkillUsage.EVENT_VIEW, userId = 0L))

            verifyNoInteractions(userTenantService)
            val captor = argumentCaptor<SkillUsage>()
            verify(skillUsageMapper).insert(captor.capture())
            assertNull(captor.firstValue.userId)
        }

        @Test
        @DisplayName("report - checks one membership per report, not once per event")
        fun `report should resolve the user once for a batch`() {
            stubSession("web-42", 7L)
            stubVisibleSkills(skill(1L, 7L), skill(2L, 7L))
            stubInsertAcks()
            `when`(userTenantService.isUserInTenant(42L, 7L)).thenReturn(true)

            assertEquals(
                2,
                report(
                    "web-42",
                    1L to SkillUsage.EVENT_VIEW,
                    2L to SkillUsage.EVENT_USE,
                    userId = 42L,
                ),
            )

            verify(userTenantService, times(1)).isUserInTenant(42L, 7L)
        }
    }

    @Nested
    @DisplayName("Usage summary")
    inner class SummaryTests {

        @Test
        @DisplayName("summary - clamps a window outside the reportable range")
        fun `summary should clamp the window`() {
            `when`(skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull())).thenReturn(emptyList())

            assertEquals(1, service.summary(days = 0).days)
            assertEquals(1, service.summary(days = -5).days)
            assertEquals(365, service.summary(days = 5000).days)
            assertEquals(30, service.summary(days = 30).days)
        }

        @Test
        @DisplayName("summary - reads within the request's tenant and passes the builtin exemption through")
        fun `summary should read within the request tenant`() {
            TenantContext.setTenantId(7L)
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(SkillRepository().apply { id = 100L })
            `when`(skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull())).thenReturn(emptyList())

            service.summary(days = 30)

            verify(skillUsageMapper).selectUsageByTenant(eq(7L), any(), eq(100L))
        }

        @Test
        @DisplayName("summary - reports no exemption when the deployment has no builtin repository")
        fun `summary should pass a null builtin id through`() {
            TenantContext.setTenantId(7L)
            `when`(skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull())).thenReturn(emptyList())

            service.summary(days = 30)

            verify(skillUsageMapper).selectUsageByTenant(eq(7L), any(), isNull())
        }

        @Test
        @DisplayName("summary - the window it reports is the one it queried")
        fun `summary should report the window it queried`() {
            `when`(skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull())).thenReturn(emptyList())

            val since = requireNotNull(service.summary(days = 7).since)

            assertTrue(since.isAfter(LocalDateTime.now().minusDays(8)), "$since is not roughly a week back")
            assertTrue(since.isBefore(LocalDateTime.now().minusDays(6).plusSeconds(30)), "$since is not roughly a week back")
        }

        @Test
        @DisplayName("summary - totals the rows and counts the skills nobody ever loaded")
        fun `summary should total the rows`() {
            val recent = LocalDateTime.now().minusHours(2)
            `when`(
                skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull()),
            ).thenReturn(
                listOf(
                    aggregate(1L, viewCount = 5, useCount = 2, lastUsedAt = recent),
                    aggregate(2L),
                    aggregate(3L, useCount = 1, lastUsedAt = recent),
                ),
            )
            `when`(skillRepositoryService.getSkillRepository(any())).thenReturn(SkillRepository().apply { name = "qoder-skills" })

            val summary = service.summary(days = 30)

            assertEquals(3, summary.totalSkills)
            assertEquals(1, summary.zeroUseCount, "only the skill with neither a load nor a use counts as unused")
            assertEquals(5, summary.totalViews)
            assertEquals(3, summary.totalUses)
        }

        @Test
        @DisplayName("summary - maps every column the page needs, origin included")
        fun `summary should map each row`() {
            val promoted = aggregate(
                9L,
                viewCount = 4,
                useCount = 1,
                repositoryId = 100L,
                origin = Skill.ORIGIN_AGENT_PROMOTED,
            )
            `when`(skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull())).thenReturn(listOf(promoted))
            `when`(skillRepositoryService.getSkillRepository(100L)).thenReturn(SkillRepository().apply { name = "agent-promoted" })

            val row = service.summary(days = 30).rows.first()

            assertEquals(9L, row.skillId)
            assertEquals("skill-9", row.name)
            assertEquals(100L, row.repositoryId)
            assertEquals("agent-promoted", row.repositoryName)
            assertEquals(Skill.ORIGIN_AGENT_PROMOTED, row.origin)
            assertEquals(4, row.viewCount)
            assertEquals(1, row.useCount)
            assertEquals(promoted.lastUsedAt, row.lastUsedAt)
        }

        @Test
        @DisplayName("summary - a repository that no longer resolves leaves the name unknown")
        fun `summary should tolerate a repository that does not resolve`() {
            `when`(skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull())).thenReturn(listOf(aggregate(1L, repositoryId = 404L)))
            `when`(skillRepositoryService.getSkillRepository(404L)).thenReturn(null)

            assertNull(service.summary(days = 30).rows.first().repositoryName)
        }

        @Test
        @DisplayName("summary - asks for each repository once, not once per row")
        fun `summary should resolve each repository once`() {
            `when`(
                skillUsageMapper.selectUsageByTenant(any(), any(), anyOrNull()),
            ).thenReturn(
                listOf(aggregate(1L, repositoryId = 5L), aggregate(2L, repositoryId = 5L), aggregate(3L, repositoryId = 6L)),
            )

            service.summary(days = 30)

            verify(skillRepositoryService).getSkillRepository(5L)
            verify(skillRepositoryService).getSkillRepository(6L)
        }
    }

    companion object {
        private const val ACTIVE_STATUS = 1
    }
}
