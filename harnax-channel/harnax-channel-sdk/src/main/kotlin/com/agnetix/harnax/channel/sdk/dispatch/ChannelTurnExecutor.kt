package com.agnetix.harnax.channel.sdk.dispatch

import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.monitor.NoOpChannelMetricsSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded execution surface for inbound channel messages.
 *
 * Every channel used to run its agent turn on the global [kotlinx.coroutines.Dispatchers.IO],
 * which is shared JVM-wide and capped at 64 threads. One slow downstream (router / agent-service)
 * could therefore occupy the whole pool and stall message processing on every other channel.
 * This executor gives the pipeline three independent ceilings:
 *
 * 1. a fixed thread pool shared by all channels (total in-flight work is bounded),
 * 2. a per-channel semaphore (one noisy channel cannot consume the whole pool),
 * 3. a per-session lock (two messages from the same conversation never run concurrently,
 *    so session history and the agent context are updated in arrival order).
 *
 * Long-running turns are still possible by design (an agent turn may take minutes); the point
 * is that the damage is bounded and attributed to the offending channel instead of everyone.
 */
class ChannelTurnExecutor(
    threadPoolSize: Int = DEFAULT_POOL_SIZE,
    private val perChannelConcurrency: Int = DEFAULT_PER_CHANNEL_CONCURRENCY,
    private val sink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : AutoCloseable {

    private val threadSeq = AtomicInteger(0)

    private val executor: ExecutorService = Executors.newFixedThreadPool(threadPoolSize) { runnable ->
        Thread(runnable, "channel-turn-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }

    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val channelLimits = ConcurrentHashMap<Long, Semaphore>()

    private val sessionLocks = ConcurrentHashMap<String, SessionLock>()

    @Volatile
    private var lastSweepAt: Long = 0

    /**
     * Run one inbound message end to end. Exceptions are swallowed after being
     * reported to [sink] — callers must not depend on the returned [Job] outcome.
     */
    fun launchTurn(
        channelId: Long,
        sessionId: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            val started = System.currentTimeMillis()
            var error: Throwable? = null
            try {
                val limit = channelLimits.computeIfAbsent(channelId) { Semaphore(perChannelConcurrency) }
                limit.withPermit {
                    withSessionLock("$channelId:$sessionId", block)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            } finally {
                sink.onTurnCompleted(channelId, System.currentTimeMillis() - started, error)
            }
        }
    }

    /** Serialises turns of the same conversation, keyed by "channelId:sessionId". */
    private suspend fun withSessionLock(
        key: String,
        block: suspend () -> Unit,
    ) {
        sweepIdleSessionLocks()
        val lock = sessionLocks.computeIfAbsent(key) { SessionLock() }
        lock.lastUsedAt = System.currentTimeMillis()
        lock.semaphore.withPermit { block() }
    }

    /**
     * Locks are keyed by channel + conversation, so they grow with the number of distinct
     * chats. Sweep entries that have been idle and uncontended for a while to keep the map bounded.
     */
    private fun sweepIdleSessionLocks() {
        val now = System.currentTimeMillis()
        if (sessionLocks.size < SWEEP_THRESHOLD || now - lastSweepAt < SWEEP_INTERVAL_MS) {
            return
        }
        lastSweepAt = now
        val idleBefore = now - SESSION_LOCK_IDLE_MS
        val iterator = sessionLocks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastUsedAt < idleBefore && entry.value.semaphore.availablePermits == SESSION_LOCK_PERMITS) {
                iterator.remove()
            }
        }
    }

    override fun close() {
        scope.cancel("channel turn executor shutting down")
        runCatching { dispatcher.close() }
        runCatching { executor.shutdown() }
    }

    private class SessionLock {
        val semaphore = Semaphore(SESSION_LOCK_PERMITS)

        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()
    }

    companion object {
        const val DEFAULT_POOL_SIZE = 24
        const val DEFAULT_PER_CHANNEL_CONCURRENCY = 4

        private const val SWEEP_THRESHOLD = 512
        private const val SWEEP_INTERVAL_MS = 60_000L
        private const val SESSION_LOCK_IDLE_MS = 10 * 60_000L
        private const val SESSION_LOCK_PERMITS = 1

        /** Fallback for standalone SDK usage (demos, tests) where Spring does not provide a bean. */
        val SHARED: ChannelTurnExecutor by lazy { ChannelTurnExecutor() }
    }
}
