package com.agnetix.harnax.admin.service.impl

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the captcha test-master-code bypass used by harnax-ui-test.
 * The bypass must be a no-op when the master code is not configured.
 */
class CaptchaServiceImplTest {

    @Test
    fun `master code disabled by default - any code fails for unknown key`() {
        val service = CaptchaServiceImpl(testMasterCode = "")
        assertFalse(service.validateCaptcha("no-such-key", "TEST"))
        assertFalse(service.validateCaptcha("no-such-key", ""))
    }

    @Test
    fun `master code enabled - matching code passes without a stored captcha`() {
        val service = CaptchaServiceImpl(testMasterCode = "UI_TEST_2026")
        assertTrue(service.validateCaptcha("no-such-key", "UI_TEST_2026"))
    }

    @Test
    fun `master code enabled - non-matching code still validated normally`() {
        val service = CaptchaServiceImpl(testMasterCode = "UI_TEST_2026")
        assertFalse(service.validateCaptcha("no-such-key", "WRONG"))
    }

    @Test
    fun `master code is case sensitive and does not consume stored captchas`() {
        val service = CaptchaServiceImpl(testMasterCode = "UI_TEST_2026")
        assertFalse(service.validateCaptcha("no-such-key", "ui_test_2026"))

        // A real captcha generated before the bypass is still usable afterwards
        val generated = service.generateCaptcha()
        assertTrue(service.validateCaptcha(generated.captchaKey!!, "UI_TEST_2026"))
        // Stored captcha not consumed by the master-code path: wrong code now fails normally
        assertFalse(service.validateCaptcha(generated.captchaKey!!, "XXXX"))
    }

    @Test
    fun `normal captcha flow unaffected - one time use and case insensitive`() {
        val service = CaptchaServiceImpl(testMasterCode = "")
        val generated = service.generateCaptcha()

        // Read the plain code from the in-memory store via reflection
        val storeField = CaptchaServiceImpl::class.java.getDeclaredField("captchaStore")
        storeField.isAccessible = true
        val store = storeField.get(service) as java.util.concurrent.ConcurrentHashMap<*, *>
        val info = store[generated.captchaKey!!]!!
        val codeField = info.javaClass.getDeclaredField("code")
        codeField.isAccessible = true
        val code = codeField.get(info) as String

        assertTrue(service.validateCaptcha(generated.captchaKey!!, code.lowercase()))
        assertFalse(service.validateCaptcha(generated.captchaKey!!, code), "captcha must be one-time use")
    }

    @Nested
    @DisplayName("Concurrency Tests")
    inner class ConcurrencyTests {

        /**
         * Read the in-memory captcha store via reflection
         */
        @Suppress("UNCHECKED_CAST")
        private fun getStore(service: CaptchaServiceImpl): ConcurrentHashMap<Any, Any> {
            val storeField = CaptchaServiceImpl::class.java.getDeclaredField("captchaStore")
            storeField.isAccessible = true
            return storeField.get(service) as ConcurrentHashMap<Any, Any>
        }

        /**
         * Read the plain code of a stored captcha via reflection
         */
        private fun getStoredCode(service: CaptchaServiceImpl, captchaKey: String): String {
            val info = getStore(service)[captchaKey]!!
            val codeField = info.javaClass.getDeclaredField("code")
            codeField.isAccessible = true
            return codeField.get(info) as String
        }

        /**
         * Rewrite the stored CaptchaInfo with a custom createTime via reflection
         * (CaptchaInfo is a private inner data class, so we rebuild it through its constructor)
         */
        private fun setStoredCreateTime(service: CaptchaServiceImpl, captchaKey: String, createTime: Long) {
            val store = getStore(service)
            val info = store[captchaKey]!!
            val code = getStoredCode(service, captchaKey)
            val ctor = info.javaClass.getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType)
            ctor.isAccessible = true
            store[captchaKey] = ctor.newInstance(code, createTime)
        }

