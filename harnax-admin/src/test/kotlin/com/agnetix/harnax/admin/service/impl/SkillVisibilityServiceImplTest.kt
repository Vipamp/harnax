package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillVisibilityResponse
import com.agnetix.harnax.admin.dto.SkillVisibilityUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.skill.SkillReviewRecorder
import com.agnetix.harnax.admin.skill.SkillVisibilityCodec
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.SkillReviewLog
import com.agnetix.harnax.entity.SkillVisibilityPolicy
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillReviewLogMapper
import com.agnetix.harnax.mapper.SkillVisibilityPolicyMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * The visibility control plane's own rules: who may read a policy, who may write one, what the row it
 * writes is allowed to hold, and what the audit trail says afterwards.
 *
 * The mocked policy mapper behaves like the table it stands for — an upsert replaces the one row of that
 * skill and a read returns it — so the assertions below are about the encoded columns the agent spec will
 * later carry to the runtime, not about a call that could have stored anything.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillVisibilityServiceImplTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @Mock
    private lateinit var userTenantService: UserTenantService

    @Mock
    private lateinit var policyMapper: SkillVisibilityPolicyMapper

    /** The real recorder over a mocked log mapper, so the audit assertions read the entry itself. */
    @Mock
    private lateinit var skillReviewLogMapper: SkillReviewLogMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

    /** Stands for `skill_visibility_policy`, keyed the way its unique key is. */
    private val rows = mutableMapOf<Long, SkillVisibilityPolicy>()

    private lateinit var service: SkillVisibilityServiceImpl

    @BeforeEach
    fun setUp() {
        TenantContext.setTenantId(TENANT)
        `when`(skillMapper.selectById(SKILL_ID)).thenReturn(skill())
        // MessageUtil echoes the key: assertions name the bundle key, never its locale text
        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }
        `when`(userTenantService.isUserInTenant(anyLong(), anyLong())).thenReturn(true)

        `when`(policyMapper.upsert(any())).thenAnswer { invocation ->
            val policy = invocation.getArgument<SkillVisibilityPolicy>(0)
            rows[policy.skillId] = policy
            1
        }
        `when`(policyMapper.selectBySkillId(anyLong())).thenAnswer { invocation ->
            rows[invocation.getArgument<Long>(0)]
        }
        service = SkillVisibilityServiceImpl(
            jwtUtil = jwtUtil,
            skillMapper = skillMapper,
            sysUserMapper = sysUserMapper,
            skillRepositoryService = skillRepositoryService,
            userTenantService = userTenantService,
            skillVisibilityPolicyMapper = policyMapper,
            skillReviewRecorder = SkillReviewRecorder(skillReviewLogMapper, jwtUtil),
            messageUtil = messageUtil,
        )
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    private fun skill(tenantId: Long = TENANT): Skill = Skill().apply {
        id = SKILL_ID
        this.tenantId = tenantId
        name = "web-search"
        repositoryId = 5L
    }

    private fun request(
        mode: String?,
        canaryPct: Int? = null,
        userIds: List<Long>? = null,
        environments: List<String>? = null,
    ): SkillVisibilityUpdateRequest = SkillVisibilityUpdateRequest(
        mode = mode,
        canaryPct = canaryPct,
        userIds = userIds,
        environments = environments,
    )

    private fun user(
        id: Long,
        username: String,
    ): SysUser = SysUser().apply {
        this.id = id
        this.username = username
    }

    private fun update(req: SkillVisibilityUpdateRequest): SkillVisibilityResponse = service.update(SKILL_ID, req)

    /** The row that ended up in the table, which is what the next spec delivery will encode. */
    private fun storedRow(): SkillVisibilityPolicy = checkNotNull(rows[SKILL_ID])

    private fun canary(pct: Int?): SkillVisibilityPolicy = SkillVisibilityPolicy().apply {
        skillId = SKILL_ID
        tenantId = TENANT
        mode = SkillVisibilityPolicy.MODE_CANARY
        canaryPct = pct
    }

    @Nested
    @DisplayName("read")
    inner class Read {

        @Test
        @DisplayName("a skill with no policy row reads back as ALL, not as a missing page")
        fun `a skill without a row reads as ALL`() {
            val response = service.get(SKILL_ID)

            assertEquals(SkillVisibilityPolicy.MODE_ALL, response.mode)
            assertEquals("web-search", response.skillName)
            assertEquals(TENANT, response.tenantId, "the picker needs the tenant before any row exists")
            assertTrue(response.editable, "the caller's tenant owns the skill")
            assertNull(response.updateTime, "nothing has ever changed here")
            assertTrue(response.userIds.isEmpty())
        }

        @Test
        @DisplayName("a stored row reads back decoded, with its users named")
        fun `a stored policy reads back`() {
            rows[SKILL_ID] = SkillVisibilityPolicy().apply {
                skillId = SKILL_ID
                tenantId = TENANT
                mode = SkillVisibilityPolicy.MODE_ALLOW_LIST
                userIds = "[42,43]"
                updateTime = LocalDateTime.now()
            }
            `when`(sysUserMapper.selectByIds(any())).thenReturn(listOf(user(42L, "linqing")))

            val response = service.get(SKILL_ID)

            assertEquals(SkillVisibilityPolicy.MODE_ALLOW_LIST, response.mode)
            assertEquals(listOf(42L, 43L), response.userIds)
            assertEquals("linqing", response.users.first { it.id == 42L }.username)
            assertNull(
                response.users.first { it.id == 43L }.username,
                "an id with no live account still belongs on the list, named only by its id",
            )
        }

        @Test
        @DisplayName("a builtin skill another tenant owns is readable but not editable")
        fun `a builtin skill of another tenant is readable only`() {
            `when`(skillMapper.selectById(SKILL_ID)).thenReturn(skill(tenantId = 9L))
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(
                SkillRepository().apply {
                    id = 5L
                    tenantId = 9L
                },
            )

            assertFalse(service.get(SKILL_ID).editable, "readable across tenants, writable only by the owner")
        }

        @Test
        @DisplayName("another tenant's private skill has no visibility page at all")
        fun `a foreign private skill is refused`() {
            `when`(skillMapper.selectById(SKILL_ID)).thenReturn(skill(tenantId = 9L))

            val e = assertThrows<BizException> { service.get(SKILL_ID) }

            assertEquals("error.skill.no_permission", e.message)
        }

        @Test
        @DisplayName("a skill that does not exist is refused the same way as anywhere else")
        fun `a missing skill is refused`() {
            `when`(skillMapper.selectById(SKILL_ID)).thenReturn(null)

            val e = assertThrows<BizException> { service.get(SKILL_ID) }

            assertEquals("error.skill.notfound", e.message)
        }
    }

    @Nested
    @DisplayName("who may write")
    inner class WriteGuard {

        @Test
        @DisplayName("only the owning tenant may set a rollout, and the refusal names the owner")
        fun `a foreign tenant is refused the write`() {
            `when`(skillMapper.selectById(SKILL_ID)).thenReturn(skill(tenantId = 9L))

            val e = assertThrows<BizException> { update(request(SkillVisibilityPolicy.MODE_CANARY, 20)) }

            assertTrue(e.message!!.contains("tenant 9"), "the caller is told whose skill it is: ${e.message}")
            assertTrue(rows.isEmpty(), "a refused write stores nothing")
            verify(skillReviewLogMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a builtin skill another tenant owns is refused too, though its page opened")
        fun `a builtin skill of another tenant is refused the write`() {
            `when`(skillMapper.selectById(SKILL_ID)).thenReturn(skill(tenantId = 9L))
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(
                SkillRepository().apply {
                    id = 5L
                    tenantId = 9L
                },
            )

            assertThrows<BizException> { update(request(SkillVisibilityPolicy.MODE_ALL)) }
            assertTrue(rows.isEmpty())
        }

        @Test
        @DisplayName("a skill that does not exist is refused before its mode is read")
        fun `a missing skill is refused the write`() {
            `when`(skillMapper.selectById(SKILL_ID)).thenReturn(null)

            assertEquals("error.skill.notfound", assertThrows<BizException> { update(request("CANARY", 20)) }.message)
        }
    }

    @Nested
    @DisplayName("mode")
    inner class Mode {

        @Test
        @DisplayName("a mode is normalised, so the screen's lowercase answer stores the runtime's uppercase one")
        fun `a mode is normalised`() {
            update(request(" canary ", 20))

            assertEquals(SkillVisibilityPolicy.MODE_CANARY, storedRow().mode)
        }

        @Test
        @DisplayName("an absent or blank mode is refused rather than stored as open")
        fun `a blank mode is refused`() {
            listOf(null, "   ").forEach { mode ->
                val e = assertThrows<BizException> { update(request(mode, 20)) }
                assertTrue(e.message!!.contains("ALL"), "the refusal names the modes allowed: ${e.message}")
            }
            assertTrue(rows.isEmpty(), "nothing was stored")
        }

        @Test
        @DisplayName("a mode this build does not know is refused, not silently read as ALL")
        fun `an unknown mode is refused`() {
            val e = assertThrows<BizException> { update(request("GRAY")) }

            assertTrue(e.message!!.contains("GRAY"))
            assertTrue(rows.isEmpty())
        }

        @Test
        @DisplayName("only the column the mode uses is filled")
        fun `a mode fills only its own column`() {
            update(request(SkillVisibilityPolicy.MODE_CANARY, 20))

            val row = storedRow()
            assertEquals(20, checkNotNull(row.canaryPct))
            assertNull(row.userIds)
            assertNull(row.environments)
        }

        @Test
        @DisplayName("fields a mode does not use are ignored, not stored alongside it")
        fun `unused fields are ignored`() {
            update(
                request(
                    mode = SkillVisibilityPolicy.MODE_ENV,
                    canaryPct = 33,
                    userIds = listOf(42L),
                    environments = listOf("staging"),
                ),
            )

            val row = storedRow()
            assertNull(row.canaryPct)
            assertNull(row.userIds, "an allow-list next to ENV would be a second answer nobody set")
            assertEquals("staging", row.environments)
        }

        @Test
        @DisplayName("switching away from a mode clears the column it had filled")
        fun `leaving a mode clears its column`() {
            update(request(SkillVisibilityPolicy.MODE_CANARY, 20))
            update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L)))

            val row = storedRow()
            assertEquals(SkillVisibilityPolicy.MODE_ALLOW_LIST, row.mode)
            assertNull(row.canaryPct, "the rollout number of the mode that was replaced must not survive it")

            update(request(SkillVisibilityPolicy.MODE_ALL))

            val cleared = storedRow()
            assertEquals(SkillVisibilityPolicy.MODE_ALL, cleared.mode)
            assertNull(cleared.userIds, "a saved form that still shows an allow-list is a lie about the rollout")
        }

        @Test
        @DisplayName("the second save of a skill replaces its row rather than adding one")
        fun `a save replaces the row`() {
            update(request(SkillVisibilityPolicy.MODE_CANARY, 20))
            update(request(SkillVisibilityPolicy.MODE_CANARY, 40))

            assertEquals(1, rows.size, "one row per skill is what the unique key means")
            assertEquals(40, checkNotNull(storedRow().canaryPct))
        }

        @Test
        @DisplayName("the row is written under the tenant that owns the skill")
        fun `the row carries the skill tenant`() {
            update(request(SkillVisibilityPolicy.MODE_ALL))

            assertEquals(TENANT, storedRow().tenantId)
        }
    }

    @Nested
    @DisplayName("CANARY")
    inner class Canary {

        @Test
        @DisplayName("a percentage is required")
        fun `a missing percentage is refused`() {
            val e = assertThrows<BizException> { update(request(SkillVisibilityPolicy.MODE_CANARY)) }

            assertTrue(e.message!!.contains("percentage"))
            assertTrue(rows.isEmpty())
        }

        @Test
        @DisplayName("both ends of the range are accepted")
        fun `the ends of the range are accepted`() {
            update(request(SkillVisibilityPolicy.MODE_CANARY, 0))
            assertEquals(0, checkNotNull(storedRow().canaryPct))

            update(request(SkillVisibilityPolicy.MODE_CANARY, 100))
            assertEquals(100, checkNotNull(storedRow().canaryPct))
        }

        @Test
        @DisplayName("a number outside 0..100 is refused, not clamped")
        fun `an out of range percentage is refused`() {
            listOf(-1, 101).forEach { pct ->
                val e = assertThrows<BizException> { update(request(SkillVisibilityPolicy.MODE_CANARY, pct)) }
                assertTrue(e.message!!.contains(pct.toString()), "the refusal quotes the value: ${e.message}")
            }
            assertTrue(rows.isEmpty(), "neither save reached the table")
        }
    }

    @Nested
    @DisplayName("ALLOW_LIST")
    inner class AllowList {

        @Test
        @DisplayName("the ids are stored as the JSON array the codec reads back")
        fun `ids are stored as JSON`() {
            update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L, 43L)))

            assertEquals("[42,43]", storedRow().userIds)
        }

        @Test
        @DisplayName("an empty allow-list is refused: it would gate nobody and read as a rollout")
        fun `an empty list is refused`() {
            listOf<List<Long>?>(null, emptyList()).forEach { ids ->
                val e = assertThrows<BizException> { update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = ids)) }
                assertTrue(e.message!!.contains("at least one"))
            }
            assertTrue(rows.isEmpty())
        }

        @Test
        @DisplayName("duplicates collapse, so the delivered list names each user once")
        fun `duplicates collapse`() {
            update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L, 42L, 43L)))

            assertEquals("[42,43]", storedRow().userIds)
            // One read per name, so the collapsed list is what the membership was checked against too
            verify(userTenantService, times(1)).isUserInTenant(eq(42L), eq(TENANT))
        }

        @Test
        @DisplayName("a non-positive id is refused before any membership is looked up")
        fun `a non-positive id is refused`() {
            val e = assertThrows<BizException> {
                update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L, 0L, -3L)))
            }

            assertTrue(e.message!!.contains("0"))
            assertTrue(e.message!!.contains("-3"))
            verify(userTenantService, never()).isUserInTenant(anyLong(), anyLong())
        }

        @Test
        @DisplayName("an id that is not a member of the skill's tenant is refused by name")
        fun `an outsider is refused`() {
            `when`(userTenantService.isUserInTenant(eq(43L), anyLong())).thenReturn(false)

            val e = assertThrows<BizException> {
                update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L, 43L)))
            }

            assertTrue(e.message!!.contains("43"), "the operator is told which id is wrong: ${e.message}")
            assertTrue(rows.isEmpty(), "a half-valid allow-list is not a rollout guard")
        }

        @Test
        @DisplayName("a list wider than the ceiling is pointed at CANARY")
        fun `the ceiling is enforced`() {
            val e = assertThrows<BizException> {
                update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = (1L..201L).toList()))
            }

            assertTrue(e.message!!.contains("200"))
            assertTrue(e.message!!.contains("CANARY"))
            assertTrue(rows.isEmpty())
        }

        @Test
        @DisplayName("the ceiling itself is accepted, whole")
        fun `the ceiling is accepted`() {
            update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = (1L..200L).toList()))

            assertEquals(200, checkNotNull(storedRow().userIds).split(",").size)
        }
    }

    @Nested
    @DisplayName("ENV")
    inner class Env {

        @Test
        @DisplayName("labels are stored comma separated, trimmed and deduplicated")
        fun `labels are normalised`() {
            update(request(SkillVisibilityPolicy.MODE_ENV, environments = listOf(" staging ", "staging", "dev")))

            assertEquals("staging,dev", storedRow().environments)
        }

        @Test
        @DisplayName("an empty label list is refused")
        fun `an empty list is refused`() {
            listOf(null, emptyList(), listOf("  ", "")).forEach { labels ->
                val e = assertThrows<BizException> { update(request(SkillVisibilityPolicy.MODE_ENV, environments = labels)) }
                assertTrue(e.message!!.contains("at least one"))
            }
            assertTrue(rows.isEmpty())
        }

        @Test
        @DisplayName("a comma inside a label is refused: the column would read back as two labels")
        fun `a comma is refused`() {
            val e = assertThrows<BizException> {
                update(request(SkillVisibilityPolicy.MODE_ENV, environments = listOf("staging,prod")))
            }

            assertTrue(e.message!!.contains("comma"))
            assertTrue(rows.isEmpty())
        }

        @Test
        @DisplayName("a label over the per-label ceiling is refused")
        fun `a long label is refused`() {
            val e = assertThrows<BizException> {
                update(request(SkillVisibilityPolicy.MODE_ENV, environments = listOf("a".repeat(65))))
            }

            assertTrue(e.message!!.contains("64"))
        }

        /**
         * [count] labels of [length] characters that differ from each other: the service deduplicates labels
         * before it measures the column, so four copies of one label are one label.
         */
        private fun labels(
            length: Int,
            count: Int,
        ): List<String> = List(count) { (it.toString() + "x".repeat(length)).take(length) }

        @Test
        @DisplayName("labels that do not fit the column are refused rather than truncated")
        fun `an over-wide total is refused`() {
            // Four distinct labels of 64 characters each are individually legal; joined they are 259
            val e = assertThrows<BizException> {
                update(request(SkillVisibilityPolicy.MODE_ENV, environments = labels(64, 4)))
            }

            assertTrue(e.message!!.contains("255"))
            assertTrue(rows.isEmpty(), "a silently shortened rollout rule is worse than a refused save")
        }

        @Test
        @DisplayName("the widest list the column holds is stored whole")
        fun `a full column is stored`() {
            // 4 × 63 + 3 separators is exactly 255, the column's own limit
            val wide = labels(63, 4)

            update(request(SkillVisibilityPolicy.MODE_ENV, environments = wide))

            assertEquals(wide.joinToString(","), storedRow().environments)
            assertEquals(255, checkNotNull(storedRow().environments).length)
        }

        @Test
        @DisplayName("the stored labels are the ones the runtime compares case-insensitively")
        fun `labels reach the runtime as stored`() {
            update(request(SkillVisibilityPolicy.MODE_ENV, environments = listOf("Staging")))

            val policy = checkNotNull(storedRow())
            assertEquals(listOf("Staging"), SkillVisibilityCodec.toDto(policy).environments)
        }
    }

    @Nested
    @DisplayName("audit and response")
    inner class Audit {

        @Test
        @DisplayName("a change is audited with both states")
        fun `the change is audited`() {
            update(request(SkillVisibilityPolicy.MODE_CANARY, 20))

            val entry = lastAuditEntry()
            assertEquals(SkillReviewLog.SUBJECT_SKILL, entry.subject)
            assertEquals(SkillReviewLog.ACTION_VISIBILITY_CHANGE, entry.action)
            assertEquals(SKILL_ID, entry.subjectId)
            assertTrue(entry.detail!!.contains("\"before\":{\"mode\":\"ALL\"}"), "no row was in place: ${entry.detail}")
            assertTrue(entry.detail!!.contains("\"canaryPct\":20"), entry.detail!!)
            assertTrue(entry.detail!!.contains("web-search"), "named, so history outlives the row: ${entry.detail}")
        }

        @Test
        @DisplayName("the state being replaced is recorded as it was, in its decoded form")
        fun `the previous state is recorded`() {
            update(request(SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L)))
            update(request(SkillVisibilityPolicy.MODE_ENV, environments = listOf("staging")))

            val detail = checkNotNull(lastAuditEntry().detail)
            assertTrue(detail.contains("\"userIds\":[42]"), "the allow-list that was replaced: $detail")
            assertTrue(detail.contains("\"environments\":[\"staging\"]"), "the mode now in place: $detail")
        }

        @Test
        @DisplayName("an explicit ALL that replaces a rule says which rule went away")
        fun `opening a restricted skill is audited`() {
            rows[SKILL_ID] = canary(20)

            update(request(SkillVisibilityPolicy.MODE_ALL))

            val detail = checkNotNull(lastAuditEntry().detail)
            assertTrue(detail.contains("\"before\":{\"mode\":\"CANARY\",\"canaryPct\":20"), detail)
        }

        @Test
        @DisplayName("a refused save audits nothing")
        fun `a refused save is not audited`() {
            assertThrows<BizException> { update(request(SkillVisibilityPolicy.MODE_CANARY, 150)) }

            verify(skillReviewLogMapper, never()).insert(any())
        }

        @Test
        @DisplayName("the response is the stored row, read back through the codec the runtime is served from")
        fun `the response reflects the stored row`() {
            val response = update(request(SkillVisibilityPolicy.MODE_CANARY, 20))

            assertEquals(SkillVisibilityPolicy.MODE_CANARY, response.mode)
            assertEquals(20, response.canaryPct)
            assertTrue(response.editable, "the write succeeded, so this tenant does own the row")
        }

        private fun lastAuditEntry(): SkillReviewLog {
            val captor = argumentCaptor<SkillReviewLog>()
            verify(skillReviewLogMapper, atLeastOnce()).insert(captor.capture())
            return captor.lastValue
        }
    }

    private companion object {
        private const val TENANT = 7L

        // Deliberately unlike TENANT: a fixture where a skill id equals a tenant id lets any assertion about
        // one of them pass by reading the other off the response.
        private const val SKILL_ID = 101L
    }
}
