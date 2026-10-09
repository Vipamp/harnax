package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.MemoryDraftApproveRequest
import com.agnetix.harnax.admin.dto.MemoryDraftDecisionResponse
import com.agnetix.harnax.admin.dto.MemoryDraftRejectRequest
import com.agnetix.harnax.admin.dto.MemoryDraftSource
import com.agnetix.harnax.admin.dto.MemoryDraftSubmitRequest
import com.agnetix.harnax.admin.dto.MemoryDraftTarget
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.McpSessionOwner
import com.agnetix.harnax.admin.util.McpSessionOwnerResolver
import com.agnetix.harnax.admin.util.MemoryDraftCodec
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.MemoryDraft
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.MemoryDraftMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SysUserMapper
import com.github.pagehelper.PageHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
import org.mockito.kotlin.inOrder
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Who a memory merge is filed against, and what an approval is allowed to touch.
 *
 * Intake is the runtime's only route into an owner's long-term layer, so most of these assert what was
 * refused and *whose* the accepted row got: the tenant, the person and the agent all come out of the session
 * id, and a proposal naming an agent its conversation did not run would put one agent's text into another's
 * memory on the strength of a click.
 *
 * The decision tests hold the other half — that nothing reaches the memory bucket except an approval whose
 * preconditions still hold, one per object the candidate writes. Two of those preconditions cannot be read
 * from the row at all, only from the store: the digest, which says the owner approved the text they read, and
 * the version of each object, which says the merge was made against the bytes still there. The order between
 * them and the store writes is asserted explicitly, because the two failure modes it prevents are different:
 * a claim that lands before a refused write closes a candidate nobody decided, and a clear that runs before a
 * failed write deletes a conversation's memory for a layer that never changed. The writes go days first and
 * the conclusion layer last, which is what makes a part-way failure an unfinished approval rather than a
 * half-merged memory.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryDraftServiceImpl - intake and the only writer of the long-term layer")
class MemoryDraftServiceImplTest {

    @Mock
    private lateinit var memoryDraftMapper: MemoryDraftMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var mcpSessionOwnerResolver: McpSessionOwnerResolver

    @Mock
    private lateinit var memoryStoreGateway: MemoryStoreGateway

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    private lateinit var service: MemoryDraftServiceImpl

    @BeforeEach
    fun setUp() {
        service = MemoryDraftServiceImpl(
            memoryDraftMapper = memoryDraftMapper,
            sessionMapper = sessionMapper,
            agentMapper = agentMapper,
            mcpSessionOwnerResolver = mcpSessionOwnerResolver,
            memoryStoreGateway = memoryStoreGateway,
            jwtUtil = jwtUtil,
        )
        // A generated key is what the mapper writes back; the mock has to, or the returned id says nothing
        `when`(memoryDraftMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<MemoryDraft>(0).id = NEW_ID
            1
        }
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()
        // The SecurityUtils singleton is process-wide; leaving it registered would hand the next test class
        // an account that belongs to this one.
        val instanceField = SecurityUtils::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
        PageHelper.clearPage()
    }

    /**
     * Logs in the owner whose memory this is: `member`, id 7, tenant 4.
     *
     * No request attributes are installed on purpose, so nothing names a workspace as a header would and the
     * tenant has to come from the account row — a test that set [TenantContext] first would pass even if the
     * row were ignored.
     */
    private fun loginAsMember() {
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername(MEMBER)).thenReturn(
            SysUser().apply {
                id = USER_ID
                username = MEMBER
                tenantId = TENANT
            },
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(MEMBER, null, ArrayList())
    }

    /** A conversation admin can place: an owner from the session's creator, an agent from its `agent_id`. */
    private fun stubWebSession(agentId: Long = AGENT_ID) {
        `when`(mcpSessionOwnerResolver.resolve(WEB_SESSION)).thenReturn(McpSessionOwner(userId = USER_ID, tenantId = TENANT))
        `when`(sessionMapper.selectBySessionIdAndStatus(WEB_SESSION, 1)).thenReturn(
            Session().apply {
                sessionId = WEB_SESSION
                tenantId = TENANT
                this.agentId = agentId
            },
        )
        stubAgent(agentId)
    }

    private fun stubAgent(
        agentId: Long = AGENT_ID,
        name: String = AGENT,
        tenantId: Long = TENANT,
    ) {
        `when`(agentMapper.selectById(agentId)).thenReturn(
            Agent().apply {
                id = agentId
                this.name = name
                this.tenantId = tenantId
            },
        )
    }

