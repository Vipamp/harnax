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
    private var reconcileAt: Instant? = null

    @Volatile
    private var reconcileError: String? = null

    @Volatile
    private var jobCount: Int = 0

    /**
     * When *this instance* last ran a reconcile round to completion; null while none has, which is the state
     * the health check reads as "this node has never converged the store". A round that left drift still
     * stamps it: it did reach the store, and [lastReconcileError] is what keeps it from reading as healthy.
     *
     * Read it as a node-local observation and nothing more. Two things move it — the 60-second sweep, which is
     * a cluster singleton and stamps only the node that fired it, and admin's `/reload` forward, which reaches
     * the one instance its caller resolved to. A node whose stamp is old has therefore not been told to
     * converge anything recently, which is not the same statement as "the cluster is not converging"; the
     * latter is visible on whichever node *did* run the round. An alert on this field's age would page on the
     * distribution of that work, which is why there is no such rule here and why the alert belongs on the
     * drift side: [lastReconcileError] on this node and `scheduler.reconcile.drift` say what the store could
     * not be made to match, and they are wrong only when the work actually failed.
     */
    val lastReconcileAt: Instant? get() = reconcileAt

    val lastReconcileError: String? get() = reconcileError

    /**
     * How many active tasks the *most recent round* left scheduled — not what the cluster is running now.
     *
     * Nothing here sees `startTask`/`pauseTask` or any CRUD, so reading this as a live count is how the
     * health detail and the `scheduler.jobs.scheduled` gauge ended up reporting a startup number forever.
     * Both now read the store through `QuartzJobInventory`; this stays only as reconcile bookkeeping
     * (and as what [lastReconcileError] drifts against).
     */
    val lastReconcileJobCount: Int get() = jobCount

    /**
     * Records a round that left [jobCount] tasks scheduled.
     *
     * [pendingError] carries the "partly" in "partly converged": pass it when some active tasks could
     * not be registered and the store therefore still drifts. The timestamp and the count
     * still move — jobs *are* firing — but [lastReconcileError] stays set so the health check keeps
     * reporting DOWN instead of clearing a real problem just because something else succeeded. A round
     * that left nothing behind is the only thing that resets it.
     */
    fun recordReconcile(
        jobCount: Int,
        pendingError: String? = null,
    ) {
        reconcileAt = Instant.now()
        reconcileError = pendingError
        this.jobCount = jobCount
    }

    /** A round that never reached the store: no count, no timestamp, only the reason it failed. */
    fun recordReconcileFailure(error: String) {
        reconcileError = error
    }
}
