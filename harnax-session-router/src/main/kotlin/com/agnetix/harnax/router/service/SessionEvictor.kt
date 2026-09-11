package com.agnetix.harnax.router.service

import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.router.service.impl.ThrottledWarn
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Releases a session on the instance that no longer serves it.
 *
 * When the router moves a session, nothing tells the instance it came from: the sandbox container,
 * the cached agent and any half-open stream stay there, so a fleet that failovers often ends up
 * running several live sandboxes per session on instances nobody routes to.
 *
 * Three things make this safe to fire from a request path:
 *
 * * It runs off the request thread and never propagates a failure. The user is already talking to the
 *   new instance; an instance that will not answer an eviction will answer it never.
 * * It asks the binding store whether the session really left before sending anything. During a Redis
 *   outage the router places sessions from node-local knowledge, and evicting on that would stop the
 *   sandbox of a session that never actually moved.
 * * It sends `STOP_SANDBOX`, not `CLEAR`. CLEAR also deletes the conversation history and the plans,
 *   and those live in MySQL that every instance shares — an instance dropping a session must not
 *   delete what the next instance still needs. STOP_SANDBOX persists the workspace snapshot first, so
 *   a session that later comes back to this instance gets its files restored.
 */
@Service
class SessionEvictor(
    private val sessionMappingService: SessionMappingService,
    private val instanceRegistry: InstanceRegistry,
    private val agentServiceClient: AgentServiceClient,
    @Value($$"${router.migration.evict-old-instance:true}")
    private val enabled: Boolean,
    @Value($$"${router.migration.max-pending:200}")
    maxPending: Int,
) {

    private val log = LoggerFactory.getLogger(SessionEvictor::class.java)
    private val warn = ThrottledWarn()
    private val dropped = AtomicLong()

    private val executor = ThreadPoolExecutor(
        1,
        2,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(maxPending.coerceAtLeast(1)),
        { runnable -> Thread(runnable, "session-evictor").apply { isDaemon = true } },
    ) { _, _ ->
        val total = dropped.incrementAndGet()
        if (total == 1L || total % 100 == 0L) {
            log.warn("Eviction queue is full; $total eviction(s) dropped so far. Sessions are moving faster than instances can be told.")
        }
    }

    /**
     * Note that [sessionId] may have left [fromInstanceId]. Does nothing when the session was never
     * placed anywhere, or when the eviction backlog is saturated.
     */
    fun requestEviction(
        sessionId: String,
        fromInstanceId: String?,
    ) {
        if (!enabled || fromInstanceId == null) return
        try {
            executor.execute {
                try {
                    runBlocking { evict(sessionId, fromInstanceId) }
                } catch (e: Exception) {
                    warn.log(log, "Eviction of session $sessionId from $fromInstanceId did not complete", e)
                }
            }
        } catch (e: Exception) {
            // Only a shutting-down executor gets here. The session has moved either way; the old
            // instance keeps a sandbox nobody points at and its own TTL will decide what happens.
            log.debug("Not evicting session {} from {}: {}", sessionId, fromInstanceId, e.message)
        }
    }

    private suspend fun evict(
        sessionId: String,
        oldInstanceId: String,
    ) {
        val current = try {
            sessionMappingService.getInstanceId(sessionId)
        } catch (e: Exception) {
            // The store cannot confirm the session left, so the session has not verifiably left.
            return
        }
        if (current == oldInstanceId) {
            log.debug("Session {} came back to {}; nothing to evict", sessionId, oldInstanceId)
            return
        }

        val oldInstance = try {
            instanceRegistry.getInstance(oldInstanceId)
        } catch (e: Exception) {
            null
        } ?: run {
            // An instance the registry forgot is one whose address we no longer trust.
            log.debug("Instance {} is no longer registered; not evicting session {}", oldInstanceId, sessionId)
            return
        }

        try {
            val answer = agentServiceClient.command(
                oldInstance.getBaseUrl(),
                CommandAgentRequest(sessionId = sessionId, command = CommandType.STOP_SANDBOX),
            )
            if (answer.isSuccess()) {
                log.info("Released session {} on instance {}", sessionId, oldInstanceId)
            } else {
                warn.log(log, "Instance $oldInstanceId refused to release session $sessionId: ${answer.message}")
            }
        } catch (e: Exception) {
            warn.log(log, "Could not release session $sessionId on $oldInstanceId", e)
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        executor.shutdownNow()
    }
}
