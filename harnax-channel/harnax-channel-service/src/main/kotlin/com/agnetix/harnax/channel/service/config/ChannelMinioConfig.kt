package com.agnetix.harnax.channel.service.config

import io.minio.GetObjectArgs
import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * MinIO configuration for channel-service.
 *
 * Used to read output files directly from MinIO (internal network)
 * for delivering files to channel users (WeChat, Feishu, etc.).
 */
@Configuration
@ConditionalOnProperty(prefix = "minio", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ChannelMinioProperties::class)
class ChannelMinioConfig {

    @Bean
    fun channelMinioClient(minioProperties: ChannelMinioProperties): MinioClient = MinioClient.builder()
        .endpoint(minioProperties.endpoint)
        .credentials(minioProperties.accessKey, minioProperties.secretKey)
        .build()

    @Bean
    fun minioFileContentResolver(minioClient: MinioClient, minioProperties: ChannelMinioProperties): MinioFileContentResolver = MinioFileContentResolver(minioClient, minioProperties)
}

/**
 * MinIO configuration properties for channel-service.
 */
@ConfigurationProperties(prefix = "minio")
data class ChannelMinioProperties(
    val enabled: Boolean = true,
    val endpoint: String = "http://localhost:9000",
    val accessKey: String = "minioadmin",
    val secretKey: String = "minioadmin",
    val outputBucket: String = "harnax-output",
)

/**
 * MinIO-backed file content resolver for channel file delivery.
 *
 * Reads file bytes directly from MinIO using the objectKey,
 * avoiding the need to go through admin's JWT-protected HTTP endpoint.
 */
class MinioFileContentResolver(
    private val minioClient: MinioClient,
    private val minioProperties: ChannelMinioProperties,
) : com.agnetix.harnax.channel.sdk.service.FileContentResolver {

    private val log = LoggerFactory.getLogger(MinioFileContentResolver::class.java)

    override fun resolve(attachment: com.agnetix.harnax.agent.protocol.FileAttachment): ByteArray? {
        if (attachment.objectKey.isBlank()) {
            log.debug("[fileResolver] No objectKey for file '{}', will fallback to URL", attachment.fileName)
            return null
        }
        // Security: reject object keys containing path traversal sequences
        if (attachment.objectKey.contains("..") || attachment.objectKey.startsWith("/")) {
            log.warn("[fileResolver] Suspicious objectKey rejected: '{}'", attachment.objectKey)
            return null
        }
        return try {
            minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(minioProperties.outputBucket)
                    .`object`(attachment.objectKey)
                    .build(),
            ).use { it.readBytes() }
        } catch (e: Exception) {
            log.warn("[fileResolver] Failed to read '{}' from MinIO: {}", attachment.objectKey, e.message)
            null
        }
    }
}
