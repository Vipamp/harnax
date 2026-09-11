package com.agnetix.harnax.router.service

/**
 * Keeps one client request from running twice across the router cluster.
 *
 * The slot is an *in-flight* guard, not a response cache: [tryAcquire] refuses a request that
 * another node is serving right now, and [release] hands it back when this node is done. Without the
 * release, a client retrying after a timeout or a failed proxy would be locked out for the whole TTL
 * and told its request was a duplicate.
 */
interface IdempotencyService {

    /** @return true when this caller owns [requestId] and may proceed. */
    fun tryAcquire(requestId: String): Boolean

    /** Gives up the slot taken by [tryAcquire]. Safe to call for a request that is no longer held. */
    fun release(requestId: String)
}
