package com.agnetix.harnax.channel.service.bootstrap

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionStatus
import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.sdk.util.ReconnectBackoff
import com.agnetix.harnax.channel.service.adaptor.RouterAgentAdaptor
import com.agnetix.harnax.channel.service.client.RouterClient
import com.agnetix.harnax.channel.service.manager.ChannelAdaptorRegistry
import com.agnetix.harnax.channel.service.mapper.ChannelEntityConverter
import com.agnetix.harnax.channel.service.monitor.ChannelRuntimeMonitor
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.mapper.ChannelMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the in-process channel listeners aligned with the `channel` table.
 *
 * The reconcile loop applies four things, in this order:
 *
 * 1. ownership — only the instance holding [ChannelListenerLockGuard]'s lock may listen, so two
 *    replicas cannot consume the same platform's messages.
 * 2. membership — channels added / removed / disabled in the DB get started / stopped.
 * 3. config drift — a changed config fingerprint restarts that one listener.
 * 4. liveness — a listener whose transport stopped serving gets restarted. This is the case the
 *    previous version could not see: `startChannelWithAgent` was called once, its return value
 *    discarded, and a WebSocket that died an hour later still looked "running".
 *
 * Restarts are rate limited twice over — exponential per-channel backoff, plus a cap on how many
 * listeners one round may start. Without the cap, one router outage that drops thirty channels at
 * once would spend every following round rebuilding thirty WebSockets and re-authenticating thirty
 * times against platforms that are rate limiting us, and never finish the round.
 *
 * Change detection uses [configFingerprint] and not `update_time`: MySQL DATETIME has second
 * precision, so two edits in the same second are indistinguishable, while an unrelated column edit
 * (rename, description) would otherwise bounce a live connection for no reason.
 */
