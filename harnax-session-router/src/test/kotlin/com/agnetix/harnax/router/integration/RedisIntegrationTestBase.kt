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

@Testcontainers
abstract class RedisIntegrationTestBase {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        private var _redisTemplate: RedisTemplate<String, Any>? = null
        private var _connectionFactory: LettuceConnectionFactory? = null

        fun getRedisTemplate(): RedisTemplate<String, Any> {
            if (_redisTemplate == null) {
                val config = RedisStandaloneConfiguration(redis.host, redis.firstMappedPort)
                _connectionFactory = LettuceConnectionFactory(config).apply { afterPropertiesSet() }

                _redisTemplate = RedisTemplate<String, Any>().apply {
                    connectionFactory = _connectionFactory
                    keySerializer = StringRedisSerializer()
                    hashKeySerializer = StringRedisSerializer()
                    valueSerializer = GenericJackson2JsonRedisSerializer()
                    hashValueSerializer = GenericJackson2JsonRedisSerializer()
                    afterPropertiesSet()
                }
            }
            return _redisTemplate!!
        }

        fun flushRedis() {
            _redisTemplate?.connectionFactory?.connection?.serverCommands()?.flushAll()
        }
    }
}