    private fun stubResolvableSources() {
        `when`(
            memoryStoreGateway.sessionSourceKey(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(WEB_SESSION), any()),
        ).thenAnswer { invocation -> "store/tenants/4/users/7/agents/Research/sessions/web-1/${invocation.getArgument<String>(4)}" }
    }

    /**
     * The store's own reachability answer for a daily target: a `memory/<date>.md` path names a day of the
     * agent's ledger, anything else — `MEMORY.md` above all — names no object an approval could write.
     */
    private fun stubResolvableTargets() {
        `when`(
            memoryStoreGateway.longTermSourceKey(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), any()),
        ).thenAnswer { invocation ->
            val path = invocation.getArgument<String>(3)
            if (path.startsWith("memory/") && path.endsWith(".md")) "store/tenants/4/users/7/agents/Research/$path" else null
        }
    }

    private fun submit(
        sessionId: String? = WEB_SESSION,
        agentName: String? = AGENT,
        mergedMarkdown: String? = MERGED,
        baseMarkdown: String? = BASE,
        baseVersion: Long = 3L,
        sources: List<MemoryDraftSource>? = SOURCES,
        targets: List<MemoryDraftTarget>? = null,
    ): Long = service.submit(
        MemoryDraftSubmitRequest(
            sessionId = sessionId,
            agentName = agentName,
            mergedMarkdown = mergedMarkdown,
            baseMarkdown = baseMarkdown,
            baseVersion = baseVersion,
            sources = sources,
            targets = targets,
        ),
    )

    private fun capturedInsert(): MemoryDraft = argumentCaptor<MemoryDraft>().let { captor ->
        verify(memoryDraftMapper).insert(captor.capture())
        captor.firstValue
    }

    private fun pendingDraft(
        status: String = MemoryDraft.STATUS_PENDING,
        agentName: String = AGENT,
        sessionId: String = WEB_SESSION,
        mergedMd: String = MERGED,
        baseMd: String? = BASE,
        baseVersion: Long = 3L,
        sources: List<MemoryDraftSource> = SOURCES,
        targets: List<MemoryDraftTarget> = emptyList(),
        reviewedBy: String? = null,
        rejectReason: String? = null,
    ): MemoryDraft {
        val draft = MemoryDraft()
        draft.id = DRAFT_ID
        draft.tenantId = TENANT
        draft.userId = USER_ID
        draft.agentName = agentName
        draft.sessionId = sessionId
        draft.mergedMd = mergedMd
        draft.baseMd = baseMd
        draft.baseVersion = baseVersion
        draft.sources = MemoryDraftCodec.sourcesJson(sources)
        draft.targets = targets.takeIf { it.isNotEmpty() }?.let { MemoryDraftCodec.targetsJson(it) }
        draft.status = status
        draft.reviewedBy = reviewedBy
        draft.rejectReason = rejectReason
        return draft
    }

    private fun stubDraftOnRow(draft: MemoryDraft) {
        `when`(memoryDraftMapper.selectById(DRAFT_ID)).thenReturn(draft)
    }

    private fun stubStoreReady() {
        `when`(memoryStoreGateway.isAvailable()).thenReturn(true)
    }

    private fun stubLayer(
        content: String,
        version: Long,
    ) {
        `when`(memoryStoreGateway.readCuratedLayer(TENANT, USER_SEGMENT, AGENT))
            .thenReturn(MemoryStoreGateway.CuratedLayer(content, version))
    }

    /** What one day of the agent's own ledger holds when the approval reads it. */
    private fun stubDailyLayer(
        path: String,
        content: String,
        version: Long,
    ) {
        `when`(memoryStoreGateway.readDailyLayer(TENANT, USER_SEGMENT, AGENT, path))
            .thenReturn(MemoryStoreGateway.DailyLayer(content, version))
    }

    @Nested
    @DisplayName("Intake")
    inner class Intake {

        @Test
        @DisplayName("a session admin cannot place with an owner and an agent queues nothing")
        fun `an unattributable session is refused`() {
            `when`(mcpSessionOwnerResolver.resolve(WEB_SESSION)).thenReturn(null)

            val refused = assertThrows(BizException::class.java) { submit() }

            assertEquals(404, refused.code)
            assertTrue(refused.message!!.contains(WEB_SESSION), "the refusal has to name the id it could not place")
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a merge naming an agent its conversation did not run is refused")
        fun `a mismatched agent is refused`() {
            stubWebSession()

            val refused = assertThrows(BizException::class.java) { submit(agentName = "Other") }

            assertTrue(
                refused.message!!.contains("Other") && refused.message!!.contains(AGENT),
                "the runtime has to see which two names disagree",
            )
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a conversation in one workspace merging into another workspace's agent is refused")
        fun `a cross-tenant agent is refused`() {
            stubWebSession()
            stubAgent(tenantId = 9L)

            val refused = assertThrows(BizException::class.java) { submit() }

            assertTrue(refused.message!!.contains("9"), "the refusal names the tenant the agent actually lives in")
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("the accepted row is filed against the session's owner, tenant and agent, never the caller's")
        fun `a merge is filed against the resolved owner`() {
            stubWebSession()
            stubResolvableSources()

            val id = submit(baseVersion = 7L)

            assertEquals(NEW_ID, id)
            val stored = capturedInsert()
            assertEquals(TENANT, stored.tenantId)
            assertEquals(USER_ID, stored.userId)
            assertEquals(AGENT, stored.agentName)
            assertEquals(WEB_SESSION, stored.sessionId)
            assertEquals(MERGED, stored.mergedMd)
            assertEquals(BASE, stored.baseMd)
            assertEquals(7L, stored.baseVersion)
            assertEquals(MemoryDraft.STATUS_PENDING, stored.status)
        }

        @Test
        @DisplayName("sources are stored sorted, so two merges of one layer make the same digest")
        fun `sources are stored canonically`() {
            stubWebSession()
            stubResolvableSources()

            submit(sources = listOf(MemoryDraftSource(path = "memory/2026-10-06.md", content = "- b"), SOURCES.first()))

            assertEquals(
                listOf("MEMORY.md", "memory/2026-10-06.md"),
                MemoryDraftCodec.sourcesOf(capturedInsert()).map { it.path },
            )
        }

        @Test
        @DisplayName("a second merge replaces the conversation's open candidate instead of queueing twice")
        fun `an open candidate is rewritten`() {
            stubWebSession()
            stubResolvableSources()
            `when`(memoryDraftMapper.selectPendingBySession(TENANT, WEB_SESSION)).thenReturn(pendingDraft())
            `when`(memoryDraftMapper.updateContent(any())).thenReturn(1)

            val id = submit(mergedMarkdown = "# Memory\n- rewritten")

            assertEquals(DRAFT_ID, id, "the queue holds one candidate per conversation, so the id is the open one")
            verify(memoryDraftMapper, never()).insert(any())
            assertEquals("# Memory\n- rewritten", capturedUpdate().mergedMd)
        }

        @Test
        @DisplayName("a merge that arrives while the owner decides becomes a fresh row, not a reopened one")
        fun `a decided candidate is not reopened`() {
            stubWebSession()
            stubResolvableSources()
            `when`(memoryDraftMapper.selectPendingBySession(TENANT, WEB_SESSION)).thenReturn(pendingDraft())
            // 0 rows: the conditional UPDATE found the row no longer PENDING
            `when`(memoryDraftMapper.updateContent(any())).thenReturn(0)

            val id = submit()

            assertEquals(NEW_ID, id)
            verify(memoryDraftMapper).insert(any())
        }

        @Test
        @DisplayName("a candidate naming no source file is refused: nothing would be cleared on approval")
        fun `an empty source list is refused`() {
            stubWebSession()

            val refused = assertThrows(BizException::class.java) { submit(sources = emptyList()) }

            assertTrue(refused.message!!.contains("sources"))
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a source path no merge could have read is refused by the store's own rule")
        fun `an unresolvable source is refused`() {
            stubWebSession()
            `when`(
                memoryStoreGateway.sessionSourceKey(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(WEB_SESSION), any()),
            ).thenReturn(null)

            val refused = assertThrows(BizException::class.java) { submit() }

            assertTrue(refused.message!!.contains("MEMORY.md"), "the refusal names the path it cannot address")
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a candidate with no daily target stores a NULL column")
        fun `no daily target stores null`() {
            stubWebSession()
            stubResolvableSources()

            submit()

            assertNull(capturedInsert().targets, "NULL is the natural shape of a candidate that merged no day")
        }

        @Test
        @DisplayName("daily targets are stored sorted, so two merges of one layer make the same digest")
        fun `daily targets are stored canonically`() {
            stubWebSession()
            stubResolvableSources()
            stubResolvableTargets()

            submit(targets = listOf(TWO_TARGETS[1], TWO_TARGETS[0]))

            assertEquals(
                listOf(DAY_1, DAY_2),
                MemoryDraftCodec.targetsOf(capturedInsert()).map { it.path },
            )
            assertEquals(
                TWO_TARGETS,
                MemoryDraftCodec.targetsOf(capturedInsert()),
                "the texts an approval writes are the ones the owner read, so nothing may be lost or reordered on the way in",
            )
        }

        @Test
        @DisplayName("two targets for one day are refused: the approval could not write that day twice")
        fun `a duplicate daily target is refused`() {
            stubWebSession()
            stubResolvableSources()
            stubResolvableTargets()

            val refused = assertThrows(BizException::class.java) { submit(targets = listOf(TWO_TARGETS[0], TARGETS[0])) }

            assertTrue(refused.message!!.contains(DAY_1), "the refusal names the day it cannot place twice")
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a daily target with no text is refused: approving it would erase that day")
        fun `a blank daily target is refused`() {
            stubWebSession()
            stubResolvableSources()
            stubResolvableTargets()

            val refused = assertThrows(BizException::class.java) {
                submit(targets = listOf(TARGETS[0].copy(mergedText = "   ")))
            }

            assertTrue(refused.message!!.contains(DAY_1))
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a daily target that expects a negative version is refused")
        fun `a negative daily target version is refused`() {
            stubWebSession()
            stubResolvableSources()
            stubResolvableTargets()

            val refused = assertThrows(BizException::class.java) {
                submit(targets = listOf(TARGETS[0].copy(expectedVersion = -1L)))
            }

            assertTrue(refused.message!!.contains("-1"), "the refusal quotes the version that is not one")
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a target path that addresses no day of this agent's ledger is refused by the store's own rule")
        fun `an unresolvable daily target is refused`() {
            stubWebSession()
            stubResolvableSources()
            stubResolvableTargets()

            val refused = assertThrows(BizException::class.java) {
                // The conclusion layer has one writer and no path of its own; a proposal naming it as a target
                // would ask an approval to write MEMORY.md twice, once from the base columns and once from here.
                submit(targets = listOf(TARGETS[0].copy(path = "MEMORY.md")))
            }

            assertTrue(refused.message!!.contains("MEMORY.md"), "the refusal names the path it cannot address")
            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a candidate with no text is refused")
        fun `a blank merge is refused`() {
            stubWebSession()

            assertThrows(BizException::class.java) { submit(mergedMarkdown = "   ") }

            verify(memoryDraftMapper, never()).insert(any())
        }

        @Test
        @DisplayName("a member conversation is filed against its own agent and remembered as itself")
        fun `a team child session is unwrapped to its member agent`() {
            val child = "team-$WEB_SESSION-m$AGENT_ID"
            `when`(mcpSessionOwnerResolver.resolve(WEB_SESSION)).thenReturn(McpSessionOwner(userId = USER_ID, tenantId = TENANT))
            stubAgent()
            `when`(
                memoryStoreGateway.sessionSourceKey(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(child), any()),
            ).thenAnswer { invocation -> "store/$child/${invocation.getArgument<String>(4)}" }

            val id = submit(sessionId = child)

            assertEquals(NEW_ID, id)
            val stored = capturedInsert()
            assertEquals(child, stored.sessionId, "the layer the approval clears is the child's own bucket")
            assertEquals(AGENT, stored.agentName)
            // The root is a team session and carries the lead's id, which has no memory of its own
            verify(sessionMapper, never()).selectBySessionIdAndStatus(WEB_SESSION, 1)
        }

        private fun capturedUpdate(): MemoryDraft = argumentCaptor<MemoryDraft>().let { captor ->
            verify(memoryDraftMapper).updateContent(captor.capture())
            captor.firstValue
        }
    }

    @Nested
    @DisplayName("Decisions")
    inner class Decisions {

        @Test
        @DisplayName("the queue is read under the caller's own account, not the workspace's")
        fun `page scopes by user`() {
            loginAsMember()
            `when`(memoryDraftMapper.selectDraftList(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(emptyList())

            service.page(status = null, agentName = null, sessionId = null, pageNum = 1, pageSize = 20)

            verify(memoryDraftMapper).selectDraftList(eq(TENANT), eq(USER_ID), anyOrNull(), anyOrNull(), anyOrNull())
        }

        @Test
        @DisplayName("a status the queue cannot hold is refused rather than answered empty")
        fun `page refuses an unknown status`() {
            loginAsMember()

            val refused = assertThrows(BizException::class.java) {
                service.page(status = "EXPIRED", agentName = null, sessionId = null, pageNum = 1, pageSize = 20)
            }

            assertTrue(refused.message!!.contains("PENDING"))
            verify(memoryDraftMapper, never()).selectDraftList(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
        }

        @Test
        @DisplayName("another account's candidate answers the same way as no candidate at all")
        fun `a foreign candidate is invisible`() {
            loginAsMember()
            val other = pendingDraft().apply { userId = 88L }
            stubDraftOnRow(other)

            val missing = assertThrows(BizException::class.java) { service.detail(DRAFT_ID) }
            `when`(memoryDraftMapper.selectById(DRAFT_ID)).thenReturn(null)
            val unknown = assertThrows(BizException::class.java) { service.detail(DRAFT_ID) }

            assertEquals(404, missing.code)
            assertEquals(missing.message, unknown.message, "the queue must not confirm that a row exists for somebody else")
        }

        @Test
        @DisplayName("the internal service secret decides nothing")
        fun `a service principal is refused`() {
            SecurityUtils(sysUserMapper).init()
            `when`(sysUserMapper.selectByUsername("internal-service")).thenReturn(null)
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken("internal-service", null, ArrayList())

            val refused = assertThrows(BizException::class.java) {
                service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = "x"))
            }

            assertEquals(401, refused.code)
            verify(memoryDraftMapper, never()).selectById(any())
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("an approval carries the digest of the text it covers")
        fun `an approval without a digest is refused`() {
            loginAsMember()

            assertThrows(BizException::class.java) { service.approve(DRAFT_ID, MemoryDraftApproveRequest()) }

            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("a candidate the conversation merged again is refused until the owner re-reads it")
        fun `a changed candidate is refused`() {
            loginAsMember()
            stubDraftOnRow(pendingDraft())

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = "stale-digest"))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_DRAFT_CHANGED, answer.outcome)
            assertEquals(MemoryDraftCodec.contentDigest(pendingDraft()), answer.currentDigest)
            verify(memoryDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("an already decided candidate answers with the decision that beat this one")
        fun `a decided candidate is refused`() {
            loginAsMember()
            stubDraftOnRow(pendingDraft(status = MemoryDraft.STATUS_REJECTED, reviewedBy = "member", rejectReason = "too broad"))

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(pendingDraft())))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_ALREADY_REVIEWED, answer.outcome)
            assertEquals("too broad", answer.rejectReason)
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("a layer that moved since the merge read it is not overwritten, and the candidate stays open")
        fun `a stale base is refused`() {
            loginAsMember()
            val draft = pendingDraft()
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = "# Memory\n- somebody else approved this", version = 9L)

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_STALE_BASE, answer.outcome)
            assertEquals(9L, answer.currentBaseVersion, "the owner needs the version that moved to know what to re-run")
            verify(memoryDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
            verify(memoryStoreGateway, never()).clearSessionSources(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("a day of the ledger that moved since the merge read it refuses the whole candidate")
        fun `a stale daily target is refused and named`() {
            loginAsMember()
            val draft = pendingDraft(targets = TWO_TARGETS)
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = BASE!!, version = 3L)
            stubDailyLayer(DAY_1, content = "", version = 0L)
            // A second conversation's approval landed this day in the meantime.
            stubDailyLayer(DAY_2, content = "- somebody else merged this day", version = 5L)

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_STALE_BASE, answer.outcome)
            assertEquals(DAY_2, answer.staleTarget, "the owner has to see which of the objects moved, not just that one did")
            verify(memoryDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
            // Half a candidate is not a decision: the day that was still at its version is left alone too.
            verify(memoryStoreGateway, never()).writeDailyIfVersion(any(), any(), any(), any(), any(), any())
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
            verify(memoryStoreGateway, never()).clearSessionSources(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("an approval writes every day it names, then the conclusion layer, then clears")
        fun `the days land before the conclusion layer`() {
            loginAsMember()
            val draft = pendingDraft(targets = TWO_TARGETS)
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = BASE!!, version = 3L)
            stubDailyLayer(DAY_1, content = "", version = 0L)
            stubDailyLayer(DAY_2, content = DAY_2_BASE, version = 2L)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())).thenReturn(1)
            `when`(memoryStoreGateway.writeDailyIfVersion(TENANT, USER_SEGMENT, AGENT, DAY_1, 0L, DAY_1_MERGED)).thenReturn(true)
            `when`(memoryStoreGateway.writeDailyIfVersion(TENANT, USER_SEGMENT, AGENT, DAY_2, 2L, DAY_2_MERGED)).thenReturn(true)
            `when`(memoryStoreGateway.writeCuratedIfVersion(TENANT, USER_SEGMENT, AGENT, 3L, MERGED)).thenReturn(true)
            `when`(memoryStoreGateway.clearSessionSources(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(WEB_SESSION), any()))
                .thenReturn(MemoryStoreGateway.ClearedSources(cleared = 2, kept = 0, absent = 0))

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_APPROVED, answer.outcome)
            assertEquals(2, answer.dailyTargetsApplied)
            assertEquals(4L, answer.longTermVersion, "the conclusion layer's version says nothing about the days")
            // The order is the whole half-failure story: the days have no reader, the layer goes into every
            // conversation, so a run that stops part-way must stop with the readable object still untouched.
            val order = inOrder(memoryStoreGateway)
            order.verify(memoryStoreGateway).writeDailyIfVersion(TENANT, USER_SEGMENT, AGENT, DAY_1, 0L, DAY_1_MERGED)
            order.verify(memoryStoreGateway).writeDailyIfVersion(TENANT, USER_SEGMENT, AGENT, DAY_2, 2L, DAY_2_MERGED)
            order.verify(memoryStoreGateway).writeCuratedIfVersion(TENANT, USER_SEGMENT, AGENT, 3L, MERGED)
            order.verify(memoryStoreGateway).clearSessionSources(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(WEB_SESSION), any())
        }

        @Test
        @DisplayName("a day already holding this candidate's text is finished, not written twice")
        fun `a day already landed counts without a second write`() {
            loginAsMember()
            val draft = pendingDraft(targets = TWO_TARGETS)
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = BASE!!, version = 3L)
            // A previous attempt of this same approval wrote DAY_1 and then lost the store mid-run.
            stubDailyLayer(DAY_1, content = DAY_1_MERGED, version = 1L)
            stubDailyLayer(DAY_2, content = DAY_2_BASE, version = 2L)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())).thenReturn(1)
            `when`(memoryStoreGateway.writeDailyIfVersion(TENANT, USER_SEGMENT, AGENT, DAY_2, 2L, DAY_2_MERGED)).thenReturn(true)
            `when`(memoryStoreGateway.writeCuratedIfVersion(TENANT, USER_SEGMENT, AGENT, 3L, MERGED)).thenReturn(true)
            `when`(memoryStoreGateway.clearSessionSources(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(WEB_SESSION), any()))
                .thenReturn(MemoryStoreGateway.ClearedSources(cleared = 2, kept = 0, absent = 0))

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_APPROVED, answer.outcome)
            assertEquals(2, answer.dailyTargetsApplied, "both days are in the ledger now, whichever attempt put them there")
            verify(memoryStoreGateway, never()).writeDailyIfVersion(any(), any(), any(), eq(DAY_1), any(), any())
        }

        @Test
        @DisplayName("a day the store refuses mid-approval costs the claim and leaves the conclusion layer alone")
        fun `a lost daily race rolls the claim back`() {
            loginAsMember()
            val draft = pendingDraft(targets = TWO_TARGETS)
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = BASE!!, version = 3L)
            stubDailyLayer(DAY_1, content = "", version = 0L)
            stubDailyLayer(DAY_2, content = DAY_2_BASE, version = 2L)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())).thenReturn(1)
            `when`(memoryStoreGateway.writeDailyIfVersion(TENANT, USER_SEGMENT, AGENT, DAY_1, 0L, DAY_1_MERGED)).thenReturn(true)
            // DAY_2 moved between the preflight and its own conditional write.
            `when`(memoryStoreGateway.writeDailyIfVersion(TENANT, USER_SEGMENT, AGENT, DAY_2, 2L, DAY_2_MERGED)).thenReturn(false)

            val lost = assertThrows(BizException::class.java) {
                service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))
            }

            assertEquals(409, lost.code)
            assertTrue(lost.message!!.contains(DAY_2), "the refusal says which day the owner has to re-read")
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
            verify(memoryStoreGateway, never()).clearSessionSources(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("an approval writes the layer, then clears exactly the sources it merged out of")
        fun `an approval lands the text and clears its sources`() {
            loginAsMember()
            val draft = pendingDraft()
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = BASE!!, version = 3L)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())).thenReturn(1)
            `when`(memoryStoreGateway.writeCuratedIfVersion(TENANT, USER_SEGMENT, AGENT, 3L, MERGED)).thenReturn(true)
            `when`(memoryStoreGateway.clearSessionSources(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(WEB_SESSION), any()))
                .thenReturn(MemoryStoreGateway.ClearedSources(cleared = 2, kept = 1, absent = 0))

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_APPROVED, answer.outcome)
            assertEquals(4L, answer.longTermVersion, "the approved text is the next version of the layer")
            assertEquals(2, answer.clearedSources)
            assertEquals(1, answer.keptSources)
            assertEquals(0, answer.absentSources)
            verify(memoryStoreGateway).clearSessionSources(
                TENANT,
                USER_SEGMENT,
                AGENT,
                WEB_SESSION,
                MemoryDraftCodec.sourcesOf(draft),
            )
        }

        @Test
        @DisplayName("a write the store refuses costs the claim with it, so the candidate is still waiting")
        fun `a lost race rolls the claim back`() {
            loginAsMember()
            val draft = pendingDraft()
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = BASE!!, version = 3L)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())).thenReturn(1)
            // The layer moved between the read above and the conditional write: a second approval won.
            `when`(memoryStoreGateway.writeCuratedIfVersion(TENANT, USER_SEGMENT, AGENT, 3L, MERGED)).thenReturn(false)

            val lost = assertThrows(BizException::class.java) {
                service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))
            }

            assertEquals(409, lost.code)
            // Nothing cleared: the layer this candidate describes was never written, so its material stays put
            verify(memoryStoreGateway, never()).clearSessionSources(any(), any(), any(), any(), any())
            // The rollback itself is the transaction's behaviour; what this pins is that the claim is the only
            // thing written, so the exception after it is what undoes it.
            verify(memoryDraftMapper).markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())
        }

        @Test
        @DisplayName("two tabs decide one candidate, and the loser writes nothing")
        fun `a lost claim answers with the decision that won`() {
            loginAsMember()
            val draft = pendingDraft()
            stubDraftOnRow(draft)
            stubStoreReady()
            stubLayer(content = BASE!!, version = 3L)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())).thenReturn(0)
            `when`(memoryDraftMapper.selectById(DRAFT_ID)).thenReturn(draft, pendingDraft(status = MemoryDraft.STATUS_APPROVED, reviewedBy = "member"))

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_ALREADY_REVIEWED, answer.outcome)
            assertEquals("member", answer.reviewedBy)
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("an approval that finds its own text already in the layer completes rather than rewriting")
        fun `an already applied candidate finishes`() {
            loginAsMember()
            val draft = pendingDraft()
            stubDraftOnRow(draft)
            stubStoreReady()
            // The previous attempt wrote this text and then lost the clear, so the version moved past the base.
            stubLayer(content = MERGED, version = 4L)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_APPROVED), eq(MEMBER), anyOrNull())).thenReturn(1)
            `when`(memoryStoreGateway.clearSessionSources(eq(TENANT), eq(USER_SEGMENT), eq(AGENT), eq(WEB_SESSION), any()))
                .thenReturn(MemoryStoreGateway.ClearedSources(cleared = 2, kept = 0, absent = 0))

            val answer = service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_APPROVED, answer.outcome)
            assertEquals(4L, answer.longTermVersion, "the layer is where it is; this approval only finished the clear")
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("with no reachable store the candidate stays open and the row stays undecided")
        fun `an unreachable store refuses the approval`() {
            loginAsMember()
            val draft = pendingDraft()
            stubDraftOnRow(draft)
            `when`(memoryStoreGateway.isAvailable()).thenReturn(false)

            val refused = assertThrows(BizException::class.java) {
                service.approve(DRAFT_ID, MemoryDraftApproveRequest(expectedDigest = MemoryDraftCodec.contentDigest(draft)))
            }

            assertEquals(503, refused.code)
            verify(memoryDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
        }

        @Test
        @DisplayName("a rejection needs a reason, because the same merge comes back next window")
        fun `a blank reason is refused`() {
            loginAsMember()

            assertThrows(BizException::class.java) { service.reject(DRAFT_ID, MemoryDraftRejectRequest(reason = "  ")) }

            verify(memoryDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
        }

        @Test
        @DisplayName("a rejection closes the candidate and touches neither layer")
        fun `a rejection writes nothing to the store`() {
            loginAsMember()
            val draft = pendingDraft()
            stubDraftOnRow(draft)
            `when`(memoryDraftMapper.markReviewed(eq(DRAFT_ID), eq(MemoryDraft.STATUS_REJECTED), eq(MEMBER), eq("repeats a rule the layer holds"))).thenReturn(1)

            val answer = service.reject(DRAFT_ID, MemoryDraftRejectRequest(reason = "repeats a rule the layer holds"))

            assertEquals(MemoryDraftDecisionResponse.OUTCOME_REJECTED, answer.outcome)
            verifyNoStoreWrites()
        }

        @Test
        @DisplayName("a reason over the column's width is refused with both numbers")
        fun `an over-long reason is refused`() {
            loginAsMember()

            val refused = assertThrows(BizException::class.java) {
                service.reject(DRAFT_ID, MemoryDraftRejectRequest(reason = "x".repeat(513)))
            }

            assertTrue(refused.message!!.contains("512"))
            verify(memoryDraftMapper, never()).markReviewed(any(), any(), any(), anyOrNull())
        }

        private fun verifyNoStoreWrites() {
            verify(memoryStoreGateway, never()).writeCuratedIfVersion(any(), any(), any(), any(), any())
            verify(memoryStoreGateway, never()).clearSessionSources(any(), any(), any(), any(), any())
        }
    }

    @Nested
    @DisplayName("Digest")
    inner class Digest {

        @Test
        @DisplayName("every column a decision covers moves the digest")
        fun `the digest covers the whole decision`() {
            val baseline = pendingDraft()
            val digest = MemoryDraftCodec.contentDigest(baseline)

            assertNotEquals(digest, MemoryDraftCodec.contentDigest(pendingDraft(agentName = "Other")))
            assertNotEquals(digest, MemoryDraftCodec.contentDigest(pendingDraft(sessionId = "web-2")))
            assertNotEquals(digest, MemoryDraftCodec.contentDigest(pendingDraft(mergedMd = "# Memory\n- more")))
            assertNotEquals(digest, MemoryDraftCodec.contentDigest(pendingDraft(baseMd = "# Memory\n- other base")))
            assertNotEquals(digest, MemoryDraftCodec.contentDigest(pendingDraft(baseVersion = 4L)))
            assertNotEquals(
                digest,
                MemoryDraftCodec.contentDigest(
                    pendingDraft(sources = listOf(MemoryDraftSource(path = "MEMORY.md", content = "- a changed file"))),
                ),
            )
            assertNotEquals(
                digest,
                MemoryDraftCodec.contentDigest(pendingDraft(targets = TARGETS)),
                "the daily texts are written by this same approval, so a candidate whose ledger half moved is a different decision",
            )
            assertNotEquals(
                digest,
                MemoryDraftCodec.contentDigest(
                    pendingDraft(targets = listOf(MemoryDraftTarget(path = "memory/2026-10-05.md", expectedVersion = 4L, baseText = "- other", mergedText = "- ships on Fridays"))),
                ),
            )
            assertEquals(digest, MemoryDraftCodec.contentDigest(pendingDraft()), "the same row must digest the same way twice")
        }

        @Test
        @DisplayName("the columns a decision writes are outside the digest, because the status gate refuses first")
        fun `the digest ignores only what the status gate already refuses`() {
            val digest = MemoryDraftCodec.contentDigest(pendingDraft())

            // Not a gap: an approval checks `status == PENDING` before it compares digests, so a decided row
            // is refused by that gate rather than by a digest that would have to change with every decision.
            assertEquals(
                digest,
                MemoryDraftCodec.contentDigest(
                    pendingDraft(status = MemoryDraft.STATUS_APPROVED, reviewedBy = MEMBER, rejectReason = "dup"),
                ),
            )
        }
    }

    companion object {
        private const val MEMBER = "member"
        private const val USER_ID = 7L
        private const val USER_SEGMENT = "7"
        private const val TENANT = 4L
        private const val AGENT = "Research"
        private const val AGENT_ID = 3L
        private const val WEB_SESSION = "web-1"
        private const val DRAFT_ID = 41L
        private const val NEW_ID = 97L

        private const val BASE = "# Memory\n- the user likes terse answers"
        private const val MERGED = "# Memory\n- the user likes terse answers\n- no trailing summaries"

        private const val DAY_1 = "memory/2026-10-05.md"
        private const val DAY_2 = "memory/2026-10-06.md"
        private const val DAY_1_MERGED = "- ships on Fridays\n- the release train leaves at 18:00"
        private const val DAY_2_BASE = "- the store gate went red"
        private const val DAY_2_MERGED = "- the store gate went red\n- it passed on the second run"

        private val SOURCES = listOf(
            MemoryDraftSource(path = "MEMORY.md", content = "- the user likes terse answers"),
            MemoryDraftSource(path = DAY_1, content = "- ships on Fridays"),
        )

        /** One day of the agent's own ledger, merged out of the second source above on a layer that had none. */
        private val TARGETS = listOf(
            MemoryDraftTarget(
                path = DAY_1,
                expectedVersion = 0L,
                baseText = "",
                mergedText = DAY_1_MERGED,
            ),
        )

        /** Two days, the second one already in the ledger at version 2, so per-day preconditions are distinct. */
        private val TWO_TARGETS = TARGETS + MemoryDraftTarget(
            path = DAY_2,
            expectedVersion = 2L,
            baseText = DAY_2_BASE,
            mergedText = DAY_2_MERGED,
        )
    }
}
