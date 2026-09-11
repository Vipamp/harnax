package com.agnetix.harnax.channel.service.session

import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded in-memory conversation history, keyed by `{channelId}:{sessionId}`.
 *
 * Two limits, because both dimensions grow without bound on their own:
 * - per session: the last [maxMessagesPerSession] messages (a long-running group chat would
 *   otherwise keep every message since the process started),
 * - across sessions: at most [maxSessions] conversations, with the least recently used evicted,
 *   plus a [idleTtl] so a conversation nobody speaks in is eventually dropped rather than being
 *   held for a process that may run for months.
 *
 * History is returned as a copy. The previous implementation handed out a view over its live
 * internal list, so a message arriving while an agent turn was still reading the history could
 * raise ConcurrentModificationException inside that turn.
 *
 * Eviction is approximate-by-necessity: [ConcurrentHashMap] has no access order, so instead of
 * maintaining a second structure (which would need its own lock and its own bugs), expired and
 * least-recently-touched entries are purged in batches — a cheap sweep every [PURGE_EVERY] writes,
 * and a bulk evict only when the map is genuinely full.
 */
class InMemoryChannelSessionManager(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxMessagesPerSession: Int = DEFAULT_MAX_MESSAGES,
    private val idleTtl: Duration = DEFAULT_IDLE_TTL,
) : ChannelSessionManager {

    private val log = LoggerFactory.getLogger(InMemoryChannelSessionManager::class.java)

    private val sessions = ConcurrentHashMap<String, History>()

    private val writes = AtomicLong(0)

    private class History {
        val messages = ArrayDeque<ChannelMessage>()
        var lastTouch: Long = System.currentTimeMillis()
    }

    override suspend fun getHistory(
        channelId: Long,
        sessionId: String,
        limit: Int,
    ): List<ChannelMessage> {
        val key = key(channelId, sessionId)
        val history = sessions[key] ?: return emptyList()
        history.lastTouch = System.currentTimeMillis()
        val snapshot = synchronized(history.messages) {
            if (history.messages.size <= limit) history.messages.toList() else history.messages.takeLast(limit)
        }
        log.debug("Getting history for key={}, returned={}", key, snapshot.size)
        return snapshot
    }

    override suspend fun addMessage(
        channelId: Long,
        message: ChannelMessage,
    ) {
        val key = key(channelId, message.sessionId)
        val stored = stripBinaryPayload(message)

        val history = sessions.computeIfAbsent(key) { History() }
        history.lastTouch = System.currentTimeMillis()
        var size = 0
        synchronized(history.messages) {
            history.messages.addLast(stored)
            while (history.messages.size > maxMessagesPerSession) {
                history.messages.removeFirst()
            }
            size = history.messages.size
        }
        log.debug("Added message to key={}, total={}", key, size)
        maintainCapacity()
    }

    override suspend fun clearHistory(
        channelId: Long,
        sessionId: String,
    ) {
        val key = key(channelId, sessionId)
        sessions.remove(key)
        log.debug("Cleared history for key={}", key)
    }

    /**
     * Image data is stripped before storing: base64 payloads are megabytes each and history only
     * feeds text context to the agent anyway.
     */
    private fun stripBinaryPayload(message: ChannelMessage): ChannelMessage = when {
        message.imageUrls.isNotEmpty() -> message.copy(
            content = if (message.messageType == MessageType.IMAGE) "[image sent]" else message.content,
            imageUrls = emptyList(),
        )

        message.messageType == MessageType.IMAGE && message.content.startsWith("data:image") ->
            message.copy(content = "[image sent]")

        else -> message
    }

    private fun maintainCapacity() {
        val now = System.currentTimeMillis()
        if (sessions.size < maxSessions) {
            if (writes.incrementAndGet() % PURGE_EVERY == 0L) {
                purgeExpired(now)
            }
            return
        }
        purgeExpired(now)
        val overflow = sessions.size - maxSessions + EVICT_BATCH
        if (overflow > 0) {
            evictLeastRecentlyUsed(overflow, now)
        }
    }

    private fun purgeExpired(now: Long) {
        if (idleTtl.isZero || idleTtl.isNegative) return
        val deadline = now - idleTtl.toMillis()
        var removed = 0
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastTouch < deadline) {
                iterator.remove()
                removed++
            }
        }
        if (removed > 0) {
            log.info("Dropped {} idle channel session(s) older than {}", removed, idleTtl)
        }
    }

    private fun evictLeastRecentlyUsed(
        count: Int,
        now: Long,
    ) {
        val droppedProtected = evictOldest(count, respectTtl = true, now = now)
        // Still over capacity means every stored conversation is inside the TTL window. Memory
        // safety wins: drop the least recently used anyway rather than let the map grow forever.
        val remaining = sessions.size - maxSessions
        if (remaining >= 0) {
            // 批量淘汰是为了避免每次写入都做一次全量排序，但这个批量不能超过容量本身：
            // maxSessions 很小时，固定 64 条会把整个 map 清空。
            val batch = EVICT_BATCH.coerceAtMost(maxSessions / 4)
            val droppedHard = evictOldest(remaining + batch, respectTtl = false, now = now)
            if (droppedHard > 0) {
                log.warn(
                    "All {} channel sessions are within the idle window; force-evicted {} least-recently-used",
                    sessions.size,
                    droppedHard,
                )
            }
        }
        if (droppedProtected > 0) {
            log.warn("Evicted {} stale channel session(s) to stay within {} (total={})", droppedProtected, maxSessions, sessions.size)
        }
    }

    private fun evictOldest(
        count: Int,
        respectTtl: Boolean,
        now: Long,
    ): Int {
        if (count <= 0) return 0
        if (respectTtl && (idleTtl.isZero || idleTtl.isNegative)) return 0
        val deadline = if (respectTtl) now - idleTtl.toMillis() else Long.MAX_VALUE
        val victims = sessions.entries
            .sortedBy { it.value.lastTouch }
            .filter { it.value.lastTouch < deadline }
            .take(count)
        var dropped = 0
        victims.forEach { entry ->
            if (sessions.remove(entry.key, entry.value)) dropped++
        }
        return dropped
    }

    private fun key(
        channelId: Long,
        sessionId: String,
    ): String = "$channelId:$sessionId"

    companion object {
        const val DEFAULT_MAX_SESSIONS = 10_000
        const val DEFAULT_MAX_MESSAGES = 500
        val DEFAULT_IDLE_TTL: Duration = Duration.ofHours(2)

        private const val PURGE_EVERY = 512L
        private const val EVICT_BATCH = 64
    }
}