@Component
class ChannelBootstrapRunner(
    private val channelMapper: ChannelMapper,
    private val adaptorRegistry: ChannelAdaptorRegistry,
    private val routerClient: RouterClient,
    private val sessionManager: ChannelSessionManager,
    private val monitor: ChannelRuntimeMonitor,
    private val lockGuard: ChannelListenerLockGuard,
    private val metricsSink: ChannelMetricsSink,
    @Value("\${channel.sync.max-starts-per-cycle:5}") private val maxStartsPerCycle: Int,
    @Value("\${channel.sync.restart-backoff-initial-ms:2000}") private val restartBackoffInitialMs: Long,
    @Value("\${channel.sync.restart-backoff-max-ms:300000}") private val restartBackoffMaxMs: Long,
    @Value("\${channel.sync.stale-heartbeat-ms:180000}") private val staleHeartbeatMs: Long,
) {

    private val log = LoggerFactory.getLogger(ChannelBootstrapRunner::class.java)

    private val routerAgentAdaptor: AgentAdaptor by lazy { RouterAgentAdaptor(routerClient) }

    /** Pre-configured chat service with workspace file delivery (no MinIO round-trip). */
    private val chatService = ChannelChatService(
        sessionManager = sessionManager,
        workspaceFileDownloader = { sessionId, filePath -> routerClient.downloadWorkspaceFile(sessionId, filePath) },
    )

    private val runningChannels = ConcurrentHashMap<Long, RunningChannel>()

    private val backoffs = ConcurrentHashMap<Long, ReconnectBackoff>()

    private val reconcileLock = Any()

    @Volatile
    private var shuttingDown = false

    // ==================== Startup Entry ====================

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        log.info("Application started, initiating first channel listener load")
        reconcile()
    }

    // ==================== Scheduled Polling ====================

    /**
     * Detects `channel` table changes and syncs listener state. fixedDelay keeps rounds from
     * overlapping even when a round outlives the interval, which a slow platform handshake can do.
     */
    @Scheduled(fixedDelayString = "\${channel.sync.interval-ms:10000}")
    fun scheduledReconcile() {
        reconcile()
    }

    // ==================== Core Reconcile Logic ====================

    fun reconcile() {
        if (shuttingDown) return
        synchronized(reconcileLock) {
            if (shuttingDown) return

            if (!lockGuard.hold()) {
                // Another replica owns the platforms. If we were listening until now, stop:
                // two listeners on one credential means duplicate or lost user messages.
                if (runningChannels.isNotEmpty()) {
                    stopAll("another instance owns the channel listener lock")
                }
                return
            }

            val dbChannels = try {
                channelMapper.selectAutoStartChannels()
            } catch (e: Exception) {
                log.error("Failed to load channel config, skipping this reconcile round: {}", e.message, e)
                return
            }

            val dbMap = dbChannels.associateBy { it.id }
            // A bounded start budget per round, spent in ascending id order so every replica —
            // and every log — converges on the same channel being served first.
            val result = RoundResult(if (maxStartsPerCycle <= 0) Int.MAX_VALUE else maxStartsPerCycle)

            // 1) Running here but gone from the DB (deleted, disabled, out of auto-start).
            runningChannels.keys.filter { !dbMap.containsKey(it) }.forEach { channelId ->
                if (stopChannel(channelId, "removed or disabled in DB")) result.stopped++
            }

            // 2) In the DB but not running yet.
            dbMap.keys.filter { !runningChannels.containsKey(it) }.sorted().forEach { channelId ->
                val entity = dbMap[channelId] ?: return@forEach
                result.withinBudget { if (startChannel(entity)) result.started++ }
            }

            // 3) Already running: config drift, failed start, dead transport, silent heartbeat.
            dbMap.filterKeys { runningChannels.containsKey(it) }.forEach { (channelId, entity) ->
                val running = runningChannels[channelId] ?: return@forEach
                reconcileRunning(entity, running, result)
            }

            report(result)
        }
    }

    private fun reconcileRunning(
        entity: Channel,
        running: RunningChannel,
        result: RoundResult,
    ) {
        val channelId = entity.id

        if (configFingerprint(entity) != running.configFingerprint) {
            // A config edit is a deliberate act: retry now instead of waiting out the backoff.
            result.withinBudget {
                if (restartChannel(entity, running, "config changed")) result.restarted++
            }
            return
        }

        if (running.startFailed) {
            if (!retryDue(channelId, running)) return
            result.withinBudget {
                if (restartChannel(entity, running, "previous start attempt failed")) result.restarted++
            }
            return
        }

        if (!running.listening) {
            // Webhook-style channel: nothing to keep alive.
            backoffs.remove(channelId)
            return
        }

        val state = monitor.stateOf(channelId)
        val reason = when {
            isHeartbeatStale(state) -> "no heartbeat for ${staleHeartbeatMs}ms"
            state.isServing() -> {
                backoffOf(channelId).reset()
                null
            }
            retryDue(channelId, running) -> "connection is ${state.status}"
            else -> null
        } ?: return

        result.withinBudget {
            if (restartChannel(entity, running, reason)) result.restarted++
        }
    }

    // ==================== Single Channel Start/Stop ====================

    /**
     * @return true when the channel ended up registered with this instance (listening or not).
     */
    private fun startChannel(entity: Channel): Boolean {
        val spec = try {
            ChannelEntityConverter.toSpec(entity)
        } catch (e: Exception) {
            recordStartupFailure(entity, "invalid config: ${e.message}")
            return false
        }

        val fingerprint = configFingerprint(entity)

        if (spec.communicationMode.equals("webhook", ignoreCase = true)) {
            // Callback mode: the platform delivers messages to an HTTP endpoint instead of a
            // long-lived connection we own, so there is nothing to keep alive here.
            // Registering the channel still keeps it out of the "missing listener" set.
            monitor.clearStartupFailure(entity.id)
            monitor.trackExpected(spec)
            runningChannels[entity.id] = RunningChannel(
                spec = spec,
                configFingerprint = fingerprint,
                listening = false,
                lastStartAttemptAt = System.currentTimeMillis(),
            )
            log.info("Channel id={} uses webhook callback mode, no active listener started", entity.id)
            return true
        }

        if (!adaptorRegistry.contains(spec.type)) {
            recordStartupFailure(entity, "no adaptor registered for channel type ${spec.type.code}")
            return false
        }

        log.info(
            "Starting channel id={}, name={}, type={}, mode={}",
            entity.id,
            entity.name,
            entity.type,
            entity.communicationMode,
        )
        val listening = try {
            adaptorRegistry.get(spec.type).startChannelWithAgent(spec, routerAgentAdaptor, sessionManager, chatService)
        } catch (e: Exception) {
            recordStartupFailure(entity, "start failed: ${e.message}")
            log.error("Failed to start channel id={}, name={}, type={}: {}", entity.id, entity.name, entity.type, e.message, e)
            return false
        }

        monitor.clearStartupFailure(entity.id)
        monitor.trackExpected(spec)
        runningChannels[entity.id] = RunningChannel(
            spec = spec,
            configFingerprint = fingerprint,
            listening = listening,
            lastStartAttemptAt = System.currentTimeMillis(),
        )
        if (!listening) {
            log.info("Channel id={} reported no active listener, tracked as config-only", entity.id)
        }
        return true
    }

    private fun restartChannel(
        entity: Channel,
        running: RunningChannel,
        reason: String,
    ): Boolean {
        val channelId = entity.id
        val previousState = monitor.stateOf(channelId)
        val aliveMs = System.currentTimeMillis() - running.lastStartAttemptAt
        log.info(
            "Restarting channel id={}, name={}, reason={} (previous={}, alive={}ms, lastError={})",
            channelId,
            entity.name,
            reason,
            previousState.status,
            aliveMs,
            previousState.lastError ?: "-",
        )
        // Advances this channel's backoff; it resets once the new listener reports CONNECTED.
        backoffOf(channelId).onDisconnected(Duration.ofMillis(aliveMs))
        runningChannels.remove(channelId)
        try {
            running.spec?.let { stopBySpec(it) }
        } catch (e: Exception) {
            log.error("Failed to stop channel before restart id={}: {}", channelId, e.message, e)
        }
        return startChannel(entity)
    }

    /**
     * @return true when the channel was running here and has now been removed.
     */
    private fun stopChannel(
        channelId: Long,
        reason: String,
    ): Boolean {
        val running = runningChannels.remove(channelId) ?: return false
        log.info("Stopping channel id={}, name={}: {}", channelId, running.spec?.name ?: "-", reason)
        try {
            running.spec?.let { stopBySpec(it) }
        } catch (e: Exception) {
            log.error("Failed to stop channel id={}: {}", channelId, e.message, e)
        }
        monitor.untrackExpected(channelId)
        backoffs.remove(channelId)
        metricsSink.onChannelRemoved(channelId)
        return true
    }

    /**
     * Stops the listener for the channel's communication mode; webhook channels have no active
     * connection to close.
     */
    private fun stopBySpec(spec: ChannelSpec) {
        if (spec.communicationMode.equals("webhook", ignoreCase = true)) {
            log.debug("Channel id={} uses webhook mode, no active stop required", spec.id)
            return
        }
        log.info("Stopping channel id={}, name={}, type={}", spec.id, spec.name, spec.type)
        adaptorRegistry.get(spec.type).stopChannel(spec)
    }

    // ==================== Graceful Shutdown ====================

    /**
     * Stops every listener and releases the shared transports before the context goes away.
     *
     * Without this the JVM exits with the platform sockets still open, so the platform keeps
     * pushing into a dead connection until its own timeout expires and the restarted process waits
     * for that stale session to be reaped before it can listen again — which reads to users as
     * "the channel was down for two minutes after the deploy".
     */
    @PreDestroy
    fun shutdown() {
        shuttingDown = true
        synchronized(reconcileLock) {
            stopAll("application shutdown")
        }
        adaptorRegistry.all().forEach { adaptor ->
            runCatching { adaptor.shutdown() }
                .onFailure { log.error("Failed to shut down adaptor {}: {}", adaptor.getType().code, it.message, it) }
        }
        lockGuard.release()
    }

    private fun stopAll(reason: String) {
        if (runningChannels.isEmpty()) return
        log.info("Stopping {} channel listener(s): {}", runningChannels.size, reason)
        runningChannels.keys.toList().forEach { stopChannel(it, reason) }
    }

    // ==================== Liveness Helpers ====================

    private fun backoffOf(channelId: Long): ReconnectBackoff = backoffs.computeIfAbsent(channelId) {
        ReconnectBackoff(
            initial = Duration.ofMillis(restartBackoffInitialMs),
            max = Duration.ofMillis(restartBackoffMaxMs),
            // A connection that held for the full backoff window was healthy; the next
            // disconnect is unrelated, so start retrying quickly again.
            resetThreshold = Duration.ofMillis(restartBackoffMaxMs),
        )
    }

    /**
     * Whether enough time has passed since the last attempt. The gate is the *current* backoff
     * step, so a channel that keeps failing is retried ever more rarely instead of every tick.
     */
    private fun retryDue(
        channelId: Long,
        running: RunningChannel,
    ): Boolean = System.currentTimeMillis() - running.lastStartAttemptAt >= backoffOf(channelId).currentDelay().toMillis()

    /**
     * Half-dead detection: the socket still reports CONNECTED but its application-level heartbeat
     * went quiet. Only transports that report heartbeats participate — for the others
     * [ChannelConnectionState.lastHeartbeatAt] stays 0, because an idle-but-healthy channel would
     * otherwise be restarted forever.
     */
    private fun isHeartbeatStale(state: ChannelConnectionState): Boolean {
        if (staleHeartbeatMs <= 0) return false
        if (state.status != ChannelConnectionStatus.CONNECTED) return false
        if (state.lastHeartbeatAt <= 0) return false
        return System.currentTimeMillis() - state.lastHeartbeatAt > staleHeartbeatMs
    }

    /**
     * Columns that change what a listener *does*. Display-only edits are excluded on purpose:
     * renaming a channel must not bounce a healthy WebSocket, and `update_time` would do exactly
     * that — while its second precision would still miss two edits within the same second.
     */
    private fun configFingerprint(entity: Channel): String = listOf(
        entity.type,
        entity.communicationMode,
        entity.agentId,
        entity.enabled,
        entity.status,
        entity.sessionId,
        entity.callbackKey,
        entity.configJson ?: "",
    ).joinToString("|")

    /**
     * Records a channel that could not be started at all. It still occupies a slot in
     * [runningChannels] — that is what drives the backoff and the retry — but with
     * `startFailed` so the liveness branch never looks for a transport that was never created.
     */
    private fun recordStartupFailure(
        entity: Channel,
        error: String,
    ) {
        val channelId = entity.id
        monitor.markStartupFailed(channelId, error)
        monitor.trackExpected(channelId, entity.name, entity.type, entity.communicationMode)
        // Log once per config version; a channel with permanently bad credentials must not
        // write a WARN every poll tick forever.
        if (runningChannels[channelId]?.configFingerprint != configFingerprint(entity)) {
            log.warn("Channel id={}, name={} cannot be started ({}); retrying with backoff", channelId, entity.name, error)
        }
        backoffOf(channelId).onDisconnected(Duration.ZERO)
        runningChannels.compute(
            channelId,
        ) { _, existing ->
            if (existing == null) {
                RunningChannel(
                    spec = null,
                    configFingerprint = configFingerprint(entity),
                    listening = false,
                    lastStartAttemptAt = System.currentTimeMillis(),
                    startFailed = true,
                )
            } else {
                existing.lastStartAttemptAt = System.currentTimeMillis()
                existing.configFingerprint = configFingerprint(entity)
                existing.startFailed = true
                existing
            }
        }
    }

    private fun report(result: RoundResult) {
        if (result.deferred > 0) {
            log.warn(
                "Start budget of {} reached; {} channel(s) still need attention and will be picked up in later rounds",
                result.budget,
                result.deferred,
            )
        }
        if (result.changed) {
            log.info(
                "Channel reconcile completed: started={}, stopped={}, restarted={}, deferred={}, {}",
                result.started,
                result.stopped,
                result.restarted,
                result.deferred,
                monitor.summaryLine(),
            )
        }
    }

    // ==================== Internal Data Structures ====================

    /**
     * Bookkeeping for one channel this instance is responsible for. Only mutated inside
     * [reconcileLock] from the single scheduler thread, so plain fields are enough.
     */
    private class RunningChannel(
        val spec: ChannelSpec?,
        var configFingerprint: String,
        /** False for channels that need no active listener (webhook) and for failed starts. */
        var listening: Boolean,
        var lastStartAttemptAt: Long,
        var startFailed: Boolean = false,
    )

    /**
     * Counters for one reconcile round, so the summary log is assembled in one place.
     * [budget] caps how many listeners a single round may start or restart.
     */
    private class RoundResult(val budget: Int) {
        var started = 0
        var stopped = 0
        var restarted = 0
        var deferred = 0

        private var reserved = 0

        val changed: Boolean
            get() = started > 0 || stopped > 0 || restarted > 0 || deferred > 0

        /** Runs [block] if this round still has start budget, otherwise counts it as deferred. */
        fun withinBudget(block: () -> Unit) {
            if (reserved >= budget) {
                deferred++
                return
            }
            reserved++
            block()
        }
    }
}
