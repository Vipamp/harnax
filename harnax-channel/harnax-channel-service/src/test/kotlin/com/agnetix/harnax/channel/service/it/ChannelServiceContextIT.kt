package com.agnetix.harnax.channel.service.it

import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.monitor.MicrometerChannelMetricsSink
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.service.ChannelServiceApplication
import com.agnetix.harnax.channel.service.bootstrap.ChannelBootstrapRunner
import com.agnetix.harnax.channel.service.bootstrap.ChannelListenerLockGuard
import com.agnetix.harnax.channel.service.client.RouterCircuitBreaker
import com.agnetix.harnax.channel.service.health.ChannelConnectionHealthIndicator
import com.agnetix.harnax.channel.service.monitor.ChannelRuntimeEndpoint
import com.agnetix.harnax.channel.service.monitor.ChannelRuntimeMonitor
import com.agnetix.harnax.channel.service.session.InMemoryChannelSessionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.http.client.ClientHttpResponse
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

/**
 * 对着真实 MySQL 把整个服务启起来。
 *
 * 单元测试测不出这里兜住的那几类问题：bean 构造器多了一个参数、actuator 路径被鉴权过滤器拦下、
 * `GET_LOCK` 其实一直没生效、健康指示器把 liveness 探针拖成失败，从而重启本来健康的 pod。
 *
 * 运行方式：`mvn -Pintegration-test verify`（需要本机能用 Docker）。
 */
