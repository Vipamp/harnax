package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.tools.sdk.ToolMetaDescriptor
import com.agnetix.harnax.tools.sdk.ToolMethodDescriptor
import com.agnetix.harnax.tools.sdk.registry.ToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness

/**
 * BuiltinToolAutoRegistrar Unit Tests
 * The registrar is the only lifecycle path for builtin tools, so the convergence rules —
 * especially the prune that hard deletes — need coverage before they can be trusted.
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

    @Mock
    private lateinit var agentToolBindingMapper: AgentToolBindingMapper

    private lateinit var registrar: BuiltinToolAutoRegistrar

    @BeforeEach
    fun setUp() {
        registrar = BuiltinToolAutoRegistrar(toolRegistry, agentToolMapper, agentToolEnvParamMapper, agentToolBindingMapper)
        `when`(agentToolMapper.selectAllBuiltin()).thenReturn(emptyList())
        `when`(agentToolEnvParamMapper.selectByToolId(anyLong())).thenReturn(emptyList())
    }

    @Test
    @DisplayName("sync - one upsert per @Tool method, nothing pruned when DB matches code")
    fun `sync should upsert every method and prune nothing when the database matches the code`() {
        // Given
        val live = dbTool(id = 1L, methodName = "currentTime", toolName = TOOL_NAME)
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByBeanName(LIVE_BEAN)).thenReturn(listOf(live))

        // When
        registrar.syncBuiltinTools()

        // Then
        verify(agentToolMapper).upsertBuiltinTool(any())
        verify(agentToolMapper, never()).deleteBuiltinByIds(any())
        verify(agentToolBindingMapper, never()).deleteByToolIds(any())
        verify(agentToolEnvParamMapper, never()).deleteByToolIds(any())
    }

    @Test
    @DisplayName("prune - hard deletes rows the code no longer declares, cascading bindings and env params")
    fun `prune should hard delete stale rows and cascade bindings and env param definitions`() {
        // Given - a removed method leaves one orphan row; the code still declares two others
        val live = dbTool(id = 1L, methodName = "currentTime", toolName = TOOL_NAME)
        val otherLive = dbTool(id = 7L, methodName = "timezone", toolName = "get_timezone")
        val stale = dbTool(id = 2L, methodName = "oldMethod", toolName = "old_tool")
        givenCode(
            LIVE_BEAN to listOf(
                methodOf("currentTime", TOOL_NAME),
                methodOf("timezone", "get_timezone"),
            ),
        )
        `when`(agentToolMapper.selectByBeanName(LIVE_BEAN)).thenReturn(listOf(live, otherLive))
        `when`(agentToolMapper.selectAllBuiltin()).thenReturn(listOf(live, otherLive, stale))

        // When
        registrar.syncBuiltinTools()

        // Then
        verify(agentToolBindingMapper).deleteByToolIds(listOf(2L))
        verify(agentToolEnvParamMapper).deleteByToolIds(listOf(2L))
        verify(agentToolMapper).deleteBuiltinByIds(listOf(2L))
    }

    @Test
    @DisplayName("prune - purges a soft-deleted builtin row even though the code still declares it")
    fun `prune should purge soft-deleted residue left by the retired manual delete`() {
        // Given - the retired UI wrote active = 0; such a row would otherwise collide on re-insert
        val live = dbTool(id = 1L, methodName = "currentTime", toolName = TOOL_NAME)
        val softDeleted = dbTool(id = 3L, methodName = "timezone", toolName = "get_timezone", active = 0)
        givenCode(
            LIVE_BEAN to listOf(
                methodOf("currentTime", TOOL_NAME),
                methodOf("timezone", "get_timezone"),
            ),
        )
        `when`(agentToolMapper.selectByBeanName(LIVE_BEAN)).thenReturn(listOf(live))
        `when`(agentToolMapper.selectAllBuiltin()).thenReturn(listOf(live, softDeleted))

        // When
        registrar.syncBuiltinTools()

        // Then
        verify(agentToolMapper).deleteBuiltinByIds(listOf(3L))
    }

    @Test
    @DisplayName("prune - renaming the Java method changes the identity key and drops the old row")
    fun `prune should remove the old row when the annotated method was renamed`() {
        // Given - bean_name + method_name are the duplicate key, so a method rename is a new tool
        val oldMethod = dbTool(id = 4L, methodName = "currentTime", toolName = TOOL_NAME)
        givenCode(
            LIVE_BEAN to listOf(
                methodOf("currentTimestamp", TOOL_NAME),
                methodOf("timezone", "get_timezone"),
            ),
        )
        `when`(agentToolMapper.selectByBeanName(LIVE_BEAN)).thenReturn(
            listOf(
                dbTool(id = 9L, methodName = "currentTimestamp", toolName = TOOL_NAME),
                dbTool(id = 10L, methodName = "timezone", toolName = "get_timezone"),
            ),
        )
        `when`(agentToolMapper.selectAllBuiltin()).thenReturn(listOf(oldMethod))

        // When
        registrar.syncBuiltinTools()

        // Then
        verify(agentToolMapper).deleteBuiltinByIds(listOf(4L))
    }

    @Test
    @DisplayName("prune - skipped when more is being deleted than the code keeps")
    fun `prune should skip when the stale set reaches the live set`() {
        // Given - the brake for a broken scan: two orphans against one declared tool
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByBeanName(LIVE_BEAN)).thenReturn(emptyList())
        `when`(agentToolMapper.selectAllBuiltin()).thenReturn(
            listOf(
                dbTool(id = 2L, methodName = "oldMethod", toolName = "old_tool"),
                dbTool(id = 3L, methodName = "olderMethod", toolName = "older_tool"),
            ),
        )

        // When
        registrar.syncBuiltinTools()

        // Then
        verify(agentToolMapper, never()).deleteBuiltinByIds(any())
        verify(agentToolBindingMapper, never()).deleteByToolIds(any())
    }

    @Test
    @DisplayName("sync - no @Tool method at all means nothing is synced or deleted")
    fun `sync should skip everything when the registry yields no tool meta`() {
        // Given - the safety valve: an empty scan must never read as "every tool was deleted"
        `when`(toolRegistry.getAllToolMeta()).thenReturn(emptyMap())

        // When
        registrar.syncBuiltinTools()

        // Then
        verify(agentToolMapper, never()).upsertBuiltinTool(any())
        verify(agentToolMapper, never()).selectAllBuiltin()
        verify(agentToolMapper, never()).deleteBuiltinByIds(any())
    }

    @Test
    @DisplayName("sync - a failed tool group cancels the prune")
    fun `sync should not prune when a tool group failed to sync`() {
        // Given - upsert fails for broken-box, and the DB holds a genuine orphan that would otherwise be deleted
        val broken = dbTool(id = 5L, beanName = BROKEN_BEAN, methodName = "boom", toolName = "boom_tool")
        val live = dbTool(id = 1L, methodName = "currentTime", toolName = TOOL_NAME)
        val orphan = dbTool(id = 6L, methodName = "gone", toolName = "gone_tool")
        `when`(toolRegistry.getAllToolMeta()).thenReturn(
            mapOf(
                BROKEN_BEAN to metaOf(BROKEN_BEAN, methodOf("boom", "boom_tool")),
                LIVE_BEAN to metaOf(LIVE_BEAN, methodOf("currentTime", TOOL_NAME)),
            ),
        )
        `when`(agentToolMapper.upsertBuiltinTool(any())).thenAnswer { invocation ->
            val tool = invocation.getArgument<AgentTool>(0)
            if (tool.beanName == BROKEN_BEAN) throw RuntimeException("simulated upsert failure")
            1
        }
        `when`(agentToolMapper.selectByBeanName(LIVE_BEAN)).thenReturn(listOf(live))
        `when`(agentToolMapper.selectAllBuiltin()).thenReturn(listOf(broken, live, orphan))

        // When
        registrar.syncBuiltinTools()

        // Then
        verify(agentToolMapper, never()).deleteBuiltinByIds(any())
    }

    @Test
    @DisplayName("sync - status and active are code-owned, not operator-owned")
    fun `sync should force builtin status to enabled`() {
        // Given
        givenCode(LIVE_BEAN to listOf(methodOf("currentTime", TOOL_NAME)))
        `when`(agentToolMapper.selectByBeanName(LIVE_BEAN)).thenReturn(emptyList())

        // When
        registrar.syncBuiltinTools()

        // Then
        val captor = argumentCaptor<AgentTool>()
        verify(agentToolMapper).upsertBuiltinTool(captor.capture())
        assertEquals(1, captor.firstValue.status)
        assertEquals(1, captor.firstValue.active)
        assertEquals("BUILTIN", captor.firstValue.type)
        assertEquals("SYSTEM", captor.firstValue.creator)
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
        timeoutSeconds = 0,
        isPublic = true,
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
        type = "BUILTIN"
        this.beanName = beanName
        this.methodName = methodName
        status = 1
        this.active = active
    }

    companion object {
        private const val LIVE_BEAN = "time-tool-box"
        private const val BROKEN_BEAN = "broken-tool-box"
        private const val TOOL_NAME = "get_current_time"
    }
}
