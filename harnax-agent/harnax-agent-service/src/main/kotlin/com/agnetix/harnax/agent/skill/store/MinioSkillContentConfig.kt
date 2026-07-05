package com.agnetix.harnax.agent.skill.store

import io.minio.MinioClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["skill.storage.type"], havingValue = "minio")
class MinioSkillContentConfig {

    @Bean
    fun skillMinioClient(
        @Value($$"${skill.storage.minio.endpoint:${harness.minio.endpoint:http://localhost:9000}}") endpoint: String,
        @Value($$"${skill.storage.minio.access-key:${harness.minio.access-key:minioadmin}}") accessKey: String,
        @Value($$"${skill.storage.minio.secret-key:${harness.minio.secret-key:minioadmin}}") secretKey: String,
    ): MinioClient = MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build()
}
