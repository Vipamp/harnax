package com.agnetix.harnax.router.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

/**
 * The topology decision of [RedisConfig]. These are plain constructor calls: no connection is
 * opened until the factory is started, so nothing here needs a Redis to run against.
 */
class RedisConfigTest {

    private fun factory(
        host: String = "",
        sentinelMaster: String = "",
        clusterNodes: String = "",
    ) = RedisConfig().redisConnectionFactory(
        host = host,
        port = 6379,
        username = "",
        password = "",
        database = 0,
        timeoutMs = 3000,
        connectTimeoutMs = 2000,
        sentinelMaster = sentinelMaster,
        sentinelNodes = "",
        sentinelPassword = "",
        clusterNodes = clusterNodes,
    )

    @Test
    fun `a Redis Cluster topology is refused with an explanation`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            factory(host = "redis-host", clusterNodes = "10.0.0.1:6379,10.0.0.2:6379")
        }

        assert(failure.message!!.contains("Redis Cluster is not supported"))
        // The message has to say what to do instead, not just that this one is closed.
        assert(failure.message!!.contains("CROSSSLOT"))
        assert(failure.message!!.contains("REDIS_SENTINEL_MASTER"))
    }

    @Test
    fun `the cluster refusal wins over the missing-host hint`() {
        // Both would be "misconfigured", but sending the operator off to set REDIS_HOST when the
        // real problem is an unsupported topology would waste the debugging session.
        val failure = assertThrows(IllegalStateException::class.java) {
            factory(clusterNodes = "10.0.0.1:6379")
        }

        assert(failure.message!!.contains("Redis Cluster is not supported"))
        assert(!failure.message!!.contains("requires spring.data.redis.host"))
    }

    @Test
    fun `standalone is wired`() {
        // No assertDoesNotThrow wrapper: its JUnit overloads resolve to the Executable one from
        // Kotlin, which would swallow the factory and hand back a Unit.
        val connectionFactory = factory(host = "10.0.0.1")

        assert(connectionFactory is LettuceConnectionFactory)
    }

    @Test
    fun `sentinel stays the supported way to run highly available`() {
        val connectionFactory = factory(host = "ignored", sentinelMaster = "my-master")

        assert(connectionFactory is LettuceConnectionFactory)
        val sentinel = (connectionFactory as LettuceConnectionFactory).sentinelConfiguration
        assert(sentinel?.master?.name == "my-master")
    }

    @Test
    fun `redis mode without any host names the variable to set`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            factory()
        }

        assert(failure.message!!.contains("REDIS_HOST"))
    }
}
