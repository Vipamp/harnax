package com.agnetix.harnax.admin.util

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * McpSessionOwnerResolver Unit Tests
 *
 * This is the only place a runtime session id is turned back into a person, and what it answers
 * decides whose OAuth grant the agent gets to spend. So the cases worth pinning are the ones where a
 * reasonable-looking lookup would resolve to the *wrong* human: a mini-program row that stores an id
 * where a web row stores a name, a username shared across tenants, a creator whose account is gone.
 */
class McpSessionOwnerResolverTest {

    private val sessionMapper = org.mockito.kotlin.mock<SessionMapper>()
    private val agentTaskMapper = org.mockito.kotlin.mock<AgentTaskMapper>()
    private val sysUserMapper = org.mockito.kotlin.mock<SysUserMapper>()

    private lateinit var resolver: McpSessionOwnerResolver

    @BeforeEach
    fun setUp() {
        resolver = McpSessionOwnerResolver(sessionMapper, agentTaskMapper, sysUserMapper)
    }

    private fun session(creator: String, tenantId: Long = TENANT) = Session().apply {
        sessionId = "web-1"
        this.creator = creator
        this.tenantId = tenantId
        status = 1
    }

    private fun user(id: Long, tenantId: Long?) = SysUser().apply {
        this.id = id
        username = "tester"
        this.tenantId = tenantId
    }

    private fun stubSession(creator: String, tenantId: Long = TENANT) {
        whenever(sessionMapper.selectBySessionIdAndStatus(any(), eq(STATUS_ACTIVE))).thenReturn(session(creator, tenantId))
    }

    @Test
    @DisplayName("web 会话按 creator 的用户名解析")
    fun `a web session resolves its creator as a username`() {
        stubSession("tester")
        whenever(sysUserMapper.selectByUsername("tester")).thenReturn(user(7L, TENANT))

        val owner = resolver.resolve("web-abc")

        assertEquals(7L, owner?.userId)
        assertEquals(TENANT, owner?.tenantId)
    }

    @Test
    @DisplayName("mp 会话的 creator 是用户 id:按 id 再试一次")
    fun `a mini-program session stores a user id in creator and still resolves`() {
        // MpSessionService writes `userId.toString()` into creator, so resolving only by username
        // leaves every mobile session ownerless and an OAuth MCP silently unavailable there.
        stubSession("42")
        whenever(sysUserMapper.selectByUsername("42")).thenReturn(null)
        whenever(sysUserMapper.selectById(42L)).thenReturn(user(42L, TENANT))

        assertEquals(42L, resolver.resolve("mp-abc")?.userId)
    }

    @Test
    @DisplayName("同名用户属于别的租户时不采用")
    fun `a same-named account in another tenant is not adopted`() {
        // sys_user.username has no unique index, and the grant is keyed by user id: picking the wrong
        // row here would spend that other person's authorization.
        stubSession("tester", tenantId = 2L)
        whenever(sysUserMapper.selectByUsername("tester")).thenReturn(user(7L, tenantId = 9L))

        assertNull(resolver.resolve("web-abc"))
    }

    @Test
    @DisplayName("creator 已注销就没有身份,而不是拿名字去凑")
    fun `a deleted creator leaves no identity`() {
        stubSession("ghost")
        whenever(sysUserMapper.selectByUsername("ghost")).thenReturn(null)
        whenever(sysUserMapper.selectById(any())).thenReturn(null)

        assertNull(resolver.resolve("web-abc"))
    }

    @Test
    @DisplayName("creator 为空不查库")
    fun `a blank creator resolves to nobody without a lookup`() {
        stubSession("   ")

        assertNull(resolver.resolve("web-abc"))
        verify(sysUserMapper, never()).selectByUsername(any())
        verify(sysUserMapper, never()).selectById(any())
    }

    @Test
    @DisplayName("会话不存在就没有身份")
    fun `an unknown session resolves to nobody`() {
        whenever(sessionMapper.selectBySessionIdAndStatus(any(), eq(STATUS_ACTIVE))).thenReturn(null)

        assertNull(resolver.resolve("web-gone"))
    }

    @Test
    @DisplayName("渠道会话故意不给身份:发消息的人不是平台用户")
    fun `a channel conversation is left ownerless on purpose`() {
        assertNull(resolver.resolve("chn-xyz"))
        verify(sessionMapper, never()).selectBySessionIdAndStatus(any(), any())
    }

    @Test
    @DisplayName("未知前缀不猜")
    fun `an unknown prefix is not guessed at`() {
        assertNull(resolver.resolve("sess-1234"))
        verify(sessionMapper, never()).selectBySessionIdAndStatus(any(), any())
    }

    @Test
    @DisplayName("task 前缀取任务创建人:定时任务以创建者身份跑")
    fun `a task session resolves to the task creator`() {
        whenever(agentTaskMapper.selectAnyById(42L)).thenReturn(
            AgentTask().apply {
                id = 42L
                creator = "scheduler"
                tenantId = TENANT
            },
        )
        whenever(sysUserMapper.selectByUsername("scheduler")).thenReturn(user(8L, TENANT))

        assertEquals(8L, resolver.resolve("task-42-6f0b")?.userId)
    }

    /**
     * Contract C1 put the agent id in front of the random tail. This resolver stops at the first '-', so
     * the extra segment has to be invisible here — including after release 2, when the agent id it never
     * reads is the only thing admin used to have to query the row for.
     */
    @Test
    @DisplayName("C1: a four-segment task id resolves to the same creator")
    fun `a four-segment task session id still resolves to the task creator`() {
        whenever(agentTaskMapper.selectAnyById(42L)).thenReturn(
            AgentTask().apply {
                id = 42L
                creator = "scheduler"
                tenantId = TENANT
            },
        )
        whenever(sysUserMapper.selectByUsername("scheduler")).thenReturn(user(8L, TENANT))

        val sessionId = "task-42-100-6f0b1a2c3d4e5f60718293a4b5c6d7e8"

        assertEquals(8L, resolver.resolve(sessionId)?.userId)
        verify(agentTaskMapper).selectAnyById(42L)
    }

    @Test
    @DisplayName("task 前缀里解析不出 id 就停住")
    fun `a task session with an unparsable id resolves to nobody`() {
        assertNull(resolver.resolve("task-later-1"))
        verify(agentTaskMapper, never()).selectAnyById(any())
    }

    private companion object {
        private const val TENANT = 1L

        /** The only session status the resolver accepts, mirroring the agent-spec lookup. */
        private const val STATUS_ACTIVE = 1
    }
}
