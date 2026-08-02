package com.agnetix.harnax.harness.config

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient

/**
 * MinIO connection configuration.
 *
 * @param endpoint MinIO server endpoint, e.g. "http://minio:9000"
 * @param accessKey access key (S3 compatible)
 * @param secretKey secret key (S3 compatible)
 * @param snapshotBucket bucket for sandbox workspace snapshot tar archives
 * @param storeBucket bucket for distributed KV store (RemoteFilesystem)
 * @param outputBucket bucket for agent output files (channel file delivery)
 * @param snapshotPrefix key prefix for snapshot objects
 * @param storePrefix key prefix for store objects
 */
data class MinioConfig(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val snapshotBucket: String = "harnax-snapshots",
    val storeBucket: String = "harnax-store",
    val outputBucket: String = "harnax-output",
    val snapshotPrefix: String = "snapshots/",
    val storePrefix: String = "store/",
) {
    /**
     * Creates a [MinioClient] from this configuration.
     */
    fun createMinioClient(): MinioClient = MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build()

    /**
     * Ensures the configured buckets exist, creating them if necessary.
     * Call once at application startup.
     */
    fun ensureBuckets(minioClient: MinioClient = createMinioClient()) {
        ensureBucket(minioClient, snapshotBucket)
        ensureBucket(minioClient, storeBucket)
        ensureBucket(minioClient, outputBucket)
    }

    private fun ensureBucket(client: MinioClient, bucket: String) {
        val exists = client.bucketExists(
            BucketExistsArgs.builder().bucket(bucket).build(),
        )
        if (!exists) {
            client.makeBucket(
                MakeBucketArgs.builder().bucket(bucket).build(),
            )
        }
    }
}
