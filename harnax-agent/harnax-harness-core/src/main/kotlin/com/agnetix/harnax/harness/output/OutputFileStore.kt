package com.agnetix.harnax.harness.output

import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Persists output files to MinIO and generates stable download URLs.
 *
 * Storage structure: `harnax-output/{sessionType}/{sessionId}/{fileId}`
 * - sessionType: "web" | "task" | "channel"
 *
 * Download URLs are served via admin service proxy with permission checking.
 */
interface OutputFileStore {
    /**
     * Persist file bytes to MinIO and return a stable download URL.
     *
     * @param sessionId  Session ID (prefix determines session type: chn-=channel, task-=task, else web)
     * @param fileName   Original file name
     * @param data       File bytes
     * @param mimeType   MIME type
     * @return Stored file info with stable download URL
     */
    fun persist(sessionId: String, fileName: String, data: ByteArray, mimeType: String): StoredFile

    /**
     * Retrieve file bytes from MinIO by object key.
     *
     * @param objectKey Full object key in the bucket
     * @return File bytes, or null if not found
     */
    fun retrieve(objectKey: String): ByteArray?
}

/**
 * Result of persisting a file to storage.
 *
 * @property fileId    Unique file identifier
 * @property url       Stable download URL (via admin proxy, for WebUI)
 * @property objectKey MinIO object key (for internal service direct access)
 */
data class StoredFile(
    val fileId: String,
    val url: String,
    val objectKey: String,
)

/**
 * MinIO-backed implementation of [OutputFileStore].
 *
 * @param minioClient   MinIO client instance
 * @param bucketName    Target bucket name (default: "harnax-output")
 * @param adminBaseUrl  Base URL for admin service (used to construct download URLs)
 */
class MinioOutputFileStore(
    private val minioClient: MinioClient,
    private val bucketName: String = "harnax-output",
    private val adminBaseUrl: String,
) : OutputFileStore {

    private val log = LoggerFactory.getLogger(MinioOutputFileStore::class.java)

    override fun persist(sessionId: String, fileName: String, data: ByteArray, mimeType: String): StoredFile {
        val fileId = UUID.randomUUID().toString()

        // Directory structure: {sessionType}/{sessionId}/{fileId}
        val sessionType = resolveSessionType(sessionId)
        val objectKey = "$sessionType/$sessionId/$fileId"

        // Upload file bytes
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .stream(data.inputStream(), data.size.toLong(), -1L)
                .contentType(mimeType)
                .build(),
        )

        // Upload metadata JSON
        val meta = buildString {
            append("{")
            append("\"fileName\":\"${escapeJson(fileName)}\",")
            append("\"mimeType\":\"$mimeType\",")
            append("\"size\":${data.size},")
            append("\"uploadTime\":${System.currentTimeMillis()}")
            append("}")
        }
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`("$objectKey.meta")
                .stream(meta.byteInputStream(), meta.length.toLong(), -1L)
                .contentType("application/json")
                .build(),
        )

        // Generate stable download URL via admin proxy
        val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
        val downloadPath = "/api/output-files/$sessionType/$sessionId/$fileId?name=$encodedName"
        val url = "$adminBaseUrl$downloadPath"

        log.info("[outputStore] Persisted file '{}' ({} bytes) to MinIO: {}/{}", fileName, data.size, bucketName, objectKey)
        return StoredFile(fileId = fileId, url = url, objectKey = objectKey)
    }

    override fun retrieve(objectKey: String): ByteArray? = try {
        minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .build(),
        ).use { it.readBytes() }
    } catch (e: Exception) {
        log.warn("[outputStore] Failed to retrieve object '{}': {}", objectKey, e.message)
        null
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Resolve session type from session ID prefix.
     * - "task-*" → task
     * - otherwise → web
     */
    private fun resolveSessionType(sessionId: String): String = when {
        sessionId.startsWith("task-") -> "task"
        else -> "web"
    }
}
