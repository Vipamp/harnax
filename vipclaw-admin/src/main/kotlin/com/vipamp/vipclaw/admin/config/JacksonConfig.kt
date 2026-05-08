package com.vipamp.vipclaw.admin.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * Jackson JSON 序列化配置
 *
 * 配置 Kotlin 支持,解决 isXXX 字段的 getter 方法识别问题
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper = Jackson2ObjectMapperBuilder.json()
        // 注册 Kotlin 模块 - 解决 isXXX 字段序列化问题
        .modulesToInstall(KotlinModule.Builder().build())
        // 注册 Java 8 时间模块
        .modulesToInstall(JavaTimeModule())
        // 序列化配置
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()
}
