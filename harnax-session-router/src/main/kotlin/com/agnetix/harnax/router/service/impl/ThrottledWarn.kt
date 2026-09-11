package com.agnetix.harnax.router.service.impl

import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reports a degraded read at most once per [intervalMs].
 *
 * Every fallback in this package sits on the routing path, so an unreachable Redis would otherwise
 * log one line per request: the disk fills with repeats and the line that explains the outage is gone.
 */
internal class ThrottledWarn(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    private val lastLoggedAt = AtomicLong(0)

    fun log(
        logger: Logger,
        message: String,
        cause: Exception? = null,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastLoggedAt.get()
        if (now - previous >= intervalMs && lastLoggedAt.compareAndSet(previous, now)) {
            if (cause == null) logger.warn(message) else logger.warn(message, cause)
        }
    }

    internal companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
