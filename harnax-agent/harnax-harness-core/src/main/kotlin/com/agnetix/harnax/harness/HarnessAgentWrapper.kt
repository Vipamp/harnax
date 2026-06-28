package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.agent.chat.MsgExtractHelper
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatEventConverter
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import io.agentscope.core.agent.RuntimeContext
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
import reactor.core.scheduler.Schedulers
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64

/**
 * Wraps a [HarnessAgent] and exposes a streaming call API.
 *
 * Key changes in agentscope 2.0.0:
 * - `stream()` → `streamEvents()` (returns `Flux<AgentEvent>` instead of `Flux<Event>`)
 * - `call()` now requires `RuntimeContext` (the old overload without ctx is deprecated)
 * - Event → AgentEvent with fine-grained subtypes
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
    ): Flux<ChatEvent> = Flux.defer {
        val list: MutableList<ContentBlock> = mutableListOf()
        list.add(TextBlock.builder().text(prompt).build())
        imageUrls.forEach { list.add(imageBlock(it)) }
        val msg = Msg.builder().name("user").role(MsgRole.USER).content(list).build()
        callStreamInternal(msg)
    }.subscribeOn(Schedulers.boundedElastic())

    fun callStream(
        msg: Msg? = null,
    ): Flux<ChatEvent> = Flux.defer {
        callStreamInternal(*if (msg != null) arrayOf(msg) else emptyArray())
    }.subscribeOn(Schedulers.boundedElastic())

    /**
     * Non-streaming call — returns a [ChatResponse] directly.
     */
    fun call(
        prompt: String,
        imageUrls: List<String> = listOf(),
    ): ChatResponse {
        val list: MutableList<ContentBlock> = mutableListOf()
        list.add(TextBlock.builder().text(prompt).build())
        imageUrls.forEach { list.add(imageBlock(it)) }
        val userMsg = Msg.builder().name("user").role(MsgRole.USER).content(list).build()
        return call(listOf(userMsg))
    }

    fun call(msgs: List<Msg>): ChatResponse {
        val ctxResult = buildRuntimeContext()
        try {
            val responseMsg = harnessAgent.call(msgs, ctxResult.runtimeContext)
                .block(Duration.ofMinutes(5))
            val content = responseMsg?.let { MsgExtractHelper.extractText(it) } ?: ""
            val thinking = responseMsg?.let { MsgExtractHelper.extractThinking(it) }
            return ChatResponse(
                sessionId = sessionId,
                content = content,
                thinking = thinking?.ifEmpty { null },
            )
        } finally {
            persistKeepAliveSnapshot(ctxResult)
        }
    }

    private fun callStreamInternal(
        vararg msg: Msg = arrayOf(),
    ): Flux<ChatEvent> {
        val ctxResult = buildRuntimeContext()
        return harnessAgent.streamEvents(msg.toList(), ctxResult.runtimeContext)
            .flatMap { agentEvent -> ChatEventConverter.convert(agentEvent, dangerousTools) }
            .doOnNext { extracted(it) }
            .doFinally { persistKeepAliveSnapshot(ctxResult) }
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

    /**
     * Persists the workspace snapshot for the keep-alive sandbox (if any).
     *
     * Called after both streaming ([callStreamInternal]) and non-streaming ([call])
     * requests complete, ensuring workspace state is saved regardless of the call path.
     * Exceptions are caught and logged so they never propagate to the caller.
     */
    private fun persistKeepAliveSnapshot(ctxResult: RuntimeContextResult) {
        val keepAliveSandbox = ctxResult.keepAliveSandbox ?: return
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

    private fun buildRuntimeContext(): RuntimeContextResult {
        val ctxBuilder = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId(userId ?: "")

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

        return RuntimeContextResult(ctxBuilder.build(), keepAliveSandbox)
    }

    private data class RuntimeContextResult(
        val runtimeContext: RuntimeContext,
        val keepAliveSandbox: io.agentscope.harness.agent.sandbox.Sandbox?,
    )
}
