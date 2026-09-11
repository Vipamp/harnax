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

    fun recordLoadSuccess(jobCount: Int) {
        loadSuccessAt = Instant.now()
        loadError = null
        this.jobCount = jobCount
    }

    fun recordLoadFailure(error: String) {
        loadError = error
    }
}
