package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatEventConverter
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agent.StreamOptions
import io.agentscope.core.message.Base64Source
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.ImageBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.sandbox.SandboxContext
import io.agentscope.harness.agent.sandbox.WorkspaceSpec
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64

/**
 * Wraps a [HarnessAgent] and exposes a streaming call API similar to
 * [com.agnetix.harnax.agent.ReActAgentWrapper].
 *
 * Key differences from [com.agnetix.harnax.agent.ReActAgentWrapper]:
 * - Uses the three-argument [HarnessAgent.stream] overload with [RuntimeContext] so that the
 *   same sessionId always binds to the same sandbox and workspace.
 * - Does NOT manually call `sessionManager.saveSession()` — session persistence is handled
 *   automatically by the built-in `SessionPersistenceHook` inside [HarnessAgent].
 *
 * @param harnessAgent the built HarnessAgent
 * @param dangerousTools set of tool names that require user confirmation
 * @param tokenStatBuilder builder for token stat recording
 * @param tokenStatAdaptor adaptor to persist token stats
 * @param sessionId session identifier, passed via RuntimeContext to bind sandbox + workspace
 * @param userId optional user identifier, passed via RuntimeContext
 * @param keepAliveSandboxManager optional manager for persistent sandbox containers
 * @param keepAliveSnapshotSpec optional snapshot spec for keepAlive sandbox workspace persistence
 * @param sandboxImage Docker image for sandbox containers (used when keepAlive is enabled)
 * @param sandboxWorkspaceRoot workspace root path inside container (used when keepAlive is enabled)
 */
class HarnessAgentWrapper(
    val harnessAgent: HarnessAgent,
    val dangerousTools: Set<String>,
    val tokenStatBuilder: TokenStatBuilder,
    val tokenStatAdaptor: TokenStatAdaptor,
    val sessionId: String,
    val userId: String? = null,
    val keepAliveSandboxManager: KeepAliveSandboxManager? = null,
    val keepAliveSnapshotSpec: SandboxSnapshotSpec? = null,
    val sandboxImage: String = "python:3.11-slim",
    val sandboxWorkspaceRoot: String = "/workspace",
) {

    private val log = LoggerFactory.getLogger(HarnessAgentWrapper::class.java)

    fun callStream(
        prompt: String,
        imageUrls: List<String> = listOf(),
        options: StreamOptions = StreamOptions.builder().build(),
    ): Flux<ChatEvent> {
        val list: MutableList<ContentBlock> = mutableListOf()
        list.add(TextBlock.builder().text(prompt).build())
        imageUrls.forEach { list.add(imageBlock(it)) }
        val msg = Msg.builder().name("user").role(MsgRole.USER).content(list).build()
        return callStreamInternal(options, msg)
    }

    fun callStream(
        options: StreamOptions = StreamOptions.builder().build(),
        msg: Msg? = null,
    ): Flux<ChatEvent> = callStreamInternal(options, *if (msg != null) arrayOf(msg) else emptyArray())

    private fun callStreamInternal(
        options: StreamOptions,
        vararg msg: Msg = arrayOf(),
    ): Flux<ChatEvent> {
        val ctxBuilder = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId(userId ?: "")

        // Inject external sandbox for keepAlive mode (Priority 1 user-managed)
        var keepAliveSandbox: io.agentscope.harness.agent.sandbox.Sandbox? = null
        if (keepAliveSandboxManager != null) {
            val sandbox = keepAliveSandboxManager.getOrCreate(
                sessionId,
                WorkspaceSpec(),
                keepAliveSnapshotSpec,
            )
            keepAliveSandbox = sandbox
            val clientOptions = DockerSandboxClientOptions()
                .image(sandboxImage)
                .workspaceRoot(sandboxWorkspaceRoot)
            val sandboxContext = SandboxContext.builder()
                .client(DockerSandboxClient())
                .clientOptions(clientOptions)
                .externalSandbox(sandbox)
                .build()
            ctxBuilder.put(SandboxContext::class.java, sandboxContext)
            log.debug("[keepAlive] Injected external sandbox for session={}", sessionId)
        }

        val runtimeCtx = ctxBuilder.build()

        return harnessAgent.stream(msg.toList(), options, runtimeCtx)
            // No sessionManager.saveSession() — SessionPersistenceHook handles this automatically
            .flatMap { ChatEventConverter.convert(it, dangerousTools) }
            .doOnNext { extracted(it) }
            .doFinally {
                // Persist workspace snapshot for keepAlive sandbox.
                // Only trigger snapshot upload — do NOT call stop() which would
                // set running=false and workspaceRootReady=true, changing sandbox state.
                if (keepAliveSandbox != null) {
                    try {
                        val snapshot = keepAliveSandbox.state.snapshot
                        if (snapshot != null && snapshot.isPersistenceEnabled) {
                            keepAliveSandbox.persistWorkspace().use { archive ->
                                snapshot.persist(archive)
                            }
                            log.debug("[keepAlive] Workspace snapshot persisted for session={}", sessionId)
                        }
                    } catch (e: Exception) {
                        log.warn("[keepAlive] Failed to persist snapshot for session={}: {}", sessionId, e.message)
                    }
                }
            }
            .concatWith(Flux.just(EndEventChatEvent()))
            .onErrorResume { e ->
                log.error("[harness] stream error for session={}: {}", sessionId, e.message, e)
                val errorEvent = if (e is HarnaxException) {
                    ErrorChatEvent.from(e)
                } else {
                    ErrorChatEvent(
                        code = HarnaxErrorCode.SYSTEM_ERROR.code,
                        message = e.message ?: "Unknown error",
                    )
                }
                Flux.just(errorEvent, EndEventChatEvent())
            }
    }

    private fun imageBlock(url: String): ImageBlock = if (url.startsWith("data:image")) {
        val parts = url.split(",")
        val mimeType = parts[0].substringAfter(":").substringBefore(";")
        val base64Data = parts[1]
        ImageBlock.builder().source(
            Base64Source.builder().data(base64Data).mediaType(mimeType).build(),
        ).build()
    } else {
        ImageBlock.builder().source(
            Base64Source.builder()
                .data(Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get(url))))
                .mediaType("image/png")
                .build(),
        ).build()
    }

    private fun extracted(it: ChatEvent) {
        if (it.tokenUsage != null) {
            tokenStatAdaptor.saveTokenStat(
                tokenStatBuilder.inputToken(it.tokenUsage!!.inputTokens)
                    .outputToken(it.tokenUsage!!.outputTokens)
                    .totalToken(it.tokenUsage!!.totalTokens)
                    .build(),
            )
        }
    }
}
