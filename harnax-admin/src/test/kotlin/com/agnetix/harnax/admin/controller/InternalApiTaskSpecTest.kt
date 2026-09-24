package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.registrar.BuiltinToolAutoRegistrar
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.service.McpOAuthUserService
import com.agnetix.harnax.admin.service.McpStdioPolicy
import com.agnetix.harnax.admin.skill.SkillBindingResolver
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * Contract C1 from the consumer's side: a `task-` agent spec is assembled from the two ids inside the
 * session id, and from nothing else.
 *
 * This is the half of C1 that has to ship in the same commit as the producer. admin used to read
 * `agent_task` here to turn a task id into an agent id; the scheduled-task domain is moving to
 * `harnax-scheduler`, so after this change the id itself carries the agent and the row is not available.
 * A task session whose shape is not `task-{taskId}-{agentId}-{uuid}` is therefore refused outright,
 * with the expected shape named in the message — a caller that gets one of these back has a producer
 * that is out of step with this one, and "asked for agent 0" is not an acceptable way to learn that.
 *
 * Standalone on purpose: one collaborator stub, so nothing here can quietly lean on the task table again.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InternalApiTaskSpecTest {

    @Mock
    private lateinit var apiKeyMapper: ApiKeyMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var modelMapper: ModelMapper

    @Mock
    private lateinit var modelProviderMapper: ModelProviderMapper

    @Mock
    private lateinit var aesUtil: AesUtil

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var channelMapper: ChannelMapper

    @Mock
    private lateinit var toolBindingMapper: AgentToolBindingMapper

    @Mock
    private lateinit var mcpBindingMapper: AgentMcpBindingMapper

    @Mock
    private lateinit var skillBindingMapper: AgentSkillBindingMapper

    @Mock
    private lateinit var envVariableService: EnvVariableService

    @Mock
    private lateinit var agentToolMapper: AgentToolMapper

    @Mock
    private lateinit var mcpServerMapper: McpServerMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Mock
    private lateinit var cliBindingMapper: AgentCliBindingMapper

    @Mock
    private lateinit var cliMapper: CliMapper

    @Mock
    private lateinit var mcpOAuthUserService: McpOAuthUserService

    @Mock
    private lateinit var mcpStdioPolicy: McpStdioPolicy

    @Mock
    private lateinit var teamMapper: TeamMapper

    @Mock
    private lateinit var teamMemberMapper: TeamMemberMapper

    @Mock
    private lateinit var teamSkillBindingMapper: TeamSkillBindingMapper

    @Mock
    private lateinit var builtinToolAutoRegistrar: BuiltinToolAutoRegistrar

    /**
     * Delivery asks the binding resolver which skills the holder's tenant may receive. Delegating that to
     * the `skillMapper.selectByIds` stubs the cases below already set keeps those stubs describing what
     * goes out rather than how the scope is computed.
     */
    @Mock
    private lateinit var skillBindingResolver: SkillBindingResolver

    @InjectMocks
    private lateinit var controller: InternalApiController

    @BeforeEach
    fun stubSkillDelivery() {
        `when`(skillBindingResolver.deliverable(anyList(), anyLong()))
            .thenAnswer { invocation -> skillMapper.selectByIds(invocation.getArgument(0)) }
    }

    @Test
    @DisplayName("C1: the agent id in the session id is the one that gets looked up")
    fun `a four-segment task id resolves the agent named inside it`() {
        `when`(agentMapper.selectById(AGENT_ID)).thenReturn(agent(AGENT_ID))

        val result = controller.getAgentSpec(TASK_SESSION_ID)

        assertTrue(result.isSuccess(), "a C1 session id was refused: ${result.message}")
        val spec = result.data
        assertNotNull(spec)
        assertEquals(AGENT_ID, spec?.agentId, "the agent the id names is the agent the spec describes")
        assertEquals("Test Agent", spec?.agentName)
        // What the task path has always answered, and must keep answering: a scheduled run has no user to
        // confirm tools, so it is BYPASS regardless of what the session asked for.
        assertEquals("BYPASS", spec?.permissionMode)
    }

    @Test
    @DisplayName("C1: an unresolvable agent is reported by the id from the string, not by a task row")
    fun `a missing agent is named by the session id's own agent segment`() {
        `when`(agentMapper.selectById(AGENT_ID)).thenReturn(null)

        val result = controller.getAgentSpec(TASK_SESSION_ID)

        assertTrue(result.message.contains("Agent not found: $AGENT_ID"), "got: ${result.message}")
    }

    @Test
    @DisplayName("C1: the pre-C1 three-segment id is refused, naming the expected shape")
    fun `a three-segment task id is refused`() {
        // Both spellings the old producer wrote: a short random piece, and a UUID that still has its dashes.
        // `parse` answers null for them because it knows exactly one shape — the honest reading of "release 2
        // copies no rows over", which makes these strings a producer-version mismatch rather than history.
        listOf(
            "task-42-abcdef",
            "task-42-6f0b1a2c-3d4e-5f60-7182-93a4b5c6d7e8",
        ).forEach { sessionId ->
            val result = controller.getAgentSpec(sessionId)
            // The message has to say both what was wanted and what came back: a refusal that only says
            // "invalid" leaves the operator with nothing to compare the producer against.
            assertTrue(
                result.message.contains(EXPECTED),
                "$sessionId is a legacy task id with no agent in it, so it has to be refused: got ${result.message}",
            )
            assertTrue(result.message.contains(sessionId), "the refusal did not echo the id: ${result.message}")
        }
    }

    @Test
    @DisplayName("C1: a malformed id segment is refused the same way")
    fun `an unparsable task id is refused`() {
        listOf(
            "task-abc-$AGENT_ID-6f0b1a2c", // a task id that is not a number
            "task-42-abc-6f0b1a2c", // an agent id that is not a number
            "task-42-0-6f0b1a2c", // agent 0 is not an agent
            "task-0-$AGENT_ID-6f0b1a2c", // task 0 is not a task
            "task--$AGENT_ID-6f0b1a2c", // an empty segment
            "task-42-$AGENT_ID-", // nothing left for the random tail
        ).forEach { sessionId ->
            val result = controller.getAgentSpec(sessionId)
            assertTrue(
                result.message.contains(EXPECTED),
                "$sessionId is not a C1 id, so it has to be refused by format: got ${result.message}",
            )
        }
    }

    /**
     * The durable form of "admin no longer reads `agent_task` here": not a `verify(never())` over a mock
     * that can be deleted, but the constructor, which cannot hold a task-table mapper and be compiled.
     */
    @Test
    @DisplayName("C1: this controller cannot reach the task table at all")
    fun `the controller is not injected with any task-domain mapper`() {
        val injected = InternalApiController::class.java.constructors
            .maxByOrNull { it.parameterCount }!!
            .parameterTypes
            .map { it.name }

        assertTrue(
            injected.none { it.startsWith("com.agnetix.harnax.mapper.AgentTask") },
            "agent_task is on its way out of admin, so this controller must not be able to read it: $injected",
        )
    }

    private fun agent(id: Long) = Agent().apply {
        this.id = id
        name = "Test Agent"
        description = "Test agent desc"
        systemPrompt = "You are a test agent"
        modelId = 5L
    }

    private companion object {
        private const val TASK_ID = 42L
        private const val AGENT_ID = 100L

        /** What `SchedulerServiceImpl.insertRunningLog` mints, and what this has to accept. */
        private val TASK_SESSION_ID = "task-$TASK_ID-$AGENT_ID-6f0b1a2c3d4e5f60718293a4b5c6d7e8"

        /** The wording a caller sees; asserting the literal, so a renamed constant cannot pass on its own. */
        private const val EXPECTED = "Invalid task sessionId: expected task-{taskId}-{agentId}-{uuid}"
    }
}
