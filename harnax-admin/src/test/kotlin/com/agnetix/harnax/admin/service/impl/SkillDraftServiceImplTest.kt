package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.SkillDraftSubmitRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.skill.SkillReviewRecorder
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.SkillDraft
import com.agnetix.harnax.entity.SkillReviewLog
import com.agnetix.harnax.entity.dto.ChannelSessionOwner
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillDraftMapper
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

/**
 * SkillDraftServiceImpl unit tests: who a proposal is filed against, and what a reviewer ends up reading.
 *
 * The intake route is the one place an agent's own words decide which tenant sees a draft, so most of these
 * assert what was refused and whose tenant the accepted row got. The rest pins the two derived columns a
 * reviewer decides on — the per-script hash and the canonical file JSON — because the approve step later
 * compares a digest over exactly those bytes, and a submit that stored files in arrival order would make
 * that comparison describe a different skill than the one displayed.
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
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var skillReviewRecorder: SkillReviewRecorder

    private lateinit var service: SkillDraftServiceImpl

    @BeforeEach
    fun setUp() {
        service = SkillDraftServiceImpl(
            skillDraftMapper = skillDraftMapper,
            sessionMapper = sessionMapper,
            channelMapper = channelMapper,
            agentMapper = agentMapper,
            skillReviewRecorder = skillReviewRecorder,
        )
        // A generated key is what the mapper writes back; the mock has to, or the returned id says nothing
        `when`(skillDraftMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<SkillDraft>(0).id = NEW_ID
            1
        }
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
    @DisplayName("a scheduled-task run resolves its tenant from the agent id carried in the session id")
    fun `task sessions resolve through contract C1`() {
        val sessionId = TaskSessionId.of(taskId = 11L, agentId = 4L)
        `when`(agentMapper.selectById(4L)).thenReturn(
            Agent().apply {
                id = 4L
                tenantId = 6L
            },
        )

        submit(sessionId = sessionId)

        val stored = capturedInsert()
        assertEquals(6L, stored.tenantId)
        assertEquals(4L, stored.agentId)
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

    private companion object {
        private const val TENANT = 7L
        private const val WEB_SESSION = "web-0f2a"
        private const val NEW_ID = 55L
    }
}
