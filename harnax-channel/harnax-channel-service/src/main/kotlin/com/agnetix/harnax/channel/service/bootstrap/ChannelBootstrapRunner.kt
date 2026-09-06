package com.agnetix.harnax.channel.service.bootstrap

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.service.adaptor.RouterAgentAdaptor
import com.agnetix.harnax.channel.service.client.RouterClient
import com.agnetix.harnax.channel.service.manager.ChannelAdaptorRegistry
import com.agnetix.harnax.channel.service.mapper.ChannelEntityConverter
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.mapper.ChannelMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Channel lifecycle manager.
 *
 * On startup, loads all channels where enabled=1 AND status=1 AND active=1 from the database
 * and establishes listeners. Then periodically polls (default 10s) to detect channel table
 * changes and automatically performs start/stop/restart:
 *
 *  - websocket    -> Feishu WebSocket long connection ([FeishuAdaptor.startChannelWithAgent])
 *  - long_polling -> WeChat iLink long polling ([WechatAdaptor.startChannelWithAgent])
 *  - webhook      -> No active connection (handled by HTTP callback Controller)
 *
 * Change detection is based on the [Channel.updateTime] field of each channel entity.
 * Every client message received by a listener is routed via [RouterAgentAdaptor]
 * -> session-router -> agent-service for Agent inference, then sent back to the
 * corresponding channel.
 */