@SpringBootTest(
    classes = [ChannelServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ChannelServiceContextIT {

    companion object {
        // 单独用一个锁名：对着本机 MySQL 跑这个用例时，既不能把锁从正在服务的实例手里抢走，也不能被它挡住。
        const val LOCK_NAME = "harnax-channel-listeners-it"
        const val HANDOVER_LOCK_NAME = "harnax-channel-listeners-handover-it"

        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_admin")
            .withUsername("root")
            .withPassword("it_test")

        init {
            mysql.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            // 不配这个 key，ChannelApiKeyInitializer 会在容器刷新期间去访问 harnax-admin，
            // 整个用例最后以一个没人想测的 IllegalStateException 收场。
            registry.add("channel.router-api-key") { "it-router-key" }
            // 第一轮 reconcile 在 ApplicationReadyEvent 上跑，调度间隔拉到 1 小时，
            // 免得第二轮跟下面的断言抢跑。
            registry.add("channel.sync.interval-ms") { Duration.ofHours(1).toMillis() }
            registry.add("channel.lock.name") { LOCK_NAME }
            // 真实频道处于 FAILED 本来就该告警，这里把宽限期设成 0，
            // 好让下面那个 liveness 隔离用例每次结果都一样。
            registry.add("channel.monitor.health.failure-grace-ms") { 0 }
        }
    }

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var lockGuard: ChannelListenerLockGuard

    @Autowired
    lateinit var monitor: ChannelRuntimeMonitor

    @LocalServerPort
    var port: Int = 0

    private val json: ObjectMapper = jacksonObjectMapper()

    private val rest: RestTemplate by lazy {
        RestTemplate().apply {
            // 有频道挂掉时 /actuator/health 就是故意回 503：用例要断言状态码和响应体，而不是去接异常。
            errorHandler = object : ResponseErrorHandler {
                override fun hasError(response: ClientHttpResponse): Boolean = false
            }
        }
    }

    private fun fullUrl(path: String): String = "http://localhost:$port$path"

    private fun body(path: String): JsonNode = json.readTree(rest.getForObject(fullUrl(path), String::class.java)!!)

    private fun status(path: String): Int = rest.getForEntity(fullUrl(path), String::class.java).statusCode.value()

    private fun standaloneGuard(lockName: String) = ChannelListenerLockGuard(
        DriverManagerDataSource().apply {
            setDriverClassName("com.mysql.cj.jdbc.Driver")
            url = mysql.jdbcUrl
            setUsername(mysql.username)
            setPassword(mysql.password)
        },
        lockEnabled = true,
        lockName = lockName,
    )

    @Test
    fun `the context starts with the bean graph changed by the runtime work`() {
        // 前面六个阶段里，这几个 bean 的构造器参数都动过。
        assertNotNull(context.getBean(ChannelRuntimeEndpoint::class.java))
        assertNotNull(context.getBean(ChannelListenerLockGuard::class.java))
        assertNotNull(context.getBean(RouterCircuitBreaker::class.java))
        assertNotNull(context.getBean(ChannelConnectionHealthIndicator::class.java))
        assertNotNull(context.getBean(ChannelBootstrapRunner::class.java))
        assertNotNull(context.getBean(ChannelTurnExecutor::class.java))

        assertTrue(
            context.getBean(ChannelSessionManager::class.java) is InMemoryChannelSessionManager,
            "the bounded session manager must be the one wired in, not an unbounded replacement",
        )
        assertTrue(
            context.getBean(ChannelMetricsSink::class.java) is MicrometerChannelMetricsSink,
            "the sink now ships with the SDK; a missing binding means the metrics quietly stop",
        )
    }

    @Test
    fun `startup reconciles without a channel table and still owns the lock`() {
        // 这里 Flyway 是关掉的（表结构归 admin 管），所以容器里没有 `channel` 表：
        // 读不到配置的那一轮只能记条日志返回，不能导致容器启动失败。
        assertEquals("channels=none", monitor.summaryLine())
        assertTrue(lockGuard.hold())
    }

    @Test
    fun `the listener lock keeps a second replica from serving`() {
        assertTrue(lockGuard.hold())

        val rival = standaloneGuard(LOCK_NAME)
        try {
            assertFalse(
                rival.hold(),
                "two replicas on one credential duplicate and drop user messages; GET_LOCK is the only thing preventing it",
            )
            assertTrue(lockGuard.isHeld(), "the owner must not lose the lock by being polled")
        } finally {
            rival.release()
        }
    }

    @Test
    fun `the lock is handed over when the owner goes away`() {
        val owner = standaloneGuard(HANDOVER_LOCK_NAME)
        val rival = standaloneGuard(HANDOVER_LOCK_NAME)
        try {
            assertTrue(owner.hold())
            assertFalse(rival.hold())

            owner.release()
            assertTrue(rival.hold(), "a rolling restart must not wait for the old connection to time out")
        } finally {
            rival.release()
        }
    }

    @Test
    fun `the runtime payload is readable without a service token`() {
        // `/api/channel/**` 在 UnifiedAuthFilter 后面，actuator 这棵树是有意豁免的：
        // 排障时用 curl 或者看板拉这份数据都不用带服务 token。
        assertEquals(200, status("/actuator/channels"))
        val payload = body("/actuator/channels")

        assertEquals(LOCK_NAME, payload["listenerLock"]["name"].asString())
        assertTrue(payload["listenerLock"]["held"].asBoolean(), "this instance owns the lock after startup")
        assertEquals(0, payload["summary"]["total"].asInt())
        assertEquals("CLOSED", payload["routerCircuit"]["state"].asString())
        assertTrue(payload["channels"].isEmpty())
    }

    @Test
    fun `a failed channel goes red without taking the liveness probe down`() {
        monitor.trackExpected(999_999L, "it-channel", "feishu", "websocket")
        monitor.markStartupFailed(999_999L, "invalid app secret")
        try {
            assertEquals(503, status("/actuator/health"), "the aggregate status has to alert")
            val health = body("/actuator/health")
            assertEquals("DOWN", health["status"].asString())
            assertEquals("DOWN", health["components"]["channelConnections"]["status"].asString())

            // 飞书的长连接断了属于服务降级，不是 JVM 坏了：重启 pod 会把其他频道一起带走。
            assertEquals(200, status("/actuator/health/liveness"))
            assertEquals("UP", body("/actuator/health/liveness")["status"].asString())
        } finally {
            monitor.untrackExpected(999_999L)
        }
    }
}
