package com.agnetix.harnax.channel.service.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper

/**
 * Unit tests for [RouterClient.downloadWorkspaceFile].
 *
 * Tests error handling behavior:
 * - Connection refused → returns null
 * - Timeout → returns null
 * - Runtime exception → returns null
 *
 * Note: Successful download path is covered by integration test
 * because RestClient's recursive generic bounds make unit mocking impractical.
 */
class RouterClientWorkspaceDownloadTest {

    private lateinit var webClient: WebClient
    private lateinit var restClient: RestClient
    private lateinit var objectMapper: ObjectMapper
    private lateinit var routerClient: RouterClient

    private val routerUrl = "http://harnax-router:8081"

    @BeforeEach
    fun setUp() {
        webClient = mock()
        restClient = mock()
        objectMapper = mock()
        routerClient = RouterClient(webClient, restClient, objectMapper, routerUrl)
    }

    // ==================== Error handling ====================

    @Nested
    inner class ErrorHandling {

        @Test
        fun `returns null when router is unreachable`() {
            whenever(restClient.get()).thenThrow(RuntimeException("Connection refused"))

            val result = routerClient.downloadWorkspaceFile(
                "chn-test-session",
                "/workspace/output/file.pptx",
            )

            assertNull(result)
        }

        @Test
        fun `returns null on timeout`() {
            whenever(restClient.get()).thenThrow(RuntimeException("Read timed out"))

            val result = routerClient.downloadWorkspaceFile(
                "chn-test-session",
                "/workspace/output/large-file.pptx",
            )

            assertNull(result)
        }

        @Test
        fun `returns null on runtime exception`() {
            whenever(restClient.get()).thenThrow(RuntimeException("Unexpected error"))

            val result = routerClient.downloadWorkspaceFile(
                "chn-test-session",
                "/workspace/output/file.pptx",
            )

            assertNull(result)
        }

        @Test
        fun `does not throw exception on failure`() {
            whenever(restClient.get()).thenThrow(IllegalStateException("Bad state"))

            assertDoesNotThrow {
                routerClient.downloadWorkspaceFile("chn-x", "/workspace/output/f.pptx")
            }
        }
    }
}
