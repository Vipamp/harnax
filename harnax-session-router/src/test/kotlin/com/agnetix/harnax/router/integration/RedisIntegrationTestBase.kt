package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.config.RedisConfig
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
abstract class RedisIntegrationTestBase {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        private var cachedRedisTemplate: RedisTemplate<String, Any>? = null
        private var cachedConnectionFactory: LettuceConnectionFactory? = null
        private var cachedPort: Int = -1

        /**
         * Builds the template through [RedisConfig] so the serializers here are byte-for-byte the
         * ones production uses. The Lua scripts compare stored values against their JSON-encoded
         * form, so a different ObjectMapper would make these tests prove nothing.
         */
        fun getRedisTemplate(): RedisTemplate<String, Any> {
            val currentPort = redis.firstMappedPort
            if (cachedRedisTemplate == null || cachedPort != currentPort) {
                // Clean up old connection if port changed (new container instance)
                cachedConnectionFactory?.destroy()

                val config = RedisStandaloneConfiguration(redis.host, currentPort)
                cachedConnectionFactory = LettuceConnectionFactory(config).apply { afterPropertiesSet() }
                cachedRedisTemplate = RedisConfig().redisTemplate(cachedConnectionFactory!!)
                cachedPort = currentPort
            }
            return cachedRedisTemplate!!
        }

        fun flushRedis() {
            getRedisTemplate().connectionFactory?.connection?.serverCommands()?.flushAll()
        }
    }
}
