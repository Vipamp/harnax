package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient
import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * [RemoteSnapshotClient] backed by MinIO (S3 compatible).
 *
 * Each snapshot is stored as a tar archive at `{keyPrefix}{snapshotId}.tar` inside [bucketName].
 * Used with [io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec] so that sandbox
 * workspace archives are automatically uploaded on stop and downloaded on resume.
 *
 * @param minioClient initialised MinIO client
 * @param bucketName bucket for snapshot objects
 * @param keyPrefix object key prefix (e.g. "snapshots/")
 */
class MinioSnapshotClient(
    private val minioClient: MinioClient,
    private val bucketName: String,
    private val keyPrefix: String = "snapshots/",
) : RemoteSnapshotClient {

    private val log = LoggerFactory.getLogger(MinioSnapshotClient::class.java)

    override fun upload(snapshotId: String, data: InputStream) {
        val key = objectKey(snapshotId)
        log.debug("[minio-snapshot] Uploading snapshot: bucket={}, key={}", bucketName, key)
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`(key)
                .stream(data, -1, PART_SIZE)
                .contentType("application/x-tar")
                .build(),
        )
        log.info("[minio-snapshot] Snapshot uploaded: {}", key)
    }

    override fun download(snapshotId: String): InputStream {
        val key = objectKey(snapshotId)
        log.debug("[minio-snapshot] Downloading snapshot: bucket={}, key={}", bucketName, key)
        return minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(bucketName)
                .`object`(key)
                .build(),
        )
    }

    override fun exists(snapshotId: String): Boolean {
        val key = objectKey(snapshotId)
        return try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(key)
                    .build(),
            )
            true
        } catch (e: Exception) {
            log.debug("[minio-snapshot] Snapshot not found: {}", key)
            false
        }
    }

    /**
     * Deletes a snapshot from MinIO. Not part of [RemoteSnapshotClient] but useful for
     * [com.agnetix.harnax.harness.HarnessAgentLauncher.clearSession].
     */
    fun delete(snapshotId: String) {
        val key = objectKey(snapshotId)
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(key)
                    .build(),
            )
            log.info("[minio-snapshot] Snapshot deleted: {}", key)
        } catch (e: Exception) {
            log.warn("[minio-snapshot] Failed to delete snapshot {}: {}", key, e.message)
        }
    }

    private fun objectKey(snapshotId: String): String {
        require(snapshotId.isNotBlank()) { "snapshotId must not be blank" }
        return "$keyPrefix$snapshotId.tar"
    }

    companion object {
        /** 10 MB part size for multipart upload. */
        private const val PART_SIZE: Long = 10 * 1024 * 1024
    }
}