@Component
class ChannelBootstrapRunner(
    private val channelMapper: ChannelMapper,
    private val adaptorRegistry: ChannelAdaptorRegistry,
    private val routerClient: RouterClient,
    private val sessionManager: ChannelSessionManager,
) {

    private val log = LoggerFactory.getLogger(ChannelBootstrapRunner::class.java)

    private val routerAgentAdaptor by lazy { RouterAgentAdaptor(routerClient) }

    /** Pre-configured ChannelChatService with workspace file delivery support */
    private val chatService = com.agnetix.harnax.channel.sdk.service.ChannelChatService(
        sessionManager = sessionManager,
        workspaceFileDownloader = { sessionId, filePath ->
            routerClient.downloadWorkspaceFile(sessionId, filePath)
        },
    )

    /**
     * Tracks currently running channels: channelId -> RunningChannel.
     */
    private val runningChannels = ConcurrentHashMap<Long, RunningChannel>()

    /**
     * Lock to prevent concurrent execution of reconcile().
     */
    private val reconcileLock = Any()

    // ==================== Startup Entry ====================

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        log.info("Application started, initiating first channel listener load")
        reconcile()
    }

    // ==================== Scheduled Polling ====================

    /**
     * Periodically detects channel table changes and syncs listener state.
     * Uses fixedDelay to ensure the next interval starts only after the current
     * round completes, avoiding overlapping executions.
     */
    @Scheduled(fixedDelayString = "\${channel.sync.interval-ms:10000}")
    fun scheduledReconcile() {
        reconcile()
    }

    // ==================== Core Reconcile Logic ====================

    /**
     * Loads the expected running channel list from the DB, compares it with
     * the in-memory running channels, and performs start/stop/restart accordingly.
     */
    fun reconcile() {
        synchronized(reconcileLock) {
            val dbChannels: List<Channel> = try {
                channelMapper.selectAutoStartChannels()
            } catch (e: Exception) {
                log.error("Failed to load channel config, skipping this reconcile round: {}", e.message, e)
                return
            }

            val dbMap = dbChannels.associateBy { it.id }
            val runningIds = runningChannels.keys.toSet()
            val dbIds = dbMap.keys

            // 1) toStop: running in memory but no longer in DB (disabled/deleted)
            val toStop = runningIds - dbIds
            toStop.forEach { channelId ->
                try {
                    val running = runningChannels[channelId] ?: return@forEach
                    log.info("Channel removed or disabled in DB, stopping listener: id={}, name={}", channelId, running.spec.name)
                    stopBySpec(running.spec)
                    runningChannels.remove(channelId)
                } catch (e: Exception) {
                    log.error("Failed to stop channel id={}: {}", channelId, e.message, e)
                }
            }

            // 2) toStart: present in DB but not running in memory
            val toStart = dbIds - runningIds
            toStart.forEach { channelId ->
                val entity = dbMap[channelId] ?: return@forEach
                try {
                    if (startSingle(entity)) {
                        runningChannels[channelId] = RunningChannel(
                            spec = ChannelEntityConverter.toSpec(entity),
                            updateTime = entity.updateTime,
                            configFingerprint = configFingerprint(entity),
                        )
                    }
                } catch (e: Exception) {
                    log.error("Failed to start channel id={}, name={}, type={}: {}", entity.id, entity.name, entity.type, e.message, e)
                }
            }

            // 3) toRestart: present in both DB and memory but updateTime differs (config changed)
            val toCheck = dbIds.intersect(runningIds)
            var restartedCount = 0
            toCheck.forEach { channelId ->
                val entity = dbMap[channelId] ?: return@forEach
                val running = runningChannels[channelId] ?: return@forEach
                val newFingerprint = configFingerprint(entity)
                val changed = entity.updateTime != running.updateTime ||
                    newFingerprint != running.configFingerprint
                if (changed) {
                    restartedCount++
                    log.info(
                        "Channel config changed, restarting listener: id={}, name={}, updateTime {} -> {}",
                        channelId,
                        entity.name,
                        running.updateTime,
                        entity.updateTime,
                    )
                    try {
                        stopBySpec(running.spec)
                    } catch (e: Exception) {
                        log.error("Failed to stop channel before restart id={}: {}", channelId, e.message, e)
                    }
                    try {
                        if (startSingle(entity)) {
                            runningChannels[channelId] = RunningChannel(
                                spec = ChannelEntityConverter.toSpec(entity),
                                updateTime = entity.updateTime,
                                configFingerprint = newFingerprint,
                            )
                        } else {
                            // Changed to webhook mode, no longer needs active listening
                            runningChannels.remove(channelId)
                        }
                    } catch (e: Exception) {
                        log.error("Failed to restart channel id={}, name={}: {}", channelId, entity.name, e.message, e)
                        runningChannels.remove(channelId)
                    }
                }
            }

            if (toStop.isNotEmpty() || toStart.isNotEmpty() || restartedCount > 0) {
                log.info(
                    "Channel reconcile completed: started={}, stopped={}, restarted={}, running={}",
                    toStart.size,
                    toStop.size,
                    restartedCount,
                    runningChannels.size,
                )
            }
        }
    }

    // ==================== Single Channel Start/Stop ====================

    /**
     * Starts a single channel listener.
     *
     * @return true if a listener was started, false if no active connection is needed (e.g. webhook mode).
     */
    private fun startSingle(entity: Channel): Boolean {
        val spec = ChannelEntityConverter.toSpec(entity)
        if (spec.communicationMode.equals("webhook", ignoreCase = true)) {
            log.info("Channel id={} uses webhook callback mode, skipping active connection", entity.id)
            return false
        }
        val adaptor = adaptorRegistry.get(spec.type)
        log.info(
            "Starting channel id={}, name={}, type={}, mode={}",
            entity.id,
            entity.name,
            entity.type,
            entity.communicationMode,
        )
        adaptor.startChannelWithAgent(spec, routerAgentAdaptor, sessionManager, chatService)
        return true
    }

    /**
     * Stops the channel listener based on the communication mode defined in [ChannelSpec].
     */
    private fun stopBySpec(spec: ChannelSpec) {
        if (spec.communicationMode.equals("webhook", ignoreCase = true)) {
            log.debug("Channel id={} uses webhook mode, no active stop required", spec.id)
            return
        }
        log.info("Stopping channel id={}, name={}, type={}", spec.id, spec.name, spec.type)
        adaptorRegistry.get(spec.type).stopChannel(spec)
    }

    // ==================== Internal Data Structures ====================

    /**
     * Tracks running channel information for change detection.
     */
    private data class RunningChannel(
        val spec: ChannelSpec,
        val updateTime: LocalDateTime,
        val configFingerprint: String,
    )

    /**
     * Builds a fingerprint of the config-relevant fields of a channel entity.
     * Used as a secondary change signal alongside update_time, which has only
     * second precision (MySQL DATETIME) and therefore cannot distinguish two
     * edits made within the same second.
     */
    private fun configFingerprint(entity: Channel): String = listOf(
        entity.type,
        entity.communicationMode,
        entity.agentId.toString(),
        entity.enabled.toString(),
        entity.status.toString(),
        entity.configJson ?: "",
    ).joinToString("|")
}
