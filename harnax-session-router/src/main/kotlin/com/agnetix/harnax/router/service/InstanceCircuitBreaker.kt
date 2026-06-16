package com.agnetix.harnax.router.service

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InstanceCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 30000,
) {

    private val log = LoggerFactory.getLogger(InstanceCircuitBreaker::class.java)

    enum class State { CLOSED, OPEN, HALF_OPEN }

    private class CircuitState {
        val state = AtomicReference(State.CLOSED)
        val failureCount = AtomicInteger(0)

        @Volatile
        var lastFailureTimeMs: Long = 0
    }

    private val circuits = ConcurrentHashMap<String, CircuitState>()

    fun isOpen(instanceId: String): Boolean {
        val circuit = circuits[instanceId] ?: return false
        return when (circuit.state.get()) {
            State.OPEN -> {
                if (System.currentTimeMillis() - circuit.lastFailureTimeMs > openDurationMs) {
                    if (circuit.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        log.info("Circuit breaker HALF_OPEN for instance: $instanceId")
                    }
                    false
                } else {
                    true
                }
            }
            State.HALF_OPEN -> false
            State.CLOSED -> false
        }
    }

    fun recordFailure(instanceId: String) {
        val circuit = circuits.computeIfAbsent(instanceId) { CircuitState() }
        circuit.lastFailureTimeMs = System.currentTimeMillis()

        val currentState = circuit.state.get()
        when (currentState) {
            State.HALF_OPEN -> {
                circuit.state.set(State.OPEN)
                log.warn("Circuit breaker OPEN for instance: $instanceId (failed in HALF_OPEN)")
            }
            State.CLOSED -> {
                val count = circuit.failureCount.incrementAndGet()
                if (count >= failureThreshold) {
                    circuit.state.set(State.OPEN)
                    log.warn("Circuit breaker OPEN for instance: $instanceId (failures=$count)")
                }
            }
            State.OPEN -> {
                // already open, just update time
            }
        }
    }

    fun recordSuccess(instanceId: String) {
        val circuit = circuits[instanceId] ?: return
        val previous = circuit.state.getAndSet(State.CLOSED)
        circuit.failureCount.set(0)
        if (previous == State.HALF_OPEN) {
            log.info("Circuit breaker CLOSED for instance: $instanceId (recovered)")
        }
    }

    fun reset(instanceId: String) {
        circuits.remove(instanceId)
    }

    fun getState(instanceId: String): State = circuits[instanceId]?.state?.get() ?: State.CLOSED

    fun getFailureCount(instanceId: String): Int = circuits[instanceId]?.failureCount?.get() ?: 0
}
