package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.entity.SysTokenBlacklist
import com.agnetix.harnax.mapper.SysTokenBlacklistMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.springframework.dao.DuplicateKeyException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SysTokenBlacklistServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SysTokenBlacklistServiceImplTest {

    @Mock
    private lateinit var tokenBlacklistMapper: SysTokenBlacklistMapper

    private lateinit var testBlacklist: SysTokenBlacklist

    @BeforeEach
    fun setUp() {
        testBlacklist = SysTokenBlacklist.builder()
            .id(1L)
            .token("mock-token")
            .tokenHash(sha256("mock-token"))
            .username("admin")
            .userId(1L)
            .reason("logout")
            .expireTime(LocalDateTime.now().plusHours(2))
            .createTime(LocalDateTime.now())
            .createIp("127.0.0.1")
            .build()

        // Mock HttpServletRequest for RequestContextHolder (client IP resolution)
        val mockRequest = MockHttpServletRequest()
        mockRequest.remoteAddr = "127.0.0.1"
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    private fun createService(): SysTokenBlacklistServiceImpl = SysTokenBlacklistServiceImpl(
        tokenBlacklistMapper = tokenBlacklistMapper,
    )

    /**
     * Same SHA256 hex algorithm as the service uses internally
     */
    private fun sha256(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Nested
    @DisplayName("Add To Blacklist Tests")
    inner class AddToBlacklistTests {

        @Test
        @DisplayName("addToBlacklist - Add token to blacklist successfully")
        fun `addToBlacklist should add token to blacklist successfully`() {
            // Given
            val expireTime = LocalDateTime.now().plusHours(2)
            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)

            // When
            createService().addToBlacklist("mock-token", "admin", 1L, expireTime, "logout")

            // Then
            val captor = argumentCaptor<SysTokenBlacklist>()
            verify(tokenBlacklistMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("mock-token", saved.token)
            assertEquals(sha256("mock-token"), saved.tokenHash)
            assertEquals("admin", saved.username)
            assertEquals(1L, saved.userId)
            assertEquals("logout", saved.reason)
            assertEquals(expireTime, saved.expireTime)
        }

        @Test
        @DisplayName("addToBlacklist - Record client IP from remoteAddr")
        fun `addToBlacklist should record client ip from remoteAddr`() {
            // Given
            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)

            // When
            createService().addToBlacklist("mock-token", "admin", 1L, LocalDateTime.now().plusHours(2), "logout")

            // Then
            val captor = argumentCaptor<SysTokenBlacklist>()
            verify(tokenBlacklistMapper).insert(captor.capture())
            assertEquals("127.0.0.1", captor.firstValue.createIp)
        }

        @Test
        @DisplayName("addToBlacklist - Prefer X-Forwarded-For header for client IP")
        fun `addToBlacklist should prefer x-forwarded-for header for client ip`() {
            // Given
            val mockRequest = MockHttpServletRequest()
            mockRequest.addHeader("X-Forwarded-For", "10.0.0.8")
            mockRequest.remoteAddr = "127.0.0.1"
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)

            // When
            createService().addToBlacklist("mock-token", "admin", 1L, LocalDateTime.now().plusHours(2), "logout")

            // Then
            val captor = argumentCaptor<SysTokenBlacklist>()
            verify(tokenBlacklistMapper).insert(captor.capture())
            assertEquals("10.0.0.8", captor.firstValue.createIp)
        }

        @Test
        @DisplayName("addToBlacklist - Fall back to X-Real-IP when X-Forwarded-For unknown")
        fun `addToBlacklist should fall back to x-real-ip when x-forwarded-for unknown`() {
            // Given
            val mockRequest = MockHttpServletRequest()
            mockRequest.addHeader("X-Forwarded-For", "unknown")
            mockRequest.addHeader("X-Real-IP", "10.0.0.9")
            mockRequest.remoteAddr = "127.0.0.1"
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)

            // When
            createService().addToBlacklist("mock-token", "admin", 1L, LocalDateTime.now().plusHours(2), "logout")

            // Then
            val captor = argumentCaptor<SysTokenBlacklist>()
            verify(tokenBlacklistMapper).insert(captor.capture())
            assertEquals("10.0.0.9", captor.firstValue.createIp)
        }

        @Test
        @DisplayName("addToBlacklist - Use unknown IP when no request context")
        fun `addToBlacklist should use unknown ip when no request context`() {
            // Given
            RequestContextHolder.resetRequestAttributes()
            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)

            // When
            createService().addToBlacklist("mock-token", "admin", 1L, LocalDateTime.now().plusHours(2), "logout")

            // Then
            val captor = argumentCaptor<SysTokenBlacklist>()
            verify(tokenBlacklistMapper).insert(captor.capture())
            assertEquals("unknown", captor.firstValue.createIp)
        }

        @Test
        @DisplayName("addToBlacklist - Do not throw when insert fails")
        fun `addToBlacklist should not throw when insert fails`() {
            // Given - insert throwing must not break the logout process
            `when`(tokenBlacklistMapper.insert(any())).thenThrow(RuntimeException("db error"))

            // When & Then
            assertDoesNotThrow {
                createService().addToBlacklist("mock-token", "admin", 1L, LocalDateTime.now().plusHours(2), "logout")
            }
        }

        @Test
        @DisplayName("addToBlacklist - Store custom reason")
        fun `addToBlacklist should store custom reason`() {
            // Given
            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)

            // When
            createService().addToBlacklist(
                "mock-token",
                "admin",
                1L,
                LocalDateTime.now().plusHours(2),
                "password_changed",
            )

            // Then
            val captor = argumentCaptor<SysTokenBlacklist>()
            verify(tokenBlacklistMapper).insert(captor.capture())
            assertEquals("password_changed", captor.firstValue.reason)
        }

        @Test
        @DisplayName("addToBlacklist - Token hash is lowercase hexadecimal SHA256")
        fun `addToBlacklist should compute lowercase hexadecimal sha256 hash`() {
            // Given
            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)

            // When
            createService().addToBlacklist("abc", "admin", 1L, LocalDateTime.now().plusHours(2), "logout")

            // Then
            val captor = argumentCaptor<SysTokenBlacklist>()
            verify(tokenBlacklistMapper).insert(captor.capture())
            // SHA256("abc") known value
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                captor.firstValue.tokenHash,
            )
        }
    }

    @Nested
    @DisplayName("Is Blacklisted Tests")
    inner class IsBlacklistedTests {

        @Test
        @DisplayName("isBlacklisted - Return true when token is in blacklist")
        fun `isBlacklisted should return true when token is in blacklist`() {
            // Given
            `when`(tokenBlacklistMapper.selectByTokenHash(sha256("mock-token"))).thenReturn(testBlacklist)

            // When
            val result = createService().isBlacklisted("mock-token")

            // Then
            assertTrue(result)
            verify(tokenBlacklistMapper).selectByTokenHash(sha256("mock-token"))
        }

        @Test
        @DisplayName("isBlacklisted - Return false when token not in blacklist")
        fun `isBlacklisted should return false when token not in blacklist`() {
            // Given
            `when`(tokenBlacklistMapper.selectByTokenHash(sha256("clean-token"))).thenReturn(null)

            // When
            val result = createService().isBlacklisted("clean-token")

            // Then
            assertFalse(result)
            verify(tokenBlacklistMapper).selectByTokenHash(sha256("clean-token"))
        }

        @Test
        @DisplayName("isBlacklisted - Return false when query fails")
        fun `isBlacklisted should return false when query fails`() {
            // Given - query failure defaults to allowing access
            `when`(tokenBlacklistMapper.selectByTokenHash(anyString())).thenThrow(RuntimeException("db error"))

            // When
            val result = createService().isBlacklisted("mock-token")

            // Then
            assertFalse(result)
        }

        @Test
        @DisplayName("isBlacklisted - Query by hash instead of raw token")
        fun `isBlacklisted should query by hash instead of raw token`() {
            // Given
            `when`(tokenBlacklistMapper.selectByTokenHash(anyString())).thenReturn(null)

            // When
            createService().isBlacklisted("raw-token-value")

            // Then - verify raw token is never passed as hash
            verify(tokenBlacklistMapper).selectByTokenHash(sha256("raw-token-value"))
            verify(tokenBlacklistMapper, never()).selectByTokenHash("raw-token-value")
        }

        @Test
        @DisplayName("isBlacklisted - Different tokens produce different hash queries")
        fun `isBlacklisted should produce different hash queries for different tokens`() {
            // Given
            `when`(tokenBlacklistMapper.selectByTokenHash(anyString())).thenReturn(null)
            val service = createService()

            // When
            service.isBlacklisted("token-a")
            service.isBlacklisted("token-b")

            // Then
            verify(tokenBlacklistMapper).selectByTokenHash(sha256("token-a"))
            verify(tokenBlacklistMapper).selectByTokenHash(sha256("token-b"))
            assertNotEquals(sha256("token-a"), sha256("token-b"))
        }
    }

    @Nested
    @DisplayName("Concurrency Tests")
    inner class ConcurrencyTests {

        @Test
        @DisplayName("addToBlacklist - Concurrent inserts of same token do not throw")
        fun `concurrent addToBlacklist of same token should not throw`() {
            // Given
            `when`(tokenBlacklistMapper.insert(any())).thenReturn(1)
            val service = createService()
            val expireTime = LocalDateTime.now().plusHours(2)

            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val errorCount = AtomicInteger(0)

            // When - all threads blacklist the same token simultaneously
            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        service.addToBlacklist("same-token", "admin", 1L, expireTime, "logout")
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "all threads should finish in time")
            executor.shutdown()

            // Then - no exception escapes and every call reached the mapper
            assertEquals(0, errorCount.get(), "concurrent addToBlacklist must not throw")
            verify(tokenBlacklistMapper, org.mockito.Mockito.times(threadCount)).insert(any())
        }

        @Test
        @DisplayName("addToBlacklist - DuplicateKeyException from mapper is swallowed")
        fun `concurrent addToBlacklist should swallow DuplicateKeyException from mapper`() {
            // Given - the impl catches all exceptions so logout is never broken;
            // simulate the second-and-later concurrent inserts hitting the unique constraint
            `when`(tokenBlacklistMapper.insert(any()))
                .thenReturn(1)
                .thenThrow(DuplicateKeyException("duplicate token_hash"))
            val service = createService()
            val expireTime = LocalDateTime.now().plusHours(2)

            val threadCount = 8
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val errorCount = AtomicInteger(0)

            // When
            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        service.addToBlacklist("dup-token", "admin", 1L, expireTime, "logout")
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "all threads should finish in time")
            executor.shutdown()

            // Then - DuplicateKeyException is swallowed inside the service, callers never see it
            assertEquals(0, errorCount.get(), "DuplicateKeyException must be swallowed by the service")
            verify(tokenBlacklistMapper, org.mockito.Mockito.times(threadCount)).insert(any())
        }

        @Test
        @DisplayName("addToBlacklist - Single-thread DuplicateKeyException also swallowed")
        fun `addToBlacklist should not throw when mapper throws DuplicateKeyException`() {
            // Given
            `when`(tokenBlacklistMapper.insert(any())).thenThrow(DuplicateKeyException("duplicate token_hash"))

            // When & Then
            assertDoesNotThrow {
                createService().addToBlacklist("dup-token", "admin", 1L, LocalDateTime.now().plusHours(2), "logout")
            }
        }

        @Test
        @DisplayName("isBlacklisted - Concurrent reads return consistent results")
        fun `concurrent isBlacklisted reads should return consistent results`() {
            // Given - "mock-token" is blacklisted, "clean-token" is not
            `when`(tokenBlacklistMapper.selectByTokenHash(sha256("mock-token"))).thenReturn(testBlacklist)
            `when`(tokenBlacklistMapper.selectByTokenHash(sha256("clean-token"))).thenReturn(null)
            val service = createService()

            val threadCount = 16
            val readsPerThread = 5
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val blacklistedHits = AtomicInteger(0)
            val cleanHits = AtomicInteger(0)
            val errorCount = AtomicInteger(0)

            // When - half of the threads read the blacklisted token, the other half the clean one
            repeat(threadCount) { index ->
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(readsPerThread) {
                            if (index % 2 == 0) {
                                if (service.isBlacklisted("mock-token")) blacklistedHits.incrementAndGet()
                            } else {
                                if (!service.isBlacklisted("clean-token")) cleanHits.incrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "all threads should finish in time")
            executor.shutdown()

            // Then - every read observed the expected consistent result
            assertEquals(0, errorCount.get(), "concurrent isBlacklisted must not throw")
            assertEquals(threadCount / 2 * readsPerThread, blacklistedHits.get(), "blacklisted token must always return true")
            assertEquals(threadCount / 2 * readsPerThread, cleanHits.get(), "clean token must always return false")
        }
    }
}
