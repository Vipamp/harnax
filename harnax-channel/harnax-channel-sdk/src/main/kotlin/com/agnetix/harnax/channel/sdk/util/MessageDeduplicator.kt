package com.agnetix.harnax.channel.sdk.util

import java.util.LinkedList

/**
 * Idempotency guard for channels that deliver messages at-least-once.
 *
 * Marking a message as processed *before* handling it is the safe thing for duplicates
 * and the wrong thing for failures: if the agent turn then throws, the platform redelivers
 * and the redelivery gets filtered out, so the message is lost for good. This split
 * register/commit flow fixes that ordering:
 *
 * ```
 * if (!dedup.tryBegin(msgId)) return     // already committed or currently in flight
 * try { handle() ; dedup.commit(msgId) }
 * catch (e) { dedup.rollback(msgId) }    // redelivery is now accepted again
 * ```
 *
 * The committed window is bounded to [maxCommitted] entries (oldest evicted first) so a
 * long-lived listener cannot grow memory without limit. Not thread-safe for a shared
 * instance across channels — each transport creates one per channel id.
 */
class MessageDeduplicator(
    private val maxCommitted: Int = DEFAULT_MAX_COMMITTED,
) {

    private val lock = Any()

    /** msgIds whose handling completed successfully, oldest first. */
    private val committed = LinkedList<String>()
    private val committedIndex = HashSet<String>()

    /** msgIds currently being handled, so a redelivery arriving mid-turn is still filtered. */
    private val inFlight = HashSet<String>()

    /**
     * @return true when this message should be processed, false when it is a duplicate.
     */
    fun tryBegin(messageId: String): Boolean = synchronized(lock) {
        if (committedIndex.contains(messageId) || inFlight.contains(messageId)) {
            false
        } else {
            // In-flight entries are only removed by commit or rollback, so they need a ceiling too:
            // a turn abandoned by a dying executor thread reaches neither, and an unbounded set
            // would grow for the life of the process while quietly filtering that msgId forever.
            // Past the ceiling the message is processed untracked — one possible duplicate is
            // cheaper than a leak, and reaching the ceiling already means something is wrong.
            if (inFlight.size >= MAX_IN_FLIGHT) {
                true
            } else {
                inFlight.add(messageId)
                true
            }
        }
    }

    fun commit(messageId: String) = synchronized(lock) {
        inFlight.remove(messageId)
        if (!committedIndex.contains(messageId)) {
            committedIndex.add(messageId)
            committed.add(messageId)
            while (committed.size > maxCommitted) {
                committedIndex.remove(committed.removeFirst())
            }
        }
        Unit
    }

    /** Forget a message so a platform redelivery of it will be processed. */
    fun rollback(messageId: String) = synchronized(lock) {
        inFlight.remove(messageId)
        Unit
    }

    fun size(): Int = synchronized(lock) { committed.size }

    companion object {
        const val DEFAULT_MAX_COMMITTED = 1000

        /** Ceiling for concurrently tracked in-flight messages; see [tryBegin]. */
        const val MAX_IN_FLIGHT = 4096
    }
}
