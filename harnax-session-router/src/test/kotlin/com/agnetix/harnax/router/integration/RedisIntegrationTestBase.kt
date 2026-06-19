package com.agnetix.harnax.router.integration

import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
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

        fun getRedisTemplate(): RedisTemplate<String, Any> {
            val currentPort = redis.firstMappedPort
            if (cachedRedisTemplate == null || cachedPort != currentPort) {
                // Clean up old connection if port changed (new container instance)
                cachedConnectionFactory?.destroy()

                val config = RedisStandaloneConfiguration(redis.host, currentPort)
                cachedConnectionFactory = LettuceConnectionFactory(config).apply { afterPropertiesSet() }

                cachedRedisTemplate = RedisTemplate<String, Any>().apply {
                    connectionFactory = cachedConnectionFactory
                    keySerializer = StringRedisSerializer()
                    hashKeySerializer = StringRedisSerializer()
                    valueSerializer = GenericJackson2JsonRedisSerializer()
                    hashValueSerializer = GenericJackson2JsonRedisSerializer()
                    afterPropertiesSet()
                }
                cachedPort = currentPort
            }
            return cachedRedisTemplate!!
        }

        fun flushRedis() {
            getRedisTemplate().connectionFactory?.connection?.serverCommands()?.flushAll()
        }
    }
}
