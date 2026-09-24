package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolEnvParam
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.tools.sdk.ToolEnvParamDescriptor
import com.agnetix.harnax.tools.sdk.ToolMetaDescriptor
import com.agnetix.harnax.tools.sdk.ToolMethodDescriptor
import com.agnetix.harnax.tools.sdk.registry.ToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.mockito.kotlin.any as kAny

/**
 * BuiltinToolAutoRegistrar Unit Tests
 *
 * The registrar is the only lifecycle path for builtin tools, and it is additive: a declaration it
 * does not find is inserted, one it finds is refreshed when a code-owned column differs, and nothing
 * is ever deleted. These cases pin those three rules plus the duplicate-name refusal.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuiltinToolAutoRegistrarTest {

    @Mock
    private lateinit var toolRegistry: ToolRegistry

    @Mock
    private lateinit var agentToolMapper: AgentToolMapper

    @Mock
    private lateinit var agentToolEnvParamMapper: AgentToolEnvParamMapper

    private lateinit var registrar: BuiltinToolAutoRegistrar

    @BeforeEach
    fun setUp() {
        registrar = BuiltinToolAutoRegistrar(toolRegistry, agentToolMapper, agentToolEnvParamMapper)
        `when`(agentToolEnvParamMapper.selectByToolId(anyLong())).thenReturn(emptyList())
    }

    @Test
    @DisplayName("sync - a declaration the table does not hold is inserted")
    fun `sync should insert a tool the database does not hold`() {
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByName(TOOL_NAME)).thenReturn(null)

        registrar.syncBuiltinTools()

        val captor = argumentCaptor<AgentTool>()
        verify(agentToolMapper).insert(captor.capture())
        verify(agentToolMapper, never()).updateById(kAny())

        val inserted = captor.firstValue
        assertEquals(TOOL_NAME, inserted.name)
        assertEquals(LIVE_BEAN, inserted.beanName)
        assertEquals(1, inserted.status)
        assertEquals(1, inserted.active)
        assertEquals("SYSTEM", inserted.creator)
    }

    @Test
    @DisplayName("sync - a matching row is left untouched when no code-owned column differs")
    fun `sync should not write when the row already matches the declaration`() {
        val live = dbTool(id = 1L, methodName = "currentTime", toolName = TOOL_NAME).apply {
            displayName = TOOL_NAME
            description = "desc of $TOOL_NAME"
        }
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByName(TOOL_NAME)).thenReturn(live)

        registrar.syncBuiltinTools()

        verify(agentToolMapper, never()).insert(kAny())
        verify(agentToolMapper, never()).updateById(kAny())
    }

    @Test
    @DisplayName("sync - only the columns that differ are reported, and the row keeps its id")
    fun `sync should refresh a row whose code-owned columns differ`() {
        val live = dbTool(id = 7L, methodName = "oldMethod", toolName = TOOL_NAME).apply {
            displayName = "stale display name"
            description = "desc of $TOOL_NAME"
        }
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByName(TOOL_NAME)).thenReturn(live)

        registrar.syncBuiltinTools()

        val captor = argumentCaptor<AgentTool>()
        verify(agentToolMapper).updateById(captor.capture())
        verify(agentToolMapper, never()).insert(kAny())

        val updated = captor.firstValue
        assertEquals(7L, updated.id, "the row is refreshed in place so its agent bindings survive")
        assertEquals("currentTime", updated.methodName)
        assertEquals(TOOL_NAME, updated.displayName)
        assertEquals(live.createTime, updated.createTime)
    }

    @Test
    @DisplayName("sync - a disabled row converges back to enabled, the sync being the only writer")
    fun `sync should force status back to enabled`() {
        val disabled = dbTool(id = 3L, methodName = "currentTime", toolName = TOOL_NAME).apply {
            displayName = TOOL_NAME
            description = "desc of $TOOL_NAME"
            status = 0
        }
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByName(TOOL_NAME)).thenReturn(disabled)

        registrar.syncBuiltinTools()

        val captor = argumentCaptor<AgentTool>()
        verify(agentToolMapper).updateById(captor.capture())
        assertEquals(1, captor.firstValue.status)
    }

    @Test
    @DisplayName("sync - a row the code no longer declares is neither updated nor deleted")
    fun `sync should leave an undeclared row alone`() {
        val orphan = dbTool(id = 42L, methodName = "removedMethod", toolName = "removed_tool", beanName = "removed-tool-box")
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByName(TOOL_NAME)).thenReturn(null)
        // The orphan's name is simply never looked up: the sync resolves by declaration, not by table scan
        `when`(agentToolMapper.selectByName("removed_tool")).thenReturn(orphan)

        registrar.syncBuiltinTools()

        verify(agentToolMapper, never()).updateById(kAny())
        assertTrue("removed_tool" !in registrar.registeredToolNames(), "an undeclared row is not deliverable")
    }

    @Test
    @DisplayName("sync - two declarations sharing one name are refused before anything is written")
    fun `sync should refuse duplicate names on the classpath`() {
        givenCode(
            "first-tool-box" to listOf(methodOf("runFirst", SHARED_NAME)),
            "second-tool-box" to listOf(methodOf("runSecond", SHARED_NAME)),
        )

        val failure = assertThrows<IllegalStateException> { registrar.syncBuiltinTools() }

        assertTrue(failure.message!!.contains(SHARED_NAME))
        assertTrue(failure.message!!.contains("first-tool-box::runFirst"))
        assertTrue(failure.message!!.contains("second-tool-box::runSecond"))
        verify(agentToolMapper, never()).insert(kAny())
        verify(agentToolMapper, never()).updateById(kAny())
    }

    @Test
    @DisplayName("sync - nothing happens when the registry yields no tool meta")
    fun `sync should skip everything when the registry yields no tool meta`() {
        `when`(toolRegistry.getAllToolMeta()).thenReturn(emptyMap())

        registrar.syncBuiltinTools()

        verify(agentToolMapper, never()).insert(kAny())
        verify(agentToolMapper, never()).updateById(kAny())
        assertTrue(registrar.registeredToolNames().isEmpty())
    }

    @Test
    @DisplayName("sync - one failing group does not stop the others, and its tools stay declared")
    fun `sync should isolate a failing tool group`() {
        val broken = methodOf("runBroken", "broken_tool")
        val healthy = methodOf("runHealthy", "healthy_tool")
        givenCode(
            BROKEN_BEAN to listOf(broken),
            LIVE_BEAN to listOf(healthy),
        )
        `when`(agentToolMapper.selectByName("broken_tool")).thenReturn(null)
        `when`(agentToolMapper.selectByName("healthy_tool")).thenReturn(null)
        `when`(agentToolMapper.insert(kAny())).thenAnswer { invocation ->
            val tool = invocation.getArgument<AgentTool>(0)
            if (tool.name == "broken_tool") throw RuntimeException("simulated insert failure")
            1
        }

        registrar.syncBuiltinTools()

        // Both groups were attempted, so the healthy one was not skipped because the other threw
        val captor = argumentCaptor<AgentTool>()
        verify(agentToolMapper, times(2)).insert(captor.capture())
        assertEquals(listOf("broken_tool", "healthy_tool"), captor.allValues.map { it.name })
        assertEquals(
            setOf("broken_tool", "healthy_tool"),
            registrar.registeredToolNames(),
            "a write failure must not turn into a missing tool in every agent",
        )
    }

    @Test
    @DisplayName("sync - env param definitions follow the tool they belong to")
    fun `sync should converge env param definitions`() {
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME).copy(envParamDescriptors = listOf(MANDATORY_ENV))))
        `when`(agentToolMapper.selectByName(TOOL_NAME)).thenReturn(null)
        `when`(agentToolEnvParamMapper.selectByToolId(anyLong())).thenReturn(listOf(staleEnvParam()))

        registrar.syncBuiltinTools()

        val captor = argumentCaptor<AgentToolEnvParam>()
        verify(agentToolEnvParamMapper).insert(captor.capture())
        assertEquals(MANDATORY_ENV.key, captor.firstValue.envParamName)
        assertEquals(1, captor.firstValue.required)
        verify(agentToolEnvParamMapper).deleteById(99L)
    }

    private fun givenCode(vararg groups: Pair<String, List<ToolMethodDescriptor>>) {
        val allMeta = groups.groupBy({ it.first }, { it.second })
            .map { (beanName, methods) -> beanName to metaOf(beanName, *methods.flatten().toTypedArray()) }
            .toMap()
        `when`(toolRegistry.getAllToolMeta()).thenReturn(allMeta)
    }

    private fun methodOf(
        methodName: String,
        toolName: String,
    ): ToolMethodDescriptor = ToolMethodDescriptor(
        methodName = methodName,
        toolName = toolName,
        displayName = toolName,
        displayNameZh = "",
        description = "desc of $toolName",
        readOnly = false,
        needConfirm = false,
        envParamDescriptors = emptyList(),
        isRequired = false,
    )

    private fun metaOf(
        beanName: String,
        vararg methods: ToolMethodDescriptor,
    ): ToolMetaDescriptor = ToolMetaDescriptor(beanName = beanName, toolName = beanName, methods = methods.toList())

    private fun dbTool(
        id: Long,
        methodName: String,
        toolName: String,
        beanName: String = LIVE_BEAN,
        active: Int = 1,
    ): AgentTool = AgentTool().apply {
        this.id = id
        name = toolName
        this.beanName = beanName
        this.methodName = methodName
        status = 1
        this.active = active
    }

    private fun staleEnvParam(): AgentToolEnvParam = AgentToolEnvParam().apply {
        id = 99L
        toolId = 1L
        envParamName = "REMOVED_KEY"
        required = 0
        secret = 0
    }

    companion object {
        private const val LIVE_BEAN = "time-tool-box"
        private const val BROKEN_BEAN = "broken-tool-box"
        private const val TOOL_NAME = "get_current_time"
        private const val SHARED_NAME = "shared_tool"
        private val MANDATORY_ENV = ToolEnvParamDescriptor(
            key = "SMTP_HOST",
            description = "SMTP host",
            required = true,
            secret = false,
            defaultValue = "",
        )
    }
}
