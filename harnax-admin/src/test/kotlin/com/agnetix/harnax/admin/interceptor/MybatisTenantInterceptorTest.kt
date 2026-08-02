package com.agnetix.harnax.admin.interceptor

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.entity.Agent
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.plugin.Invocation
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.util.Properties

/**
 * MybatisTenantInterceptor 单元测试
 * 使用 Mockito 模拟 MyBatis 的 Invocation/MappedStatement 等对象
 *
 * 注意：当前主代码 intercept() 中的租户 SQL 改写逻辑已被注释禁用，
 * 拦截器实际行为是直接放行（invocation.proceed()），不改写 SQL、不注入参数。
 * 本测试锁定该"透传"语义：无论是否存在租户上下文、无论 SQL 类型，
 * 均应放行且不修改任何参数。若后续启用改写逻辑，这些用例需同步调整。
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MybatisTenantInterceptor MyBatis 租户拦截器测试")
class MybatisTenantInterceptorTest {

    @Mock
    private lateinit var invocation: Invocation

    @Mock
    private lateinit var mappedStatement: MappedStatement

    private lateinit var interceptor: MybatisTenantInterceptor

    private val configuration = Configuration()

    @BeforeEach
    fun setUp() {
        interceptor = MybatisTenantInterceptor()
        TenantContext.clear()
    }

    @AfterEach
    fun tearDown() {
        // 清理 ThreadLocal，防止租户上下文串到其他测试
        TenantContext.clear()
    }

    private fun buildBoundSql(sql: String): BoundSql = BoundSql(configuration, sql, emptyList(), null)

    @Nested
    @DisplayName("有租户上下文时的拦截测试")
    inner class WithTenantContextTests {

        @Test
        @DisplayName("intercept - SELECT 查询放行且 SQL 不被改写（改写逻辑当前已禁用）")
        fun `intercept should proceed and not rewrite select sql when tenant context exists`() {
            // Given
            TenantContext.setTenantId(100L)
            val boundSql = buildBoundSql("SELECT * FROM agent")
            val args = arrayOf<Any?>(mappedStatement, null, null, null, null, boundSql)
            val expected = listOf(Agent())

            `when`(invocation.args).thenReturn(args)
            `when`(invocation.proceed()).thenReturn(expected)

            // When
            val result = interceptor.intercept(invocation)

            // Then
            assertSame(expected, result, "应返回 proceed() 的结果")
            verify(invocation, times(1)).proceed()
            // 当前实现不改写 SQL：BoundSql 与 SQL 内容均保持不变
            assertSame(boundSql, args[5])
            assertEquals("SELECT * FROM agent", boundSql.sql)
        }

        @Test
        @DisplayName("intercept - UPDATE 语句放行不改写")
        fun `intercept should proceed for update statement without rewriting`() {
            // Given
            TenantContext.setTenantId(100L)
            val args = arrayOf<Any?>(mappedStatement, Agent())

            `when`(invocation.args).thenReturn(args)
            `when`(invocation.proceed()).thenReturn(1)

            // When
            val result = interceptor.intercept(invocation)

            // Then
            assertEquals(1, result)
            verify(invocation, times(1)).proceed()
        }

        @Test
        @DisplayName("intercept - INSERT 操作不自动注入 tenant_id（注入逻辑当前已禁用）")
        fun `intercept should not inject tenant id into insert parameter`() {
            // Given
            TenantContext.setTenantId(999L)
            val entity = Agent().apply { tenantId = 1L }
            val args = arrayOf<Any?>(mappedStatement, entity)

            `when`(invocation.args).thenReturn(args)
            `when`(invocation.proceed()).thenReturn(1)

            // When
            interceptor.intercept(invocation)

            // Then - 参数对象上的 tenantId 保持原值，未被覆盖
            assertEquals(1L, entity.tenantId)
            verify(invocation, times(1)).proceed()
        }
    }

    @Nested
    @DisplayName("无租户上下文时的拦截测试")
    inner class WithoutTenantContextTests {

        @Test
        @DisplayName("intercept - 无租户上下文时直接放行")
        fun `intercept should proceed when no tenant context`() {
            // Given - 未设置 TenantContext
            assertNull(TenantContext.getTenantId())
            val boundSql = buildBoundSql("SELECT * FROM agent WHERE id = 1")
            val args = arrayOf<Any?>(mappedStatement, null, null, null, null, boundSql)
            val expected = emptyList<Agent>()

            `when`(invocation.args).thenReturn(args)
            `when`(invocation.proceed()).thenReturn(expected)

            // When
            val result = interceptor.intercept(invocation)

            // Then
            assertSame(expected, result)
            verify(invocation, times(1)).proceed()
            assertEquals("SELECT * FROM agent WHERE id = 1", boundSql.sql)
        }

        @Test
        @DisplayName("intercept - proceed 抛出的异常向上传播")
        fun `intercept should propagate exception from proceed`() {
            // Given
            `when`(invocation.proceed()).thenThrow(RuntimeException("execution failed"))

            // When & Then
            val exception = org.junit.jupiter.api.assertThrows<RuntimeException> {
                interceptor.intercept(invocation)
            }
            assertEquals("execution failed", exception.message)
        }
    }

    @Nested
    @DisplayName("白名单/排除表语义测试")
    inner class ExcludedTableTests {

        @Test
        @DisplayName("intercept - 排除表（如 sys_user）的语句同样直接放行")
        fun `intercept should proceed for excluded table statements`() {
            // Given - EXCLUDED_TABLES 包含 sys_user，无论是否排除当前均为放行
            TenantContext.setTenantId(100L)
            val boundSql = buildBoundSql("SELECT * FROM sys_user WHERE username = ?")
            val args = arrayOf<Any?>(mappedStatement, null, null, null, null, boundSql)

            `when`(invocation.args).thenReturn(args)
            `when`(invocation.proceed()).thenReturn(emptyList<Any>())

            // When
            interceptor.intercept(invocation)

            // Then
            verify(invocation, times(1)).proceed()
            assertEquals("SELECT * FROM sys_user WHERE username = ?", boundSql.sql)
        }
    }

    @Nested
    @DisplayName("plugin 与 setProperties 测试")
    inner class PluginAndPropertiesTests {

        @Test
        @DisplayName("plugin - Executor 目标被包装为代理")
        fun `plugin should wrap executor target`() {
            // Given
            val executor = mock(Executor::class.java)

            // When
            val wrapped = interceptor.plugin(executor)

            // Then - @Intercepts 签名匹配 Executor 接口，应返回代理对象
            assertNotNull(wrapped)
            assertTrue(wrapped is Executor, "包装结果应仍是 Executor 类型")
            assertNotSame(executor, wrapped, "匹配签名的目标应被代理包装")
        }

        @Test
        @DisplayName("plugin - 非拦截签名目标原样返回")
        fun `plugin should return same instance for non-matching target`() {
            // Given
            val target = "not-an-executor"

            // When
            val wrapped = interceptor.plugin(target)

            // Then
            assertSame(target, wrapped, "不匹配 @Intercepts 签名的目标应原样返回")
        }

        @Test
        @DisplayName("setProperties - 传入属性或 null 均不抛异常")
        fun `setProperties should not throw for null or properties`() {
            assertDoesNotThrow {
                interceptor.setProperties(null)
                interceptor.setProperties(Properties().apply { setProperty("key", "value") })
            }
        }
    }
}
