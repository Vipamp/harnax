package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory circuit breaker for single-node deployments.
 *
 * For multi-node Redis deployments use [RedisCircuitBreaker]; [com.agnetix.harnax.router.config.RouterConfig]
 * selects the implementation based on `router.cache.type`. Both implement the same state machine:
 * CLOSED counts failures, `failureThreshold` failures (or a failed probe) open the circuit for
 * `openDurationMs`, after which a single probe is granted for `probeLeaseMs`.
 */
class LocalInstanceCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 30000,
    private val probeLeaseMs: Long = openDurationMs,
) : InstanceCircuitBreaker {

    private val log = LoggerFactory.getLogger(LocalInstanceCircuitBreaker::class.java)

    /** All mutation happens under the circuit's own monitor, so the transitions stay atomic. */
    private class Circuit {
        var state: InstanceCircuitBreaker.State = InstanceCircuitBreaker.State.CLOSED
        var failures: Int = 0
        var openedAtMs: Long = 0
        var probeUntilMs: Long = 0
    }

    private val circuits = ConcurrentHashMap<String, Circuit>()

    override fun isOpen(instanceId: String): Boolean {
        val circuit = circuits[instanceId] ?: return false
        return synchronized(circuit) { isTripped(circuit, System.currentTimeMillis()) }
    }

    override fun allowRequest(instanceId: String): Boolean {
        val circuit = circuits[instanceId] ?: return true
        return synchronized(circuit) {
            val now = System.currentTimeMillis()
            if (circuit.state == InstanceCircuitBreaker.State.CLOSED) {
                return@synchronized true
            }
            if (isTripped(circuit, now)) {
                return@synchronized false
            }
            circuit.state = InstanceCircuitBreaker.State.HALF_OPEN
            circuit.probeUntilMs = now + probeLeaseMs
            log.info("Circuit breaker HALF_OPEN for instance: $instanceId (probe granted)")
            true
        }
    }

    override fun recordFailure(instanceId: String) {
        val circuit = circuits.computeIfAbsent(instanceId) { Circuit() }
        synchronized(circuit) {
            val now = System.currentTimeMillis()
            when (circuit.state) {
                InstanceCircuitBreaker.State.HALF_OPEN -> {
                    open(circuit, now)
                    log.warn("Circuit breaker OPEN for instance: $instanceId (probe failed)")
                }
                InstanceCircuitBreaker.State.OPEN -> {
                    circuit.failures++
                }
                InstanceCircuitBreaker.State.CLOSED -> {
                    circuit.failures++
                    if (circuit.failures >= failureThreshold) {
                        open(circuit, now)
                        log.warn("Circuit breaker OPEN for instance: $instanceId (failures=${circuit.failures})")
                    }
                }
            }
        }
    }

    override fun recordSuccess(instanceId: String) {
        // Dropping the circuit is the same as the Redis implementation deleting the key: a
        // successful request is proof the instance serves traffic, and CLOSED is the default.
        val circuit = circuits.remove(instanceId) ?: return
        if (circuit.state != InstanceCircuitBreaker.State.CLOSED) {
            log.info("Circuit breaker CLOSED for instance: $instanceId (recovered)")
        }
    }

    override fun reset(instanceId: String) {
        circuits.remove(instanceId)
    }

    override fun getState(instanceId: String): InstanceCircuitBreaker.State {
        val circuit = circuits[instanceId] ?: return InstanceCircuitBreaker.State.CLOSED
        return synchronized(circuit) {
            val now = System.currentTimeMillis()
            if (circuit.state == InstanceCircuitBreaker.State.OPEN && !isTripped(circuit, now)) {
                InstanceCircuitBreaker.State.HALF_OPEN
            } else {
                circuit.state
            }
        }
    }

    override fun getFailureCount(instanceId: String): Int = circuits[instanceId]?.let { synchronized(it) { it.failures } } ?: 0

    /** Whether traffic must be refused right now; false also means a probe may be granted. */
    private fun isTripped(
        circuit: Circuit,
        now: Long,
    ): Boolean = when (circuit.state) {
        InstanceCircuitBreaker.State.CLOSED -> false
        InstanceCircuitBreaker.State.OPEN -> now - circuit.openedAtMs < openDurationMs
        InstanceCircuitBreaker.State.HALF_OPEN -> now < circuit.probeUntilMs
    }

    private fun open(
        circuit: Circuit,
        now: Long,
    ) {
        circuit.state = InstanceCircuitBreaker.State.OPEN
        circuit.openedAtMs = now
        circuit.probeUntilMs = 0
    }
}
