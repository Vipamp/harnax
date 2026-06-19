package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory circuit breaker for single-node deployments.
 *
 * For multi-node Redis deployments use [RedisCircuitBreaker]; [com.agnetix.harnax.router.config.RouterConfig]
 * selects the implementation based on `router.cache.type`.
 */
class LocalInstanceCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 30000,
) : InstanceCircuitBreaker {

    private val log = LoggerFactory.getLogger(LocalInstanceCircuitBreaker::class.java)

    private class CircuitState {
        val state = AtomicReference(InstanceCircuitBreaker.State.CLOSED)
        val failureCount = AtomicInteger(0)

        @Volatile
        var lastFailureTimeMs: Long = 0
    }

    private val circuits = ConcurrentHashMap<String, CircuitState>()

    override fun isOpen(instanceId: String): Boolean {
        val circuit = circuits[instanceId] ?: return false
        return when (circuit.state.get()) {
            InstanceCircuitBreaker.State.OPEN -> {
                if (System.currentTimeMillis() - circuit.lastFailureTimeMs > openDurationMs) {
                    if (circuit.state.compareAndSet(InstanceCircuitBreaker.State.OPEN, InstanceCircuitBreaker.State.HALF_OPEN)) {
                        log.info("Circuit breaker HALF_OPEN for instance: $instanceId")
                    }
                    false
                } else {
                    true
                }
            }
            InstanceCircuitBreaker.State.HALF_OPEN -> false
            InstanceCircuitBreaker.State.CLOSED -> false
        }
    }

    override fun recordFailure(instanceId: String) {
        val circuit = circuits.computeIfAbsent(instanceId) { CircuitState() }
        circuit.lastFailureTimeMs = System.currentTimeMillis()

        val currentState = circuit.state.get()
        when (currentState) {
            InstanceCircuitBreaker.State.HALF_OPEN -> {
                circuit.state.set(InstanceCircuitBreaker.State.OPEN)
                log.warn("Circuit breaker OPEN for instance: $instanceId (failed in HALF_OPEN)")
            }
            InstanceCircuitBreaker.State.CLOSED -> {
                val count = circuit.failureCount.incrementAndGet()
                if (count >= failureThreshold) {
                    circuit.state.set(InstanceCircuitBreaker.State.OPEN)
                    log.warn("Circuit breaker OPEN for instance: $instanceId (failures=$count)")
                }
            }
            InstanceCircuitBreaker.State.OPEN -> {
                // already open, just update time
            }
        }
    }

    override fun recordSuccess(instanceId: String) {
        val circuit = circuits[instanceId] ?: return
        val previous = circuit.state.getAndSet(InstanceCircuitBreaker.State.CLOSED)
        circuit.failureCount.set(0)
        if (previous == InstanceCircuitBreaker.State.HALF_OPEN) {
            log.info("Circuit breaker CLOSED for instance: $instanceId (recovered)")
        }
    }

    override fun reset(instanceId: String) {
        circuits.remove(instanceId)
    }

    override fun getState(instanceId: String): InstanceCircuitBreaker.State = circuits[instanceId]?.state?.get() ?: InstanceCircuitBreaker.State.CLOSED

    override fun getFailureCount(instanceId: String): Int = circuits[instanceId]?.failureCount?.get() ?: 0
}
