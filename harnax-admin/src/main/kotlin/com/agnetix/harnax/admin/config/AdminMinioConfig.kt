package com.agnetix.harnax.admin.config

import io.minio.MinioClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * MinIO configuration properties for admin service.
 * Used for output file download proxy.
 */
@ConfigurationProperties(prefix = "minio")
class AdminMinioProperties {
    var enabled: Boolean = false
    var endpoint: String = "http://localhost:9000"
    var accessKey: String = "minioadmin"
    var secretKey: String = "minioadmin"
    var outputBucket: String = "harnax-output"
}

/**
 * MinIO client configuration for admin service.
 * Only active when minio.enabled=true.
 */
@Configuration
@ConditionalOnProperty(prefix = "minio", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(AdminMinioProperties::class)
class AdminMinioConfig {

    @Bean
    fun adminMinioClient(props: AdminMinioProperties): MinioClient = MinioClient.builder()
        .endpoint(props.endpoint)
        .credentials(props.accessKey, props.secretKey)
        .build()
}