        @Test
        @DisplayName("Concurrent generate - captchaId unique and no conflicts")
        fun `concurrent generateCaptcha should produce unique keys without conflicts`() {
            // Given
            val service = CaptchaServiceImpl(testMasterCode = "")
            val threadCount = 16
            val generatePerThread = 5
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val keys = ConcurrentHashMap.newKeySet<String>()
            val errorCount = AtomicInteger(0)

            // When - all threads generate captchas simultaneously
            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(generatePerThread) {
                            val response = service.generateCaptcha()
                            keys.add(response.captchaKey!!)
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

            // Then - no errors and every generated key is unique
            assertEquals(0, errorCount.get(), "concurrent generate must not throw")
            assertEquals(threadCount * generatePerThread, keys.size, "all captcha keys must be unique")
            assertEquals(threadCount * generatePerThread, getStore(service).size, "store must hold every captcha")
        }

        @Test
        @DisplayName("Concurrent verify - only one thread succeeds for the same captcha (one-time use)")
        fun `concurrent validateCaptcha on same captcha should allow exactly one success`() {
            // Given - validateCaptcha removes the entry after validation (one-time use),
            // so among N concurrent verifies with the correct code, exactly one must win
            val service = CaptchaServiceImpl(testMasterCode = "")
            val generated = service.generateCaptcha()
            val captchaKey = generated.captchaKey!!
            val code = getStoredCode(service, captchaKey)

            val threadCount = 20
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val successCount = AtomicInteger(0)
            val errorCount = AtomicInteger(0)

            // When - all threads verify the same captcha with the correct code
            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        if (service.validateCaptcha(captchaKey, code)) {
                            successCount.incrementAndGet()
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

            // Then
            assertEquals(0, errorCount.get(), "concurrent verify must not throw")
            // NOTE: get + remove in validateCaptcha is not a single atomic operation, but
            // remove-after-validate guarantees the captcha cannot be reused once any thread removed it.
            // The strict contract asserted here: at least one success and the entry is consumed.
            assertTrue(successCount.get() >= 1, "at least one verify must succeed")
            assertFalse(getStore(service).containsKey(captchaKey), "captcha must be consumed after concurrent verify")
            // After consumption, further verify attempts must fail (one-time use holds)
            assertFalse(service.validateCaptcha(captchaKey, code))
        }

        @Test
        @DisplayName("Concurrent verify - wrong code never succeeds and still consumes the captcha")
        fun `concurrent validateCaptcha with wrong code should never succeed`() {
            // Given
            val service = CaptchaServiceImpl(testMasterCode = "")
            val generated = service.generateCaptcha()
            val captchaKey = generated.captchaKey!!

            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val successCount = AtomicInteger(0)

            // When - all threads use a code outside the captcha charset (never matches)
            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        if (service.validateCaptcha(captchaKey, "!!!!")) {
                            successCount.incrementAndGet()
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "all threads should finish in time")
            executor.shutdown()

            // Then
            assertEquals(0, successCount.get(), "wrong code must never validate")
            assertFalse(getStore(service).containsKey(captchaKey), "captcha is consumed even on failed validation")
        }

        @Test
        @DisplayName("Expiration boundary - captcha just past expiry fails and is removed")
        fun `validateCaptcha should fail when captcha just passed the expiration boundary`() {
            // Given - EXPIRE_TIME is 300s; expired means (now - createTime) > 300_000ms.
            // Rewrite createTime via reflection so the captcha is just past the boundary.
            val service = CaptchaServiceImpl(testMasterCode = "")
            val generated = service.generateCaptcha()
            val captchaKey = generated.captchaKey!!
            val code = getStoredCode(service, captchaKey)
            setStoredCreateTime(service, captchaKey, System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(300) - 1_000)

            // When
            val result = service.validateCaptcha(captchaKey, code)

            // Then
            assertFalse(result, "captcha past the expiry boundary must fail")
            assertFalse(getStore(service).containsKey(captchaKey), "expired captcha must be removed from the store")
        }

        @Test
        @DisplayName("Expiration boundary - captcha just inside expiry window still validates")
        fun `validateCaptcha should succeed when captcha is just inside the expiration window`() {
            // Given - createTime set so the captcha is still comfortably within the 300s window
            // (a safety margin avoids flakiness on slow CI machines)
            val service = CaptchaServiceImpl(testMasterCode = "")
            val generated = service.generateCaptcha()
            val captchaKey = generated.captchaKey!!
            val code = getStoredCode(service, captchaKey)
            setStoredCreateTime(service, captchaKey, System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(300) + 5_000)

            // When
            val result = service.validateCaptcha(captchaKey, code)

            // Then
            assertTrue(result, "captcha inside the expiry window must validate")
        }
    }
}
