package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.mapper.AgentTaskExecutionMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.QueryTimeoutException
import java.time.Duration
import java.time.LocalDateTime

/**
 * A lock row left at status 0 is not stale data, it is a trigger nobody can ever deliver again — the
 * unique key on (task_id, trigger_time) answers "already taken" for a holder that is gone. The deadline
 * is what the cases below pin, together with the other promise this guard makes: which failures count as
 * "someone else has it" and which count as a broken node.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskExecutionGuardCleanupTest {

    @Mock
    private lateinit var executionMapper: AgentTaskExecutionMapper

    /**
     * The unique key is the only thing that speaks about another instance. Every other way the insert can
     * fail used to be folded into the same `false`, and both callers read that as a lost race:
     * `SchedulerController` answered 40901 "already in progress" for an exhausted pool, and a cron fire
     * logged "skipping" and did nothing. A failed check is not a negative answer.
     */
    @Test
    fun `only a duplicate key answers that another instance holds the lock`() {
        whenever(executionMapper.insert(any())).thenThrow(DuplicateKeyException("Duplicate entry for key 'uk_task_trigger'"))
        val guard = AgentTaskExecutionGuard(executionMapper, "test-node", EXECUTION_TIMEOUT_SECONDS)

        assertFalse(guard.tryAcquireLock(1L, LocalDateTime.now()))
    }

    @Test
    fun `a lock attempt that fails for any other reason throws instead of reporting a lost race`() {
        whenever(executionMapper.insert(any())).thenThrow(QueryTimeoutException("statement timed out acquiring the lock"))
        val guard = AgentTaskExecutionGuard(executionMapper, "test-node", EXECUTION_TIMEOUT_SECONDS)

        assertThrows<QueryTimeoutException> {
            guard.tryAcquireLock(1L, LocalDateTime.now())
        }
    }

    @Test
    fun `a leaked lock is only reaped after twice the configured execution timeout`() {
        whenever(executionMapper.deleteStaleRunning(any())).thenReturn(2)
        val guard = AgentTaskExecutionGuard(executionMapper, "test-node", EXECUTION_TIMEOUT_SECONDS)

        val deleted = guard.cleanupLeakedLocks()

        assertEquals(2, deleted, "the sweep has to report what it freed, housekeeping logs it")
        val captor = argumentCaptor<LocalDateTime>()
        verify(executionMapper).deleteStaleRunning(captor.capture())
        val expected = LocalDateTime.now().minusSeconds(EXECUTION_TIMEOUT_SECONDS * 2L)
        val drift = Duration.between(captor.firstValue, expected).abs().seconds
        assertTrue(drift <= 10, "deadline ${captor.firstValue} is not twice the timeout (drift ${drift}s)")
    }

    /** One bad statement must not take the rest of the sweep down with it. */
    @Test
    fun `a failed sweep reports zero freed instead of throwing`() {
        whenever(executionMapper.deleteStaleRunning(any())).thenThrow(RuntimeException("deadlock"))
        val guard = AgentTaskExecutionGuard(executionMapper, "test-node", EXECUTION_TIMEOUT_SECONDS)

        assertEquals(0, guard.cleanupLeakedLocks())
    }

    companion object {
        private const val EXECUTION_TIMEOUT_SECONDS = 300
    }
}
