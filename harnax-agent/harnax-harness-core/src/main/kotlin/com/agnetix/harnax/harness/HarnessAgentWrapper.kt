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
import io.agentscope.core.permission.PermissionMode
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.sandbox.SandboxContext
import io.agentscope.harness.agent.sandbox.WorkspaceSpec
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch

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
    val permissionMode: String = "DEFAULT",
) {

    private val log = LoggerFactory.getLogger(HarnessAgentWrapper::class.java)

    /**
     * Holds the active [Disposable] for the blocking [call] so that [interrupt] can cancel it.
     * Only one call per wrapper instance should be active at any time.
     */
    @Volatile
    private var activeCallDisposable: Disposable? = null

    /**
     * Set to `true` by [interrupt] so that [call] can detect that an exception was caused by
     * an explicit interrupt (rather than a genuine failure) and return a clean empty response.
     */
    @Volatile
    private var interrupted: Boolean = false

    /** The thread currently blocked in [call], used by [interrupt] to break out of `Mono.block()`. */
    @Volatile
    private var blockingThread: Thread? = null

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
        // Set permission mode from session configuration (defaults to DEFAULT if not configured)
        harnessAgent.setPermissionMode(ctxResult.runtimeContext, PermissionMode.fromString(permissionMode))
        try {
            val mono = harnessAgent.call(msgs, ctxResult.runtimeContext)
                .timeout(Duration.ofMinutes(5))
            // Use a single subscribe() to avoid double subscription on the cold Mono.
            // The previous subscribe()+block() pattern created two independent subscriptions,
            // causing SandboxLifecycleMiddleware to acquire/release the sandbox twice and
            // leaving the second subscription with a null sandbox reference.
            var result: Msg? = null
            var error: Throwable? = null
            val latch = CountDownLatch(1)
            blockingThread = Thread.currentThread()
            activeCallDisposable =
                mono.subscribe(
                    { msg ->
                        result = msg
                        latch.countDown()
                    },
                    { err ->
                        error = err
                        latch.countDown()
                    },
                )
            try {
                latch.await()
                if (error != null) {
                    val cause = error!!
                    // harnessAgent.interrupt() invalidates the sandbox context, which causes
                    // the agent's internal tool calls to throw various exceptions (e.g.
                    // SandboxConfigurationException, InterruptedException, CancellationException).
                    // Check the interrupted flag to distinguish an explicit stop from a real failure.
                    if (interrupted) {
                        log.info("[harness] Call interrupted for session={}, suppressed error: {}", sessionId, cause.message)
                        return ChatResponse(sessionId = sessionId, content = "", thinking = null)
                    }
                    throw cause
                }
                val content = result?.let { MsgExtractHelper.extractText(it) } ?: ""
                val thinking = result?.let { MsgExtractHelper.extractThinking(it) }
                return ChatResponse(
                    sessionId = sessionId,
                    content = content,
                    thinking = thinking?.ifEmpty { null },
                )
            } catch (e: InterruptedException) {
                if (interrupted) {
                    log.info("[harness] Call interrupted for session={}, suppressed InterruptedException", sessionId)
                    return ChatResponse(sessionId = sessionId, content = "", thinking = null)
                }
                throw e
            } finally {
                activeCallDisposable = null
                blockingThread = null
            }
        } finally {
            persistKeepAliveSnapshot(ctxResult)
        }
    }

    /**
     * Interrupt the ongoing agent execution.
     *
     * Three mechanisms are used:
     * 1. Sets [interrupted] flag so [call] can detect the interrupt and suppress resulting errors.
     * 2. Disposes the active [Disposable] from [call] — best-effort Mono subscription cancellation.
     * 3. Interrupts the blocking thread to break out of `Mono.block()`.
     * 4. Calls [HarnessAgent.interrupt] — invalidates the sandbox context, causing the agent's
     *    internal operations to fail and the Mono to complete with an error.
     */
    fun interrupt() {
        log.info("[harness] Interrupting agent for session={}", sessionId)
        // 1. Mark as interrupted BEFORE triggering any cancellation, so the catch block
        //    in call() can detect it regardless of timing.
        interrupted = true
        // 2. Cancel the Mono subscription (best-effort)
        val disposable = activeCallDisposable
        if (disposable != null && !disposable.isDisposed) {
            disposable.dispose()
            log.info("[harness] Disposed active call subscription for session={}", sessionId)
        }
        // 3. Interrupt the blocking thread to break out of Mono.block()
        val thread = blockingThread
        if (thread != null) {
            thread.interrupt()
            log.info("[harness] Interrupted blocking thread for session={}", sessionId)
        }
        // 4. Signal to agent internals — invalidates sandbox, causes agent ops to fail
        harnessAgent.interrupt()
    }

    private fun callStreamInternal(
        vararg msg: Msg = arrayOf(),
    ): Flux<ChatEvent> {
        val ctxResult = buildRuntimeContext()
        // Set permission mode from session configuration (defaults to DEFAULT if not configured)
        harnessAgent.setPermissionMode(ctxResult.runtimeContext, PermissionMode.fromString(permissionMode))
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
