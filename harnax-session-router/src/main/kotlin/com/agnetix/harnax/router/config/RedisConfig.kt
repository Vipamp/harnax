package com.agnetix.harnax.router.config

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisSentinelConfiguration
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * Redis wiring for cluster mode (`router.cache.type=redis`).
 *
 * The connection factory is built manually instead of via Boot's auto-configuration, because the
 * auto-config is excluded for `local` mode (see application.yml). Every `spring.data.redis.*`
 * property that this router actually relies on is therefore read explicitly here: a silently
 * ignored timeout would turn a Redis stall into 60s of blocked request threads.
 *
 * Commands fail fast when the connection is down rather than being queued, so callers can reach
 * their fallback path instead of hanging until the command timeout.
 *
 * Redis Cluster is deliberately not supported, and configuring it fails startup rather than being
 * ignored: the routing scripts are multi-key by design (writing a session binding also updates the
 * per-instance reverse index, and an instance status transition shares a script with the healthy
 * set), so Redis would reject all of them with CROSSSLOT. That is not a silent degradation either
 * way -- heartbeats and the health-check loop would start erroring while the service looked up.
 * Standalone and Sentinel give the same HA story this router actually needs, since its whole
 * dataset is a few megabytes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
class RedisConfig {

    private val log = LoggerFactory.getLogger(RedisConfig::class.java)

    companion object {
        private const val DEFAULT_SENTINEL_PORT = 26379
    }

    @Bean
    fun redisConnectionFactory(
        @Value("\${spring.data.redis.host:}") host: String,
        @Value("\${spring.data.redis.port:6379}") port: Int,
        @Value("\${spring.data.redis.username:}") username: String,
        @Value("\${spring.data.redis.password:}") password: String,
        @Value("\${spring.data.redis.database:0}") database: Int,
        @Value("\${spring.data.redis.timeout:3000}") timeoutMs: Long,
        @Value("\${spring.data.redis.connect-timeout:2000}") connectTimeoutMs: Long,
        @Value("\${spring.data.redis.sentinel.master:}") sentinelMaster: String,
        @Value("\${spring.data.redis.sentinel.nodes:}") sentinelNodes: String,
        @Value("\${spring.data.redis.sentinel.password:}") sentinelPassword: String,
        @Value("\${spring.data.redis.cluster.nodes:}") clusterNodes: String,
    ): RedisConnectionFactory {
        // Cluster is checked first: it is rejected outright, and the caller must not get a
        // "host is missing" hint when the real problem is an unsupported topology.
        val configuration = when {
            clusterNodes.isNotBlank() -> clusterUnsupported(clusterNodes)
            sentinelMaster.isNotBlank() -> sentinelConfiguration(sentinelMaster, sentinelNodes, sentinelPassword)
            else -> standaloneConfiguration(host, port, database)
        }.apply {
            if (password.isNotBlank()) setPassword(RedisPassword.of(password))
            if (username.isNotBlank()) setUsername(username)
        }

        val clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(timeoutMs))
            .shutdownTimeout(Duration.ofMillis(timeoutMs))
            .clientOptions(
                ClientOptions.builder()
                    .socketOptions(
                        SocketOptions.builder()
                            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                            .build(),
                    )
                    // A dropped connection must surface as an exception on the calling thread so
                    // routing can fall back; queueing commands would exhaust the servlet pool.
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .autoReconnect(true)
                    .build(),
            )
            .build()

        log.info(
            "Redis connection configured: mode={}, commandTimeoutMs={}, connectTimeoutMs={}",
            configuration.javaClass.simpleName.removeSuffix("Configuration").lowercase(),
            timeoutMs,
            connectTimeoutMs,
        )

        return LettuceConnectionFactory(configuration, clientConfig)
    }

    private fun standaloneConfiguration(
        host: String,
        port: Int,
        database: Int,
    ): RedisStandaloneConfiguration {
        check(host.isNotBlank()) {
            "router.cache.type=redis requires spring.data.redis.host (set REDIS_HOST), " +
                "or sentinel.master (REDIS_SENTINEL_MASTER) for a highly available Redis"
        }
        return RedisStandaloneConfiguration(host, port).apply { setDatabase(database) }
    }

    private fun sentinelConfiguration(
        master: String,
        nodes: String,
        sentinelPassword: String,
    ): RedisSentinelConfiguration {
        val config = RedisSentinelConfiguration().master(master)
        nodes.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { node ->
            val parts = node.split(":", limit = 2)
            val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_SENTINEL_PORT
            config.sentinel(parts[0], port)
        }
        if (sentinelPassword.isNotBlank()) {
            config.setSentinelPassword(RedisPassword.of(sentinelPassword))
        }
        return config
    }

    private fun clusterUnsupported(nodes: String): Nothing = throw IllegalStateException(
        "Redis Cluster is not supported by this router (spring.data.redis.cluster.nodes=$nodes). " +
            "A session binding and its per-instance index entry are updated by one Lua script, and an " +
            "instance status transition shares a script with the healthy set; those keys do not hash to " +
            "one slot, so Redis would answer every heartbeat, binding and failover with CROSSSLOT. " +
            "Use a standalone Redis, or Sentinel for HA (REDIS_SENTINEL_MASTER / REDIS_SENTINEL_NODES).",
    )

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
        val template = RedisTemplate<String, Any>()
        template.connectionFactory = connectionFactory

        // Spring Data Redis 4.x still uses Jackson 2.x (com.fasterxml) for serialization.
        // Restrict allowed types to prevent deserialization RCE attacks.
        val ptv = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType("com.agnetix.harnax.")
            .allowIfBaseType("java.util.")
            .allowIfBaseType("java.lang.")
            .allowIfSubType("com.agnetix.harnax.")
            .allowIfSubType("java.util.")
            .allowIfSubType("java.lang.")
            .build()
        val objectMapper = ObjectMapper().apply {
            setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
            activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL)
        }

        val jsonSerializer = GenericJackson2JsonRedisSerializer(objectMapper)
        val stringSerializer = StringRedisSerializer()

        template.keySerializer = stringSerializer
        template.hashKeySerializer = stringSerializer
        template.valueSerializer = jsonSerializer
        template.hashValueSerializer = jsonSerializer

        template.afterPropertiesSet()
        return template
    }
}
