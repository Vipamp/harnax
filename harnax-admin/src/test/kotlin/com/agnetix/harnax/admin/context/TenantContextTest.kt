package com.agnetix.harnax.admin.context

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TenantContext 单元测试
 * 覆盖 set/get/clear 基本操作、默认值以及 ThreadLocal 多线程隔离语义
 *
 * @author agnetix
 * @since 2026-06-28
 */
@DisplayName("TenantContext 租户上下文测试")
class TenantContextTest {

    @AfterEach
    fun tearDown() {
        // 清理 ThreadLocal，防止租户上下文串到其他测试
        TenantContext.clear()
    }

    @Nested
    @DisplayName("基本操作测试")
    inner class BasicOperationTests {

        @Test
        @DisplayName("setTenantId/getTenantId - 设置后可正确读取")
        fun `getTenantId should return value after set`() {
            // When
            TenantContext.setTenantId(100L)

            // Then
            assertEquals(100L, TenantContext.getTenantId())
        }

        @Test
        @DisplayName("getTenantId - 未设置时返回 null")
        fun `getTenantId should return null when not set`() {
            // Then - 默认值为 null
            assertNull(TenantContext.getTenantId())
        }

        @Test
        @DisplayName("setTenantId - 重复设置覆盖旧值")
        fun `setTenantId should overwrite previous value`() {
            // Given
            TenantContext.setTenantId(1L)

            // When
            TenantContext.setTenantId(2L)

            // Then
            assertEquals(2L, TenantContext.getTenantId())
        }

        @Test
        @DisplayName("clear - 清理后取不到租户ID")
        fun `clear should remove tenant id`() {
            // Given
            TenantContext.setTenantId(100L)
            assertEquals(100L, TenantContext.getTenantId())

            // When
            TenantContext.clear()

            // Then
            assertNull(TenantContext.getTenantId())
        }

        @Test
        @DisplayName("clear - 未设置时清理不报错")
        fun `clear should be safe when nothing set`() {
            // When & Then - 不抛异常
            TenantContext.clear()
            assertNull(TenantContext.getTenantId())
        }
    }

    @Nested
    @DisplayName("多线程隔离测试")
    inner class ThreadIsolationTests {

        @Test
        @DisplayName("多线程 - 两个线程各自 set 互不串扰")
        fun `tenant context should be isolated between threads`() {
            // Given
            val bothSetLatch = CountDownLatch(2)
            val readLatch = CountDownLatch(1)
            val thread1Value = AtomicReference<Long?>()
            val thread2Value = AtomicReference<Long?>()

            val thread1 = Thread {
                TenantContext.setTenantId(111L)
                bothSetLatch.countDown()
                // 等两个线程都 set 完之后再读，验证互不覆盖
                readLatch.await(5, TimeUnit.SECONDS)
                thread1Value.set(TenantContext.getTenantId())
                TenantContext.clear()
            }
            val thread2 = Thread {
                TenantContext.setTenantId(222L)
                bothSetLatch.countDown()
                readLatch.await(5, TimeUnit.SECONDS)
                thread2Value.set(TenantContext.getTenantId())
                TenantContext.clear()
            }

            // When
            thread1.start()
            thread2.start()
            assertTrue(bothSetLatch.await(5, TimeUnit.SECONDS), "两个线程应都完成 set")
            readLatch.countDown()
            thread1.join(5000)
            thread2.join(5000)

            // Then - 各线程读到自己设置的值
            assertEquals(111L, thread1Value.get())
            assertEquals(222L, thread2Value.get())
        }

        @Test
        @DisplayName("多线程 - 主线程设置的值对子线程不可见")
        fun `child thread should not see main thread tenant id`() {
            // Given
            TenantContext.setTenantId(999L)
            val childValue = AtomicReference<Long?>(-1L)

            // When
            val child = Thread {
                childValue.set(TenantContext.getTenantId())
            }
            child.start()
            child.join(5000)

            // Then - 普通 ThreadLocal 不继承到子线程
            assertNull(childValue.get(), "子线程不应看到主线程的租户ID")
            assertEquals(999L, TenantContext.getTenantId(), "主线程的值不受影响")
        }

        @Test
        @DisplayName("多线程 - 子线程 clear 不影响主线程")
        fun `clear in child thread should not affect main thread`() {
            // Given
            TenantContext.setTenantId(100L)

            // When - 子线程内 set 后 clear
            val child = Thread {
                TenantContext.setTenantId(200L)
                TenantContext.clear()
            }
            child.start()
            child.join(5000)

            // Then
            assertEquals(100L, TenantContext.getTenantId())
        }
    }
}
