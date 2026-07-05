package com.agnetix.harnax.router.config

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
class RedisConfig {

    @Bean
    fun redisConnectionFactory(
        @Value("\${spring.data.redis.host}") host: String,
        @Value("\${spring.data.redis.port:6379}") port: Int,
        @Value("\${spring.data.redis.password:}") password: String,
        @Value("\${spring.data.redis.database:0}") database: Int,
    ): RedisConnectionFactory {
        val config = RedisStandaloneConfiguration(host, port)
        config.database = database
        if (password.isNotBlank()) {
            config.setPassword(password)
        }
        return LettuceConnectionFactory(config)
    }

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

    @Bean
    fun defaultRedisTimeout(): Duration = Duration.ofSeconds(3)
}
