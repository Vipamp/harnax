package com.agnetix.harnax.scheduler.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * What this instance currently believes about its own scheduling ability.
 *
 * Exists because "the process is up" and "the process is scheduling anything" are different
 * questions, and the second one was previously unobservable: a failed startup load left the
 * instance with zero jobs while every probe still answered OK.
 */
@Component
class SchedulerStatus(
    @Value("\${scheduler.enabled:true}") val schedulerEnabled: Boolean,
) {

    // @Volatile backing fields: the kotlin spring plugin makes this class open, and an open
    // property cannot have a private setter.
    @Volatile
    private var loadSuccessAt: Instant? = null

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var jobCount: Int = 0

    val lastLoadSuccessAt: Instant? get() = loadSuccessAt

    val lastLoadError: String? get() = loadError

    val scheduledJobCount: Int get() = jobCount

    /**
     * Records a load that registered [jobCount] jobs.
     *
     * [pendingError] carries the "partly" in "partly succeeded": pass it when some active tasks could
     * not be registered and the load therefore left this instance drifting. The timestamp and the count
     * still move — jobs *are* firing — but [lastLoadError] stays set so the health check keeps reporting
     * DOWN instead of clearing a real problem just because something else succeeded. A clean sweep is
     * the only thing that resets it.
     */
    fun recordLoadSuccess(
        jobCount: Int,
        pendingError: String? = null,
    ) {
        loadSuccessAt = Instant.now()
        loadError = pendingError
        this.jobCount = jobCount
    }

    fun recordLoadFailure(error: String) {
        loadError = error
    }
}
