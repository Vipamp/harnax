package com.agnetix.harnax.admin.config

import io.minio.MinioClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * MinIO configuration properties for admin service.
 * Used for output file download proxy, the CLI package archive, and the store bucket long-term memory
 * is read out of.
 */
@ConfigurationProperties(prefix = "minio")
class AdminMinioProperties {
    var enabled: Boolean = false
    var endpoint: String = "http://localhost:9000"
    var accessKey: String = "minioadmin"
    var secretKey: String = "minioadmin"
    var outputBucket: String = "harnax-output"

    // The bucket the agent runtime's distributed KV store writes into, and therefore the bucket long-term
    // memory lives in. Must match agent-service's `harness.minio.store-bucket`; admin never creates it, it
    // only reads and removes the caller's own keys inside it.
    var storeBucket: String = "harnax-store"

    // The prefix MinioBaseStore puts in front of every store key. Must match `harness.minio.store-prefix`;
    // a different value here reads a different bucket area and finds an empty memory.
    var storePrefix: String = "store/"
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
