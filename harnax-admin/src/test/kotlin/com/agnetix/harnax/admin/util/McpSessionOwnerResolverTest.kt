package com.agnetix.harnax.admin.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.entity.dto.AgentTaskOwner
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * McpSessionOwnerResolver Unit Tests
 *
 * This is the only place a runtime session id is turned back into a person, and what it answers
 * decides whose OAuth grant the agent gets to spend. So the cases worth pinning are the ones where a
 * reasonable-looking lookup would resolve to the *wrong* human: a mini-program row that stores an id
 * where a web row stores a name, a username shared across tenants, a creator whose account is gone.
 *
 * The task branch has a second kind of case, since contract C5: it reads the row from the scheduler over
 * HTTP instead of querying a table itself, so whatever the scheduler fails to answer this resolver answers
 * nobody, and the failure it has to be audible about is the one nobody notices from the outside - an
 * OAuth tool that quietly stopped being offered for one execution.
 */
class McpSessionOwnerResolverTest {

    private val sessionMapper = org.mockito.kotlin.mock<SessionMapper>()
    private val schedulerClient = org.mockito.kotlin.mock<SchedulerClient>()
    private val sysUserMapper = org.mockito.kotlin.mock<SysUserMapper>()

    private lateinit var resolver: McpSessionOwnerResolver

    @BeforeEach
    fun setUp() {
        resolver = McpSessionOwnerResolver(sessionMapper, schedulerClient, sysUserMapper)
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

    /** What the scheduler's C5 endpoint answers for task [id]; `agentId` is along but unread here. */
    private fun stubOwner(
        id: Long,
        creator: String,
        tenantId: Long,
    ) {
        whenever(schedulerClient.taskOwner(id)).thenReturn(
            ResultVo.success<AgentTaskOwner?>(AgentTaskOwner(creator = creator, tenantId = tenantId, agentId = 100L)),
        )
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
        stubOwner(42L, creator = "scheduler", tenantId = TENANT)
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
        stubOwner(42L, creator = "scheduler", tenantId = TENANT)
        whenever(sysUserMapper.selectByUsername("scheduler")).thenReturn(user(8L, TENANT))

        val sessionId = "task-42-100-6f0b1a2c3d4e5f60718293a4b5c6d7e8"

        assertEquals(8L, resolver.resolve(sessionId)?.userId)
        // One call to the one endpoint that can answer this, and no admin-side session read: C5 is the
        // whole of admin's remaining dependence on the task row.
        verify(schedulerClient).taskOwner(42L)
        verify(sessionMapper, never()).selectBySessionIdAndStatus(any(), any())
    }

    @Test
    @DisplayName("task 前缀里解析不出 id 就停住")
    fun `a task session with an unparsable id resolves to nobody`() {
        assertNull(resolver.resolve("task-later-1"))
        verify(schedulerClient, never()).taskOwner(any())
    }

    /**
     * "No such task" is a successful answer with no data, which is how admin's own internal endpoints
     * report a miss (spec §5 C5) — so it has to land here as ownerless, not as an error the execution
     * sees.
     */
    @Test
    @DisplayName("C5: scheduler 说没有这条任务时不解析出任何人")
    fun `a task the scheduler does not have resolves to nobody`() {
        whenever(schedulerClient.taskOwner(42L)).thenReturn(ResultVo.success<AgentTaskOwner?>(null))

        assertNull(resolver.resolve("task-42-6f0b"))
        verify(sysUserMapper, never()).selectByUsername(any())
    }

    @Test
    @DisplayName("C5: scheduler 不可达时不解析出任何人，且留下 WARN")
    fun `an unreachable scheduler resolves a task session to nobody and says so`() {
        // `SchedulerClientImpl` turns a dead port or a 5xx into this answer; the resolver must not read
        // it as "no owner" and stay quiet, because the only visible consequence is one missing tool.
        whenever(schedulerClient.taskOwner(42L))
            .thenReturn(ResultVo.error("Scheduler service unavailable: Connection refused (Connection refused)"))

        val events = captureLogs { assertNull(resolver.resolve("task-42-6f0b")) }

        val warn = events.singleOrNull { it.level == Level.WARN }
        assertTrue(
            warn != null && "42" in warn.formattedMessage && "Scheduler" in warn.formattedMessage,
            "expected one WARN naming task 42 and the reason, got ${events.map { "${it.level}: ${it.formattedMessage}" }}",
        )
    }

    @Test
    @DisplayName("C5: 非 200 的业务码同样按无人解析处理并 WARN")
    fun `a business failure from the owner endpoint resolves to nobody`() {
        // 40903 is this scheduler's own "not this node" answer; any other non-200 takes the same road.
        whenever(schedulerClient.taskOwner(42L)).thenReturn(ResultVo.error(40903, "Scheduling is disabled on this instance"))

        val events = captureLogs { assertNull(resolver.resolve("task-42-6f0b")) }

        assertTrue(
            events.any { it.level == Level.WARN && "42" in it.formattedMessage },
            "expected a WARN naming task 42, got ${events.map { "${it.level}: ${it.formattedMessage}" }}",
        )
        verify(sysUserMapper, never()).selectByUsername(any())
    }

    /**
     * The client is not supposed to throw — it folds its own failures into a `ResultVo` — so this is the
     * guard against a future collaborator that does: nothing from this cold path may reach the execution,
     * whose only other option would be to fail a task over an MCP tool.
     */
    @Test
    @DisplayName("C5: owner 查询抛出的异常不得逃进任务执行")
    fun `an exception from the owner endpoint is swallowed into a null owner`() {
        whenever(schedulerClient.taskOwner(42L)).thenThrow(IllegalStateException("client blew up"))

        val events = captureLogs { assertNull(resolver.resolve("task-42-6f0b")) }

        assertTrue(
            events.any { it.level == Level.WARN && "42" in it.formattedMessage },
            "expected a WARN naming task 42, got ${events.map { "${it.level}: ${it.formattedMessage}" }}",
        )
    }

    @Test
    @DisplayName("C5: 属主的账号已注销就没有身份")
    fun `a task creator whose account is gone resolves to nobody`() {
        stubOwner(42L, creator = "ghost", tenantId = TENANT)
        whenever(sysUserMapper.selectByUsername("ghost")).thenReturn(null)
        whenever(sysUserMapper.selectById(any())).thenReturn(null)

        assertNull(resolver.resolve("task-42-6f0b"))
    }

    /**
     * The tenant now arrives over HTTP instead of from the row this service read itself, which makes the
     * comparison more important rather than less: a same-named account in another tenant must still not
     * spend its grant for a task session that is not its own.
     */
    @Test
    @DisplayName("C5: 属主同名账号在别的租户时不采用")
    fun `a task creator whose name matches another tenant's account is not adopted`() {
        stubOwner(42L, creator = "scheduler", tenantId = TENANT)
        whenever(sysUserMapper.selectByUsername("scheduler")).thenReturn(user(8L, tenantId = 9L))

        assertNull(resolver.resolve("task-42-6f0b"))
    }

    /** Events the resolver logged while [block] ran; for these paths the level is the whole contract. */
    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(McpSessionOwnerResolver::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return try {
            block()
            appender.list
        } finally {
            logger.detachAppender(appender)
        }
    }

    private companion object {
        private const val TENANT = 1L

        /** The only session status the resolver accepts, mirroring the agent-spec lookup. */
        private const val STATUS_ACTIVE = 1
    }
}
