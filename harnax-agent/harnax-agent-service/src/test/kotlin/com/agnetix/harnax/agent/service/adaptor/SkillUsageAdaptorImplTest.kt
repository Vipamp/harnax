package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.service.client.AdminApiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The two promises the read path relies on: reporting never waits for Admin, and an Admin that does not
 * answer never reaches the session that only wanted to count a skill.
 *
 * Calls are stubbed and verified on exact arguments rather than matchers, because a reporter that filed the
 * wrong session or a truncated skill list would pass a matcher-shaped test while the usage page lied.
 */
class SkillUsageAdaptorImplTest {

    private val client = mock(AdminApiClient::class.java)
    private val adaptor = SkillUsageAdaptorImpl(client)

    @AfterEach
    fun tearDown() {
        adaptor.shutdown()
    }

    @Test
    fun `a report returns while Admin is still being asked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(client.reportSkillUsage("web-1", listOf(7L))).thenAnswer {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            true
        }

        val started = System.nanoTime()
        adaptor.reportViews("web-1", listOf(7L))
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        // Had the call been made inline, reportViews would not have returned until this latch timed out.
        assertTrue(entered.await(5, TimeUnit.SECONDS), "nothing ever asked Admin")
        release.countDown()
        assertTrue(waitedMillis < 1_000, "the read waited ${waitedMillis}ms for Admin")
    }

    @Test
    fun `an empty read asks Admin nothing`() {
        adaptor.reportViews("web-1", emptyList())

        verifyNoInteractions(client)
    }

    @Test
    fun `a client that throws does not stop the next batch`() {
        // The adaptor's contract is that it owns failures. This is an implementation breaking that contract,
        // and the batch behind it still has to reach Admin.
        `when`(client.reportSkillUsage("web-1", listOf(8L))).thenThrow(IllegalStateException("admin refused"))

        adaptor.reportViews("web-1", listOf(8L))
        adaptor.reportViews("web-1", listOf(9L))

        verify(client, timeout(5_000)).reportSkillUsage("web-1", listOf(9L))
    }

    @Test
    fun `a batch queued before shutdown is still sent`() {
        `when`(client.reportSkillUsage("web-1", listOf(7L))).thenReturn(true)

        adaptor.reportViews("web-1", listOf(7L))
        adaptor.shutdown()

        verify(client).reportSkillUsage("web-1", listOf(7L))
    }
}
