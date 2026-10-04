package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.errors.ErrorResponseException
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * [BaseStore] backed by MinIO (S3 compatible).
 *
 * Each KV pair is stored as a JSON object in MinIO. The object key is built from the namespace
 * and the item key, e.g. namespace=`["agents","myAgent","sessions","sess-123"]`,
 * key=`"/MEMORY.md"` → object key=`store/agents/myAgent/sessions/sess-123/MEMORY.md`.
 *
 * The JSON content embeds the item key so that [search] can reconstruct [StoreItem] records.
 *
 * @param minioClient initialised MinIO client
 * @param bucketName bucket for store objects
 * @param keyPrefix global key prefix (e.g. "store/")
 * @param objectMapper Jackson ObjectMapper for JSON serialisation
 */
class MinioBaseStore(
    private val minioClient: MinioClient,
    private val bucketName: String,
    private val keyPrefix: String = "store/",
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : BaseStore {

    private val log = LoggerFactory.getLogger(MinioBaseStore::class.java)

    override fun get(namespace: List<String>, key: String): StoreItem? {
        val record = load(buildKey(namespace, key)) ?: return null
        return StoreItem(record.wrapper.key, record.wrapper.value, effectiveVersion(record.wrapper))
    }

    override fun put(namespace: List<String>, key: String, value: Map<String, Any>) {
        val objectKey = buildKey(namespace, key)
        val version = effectiveVersion(load(objectKey)?.wrapper) + 1
        write(objectKey, key, value, version)
    }

    override fun putIfVersion(
        namespace: List<String>,
        key: String,
        value: Map<String, Any>,
        expectedVersion: Long,
    ): Boolean {
        val objectKey = buildKey(namespace, key)
        val current = load(objectKey)
        val version = effectiveVersion(current?.wrapper)
        if (version != expectedVersion) return false
        val precondition = when {
            current == null -> mapOf("If-None-Match" to "*")
            current.etag != null -> mapOf("If-Match" to current.etag)
            else -> return false
        }
        return write(objectKey, key, value, version + 1, precondition)
    }

    /**
     * The version a record answers with: 0 when there is no record, at least 1 when there is one.
     *
     * [StoreBackedPeriodicGate] reads 0 as "this slot was never claimed", so a record that predates the
     * embedded version — or one seeded by hand — must not answer with it, or an existing slot becomes
     * claimable as though it were empty.
     */
    private fun effectiveVersion(wrapper: StoreWrapper?): Long = wrapper?.version?.coerceAtLeast(1L) ?: 0L

    /**
     * Reads one object, answering null only when the object is genuinely not there.
     *
     * Anything else the server or the client complains about is thrown, and thrown as a
     * [RuntimeException]: the callers upstream — the periodic gate and the memory middleware — guard
     * exactly that type, while minio's own exceptions are checked and would sail past their catch. A
     * swallowed read here is worse than a failed one, because
     * [io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem] answers a null with "file not
     * found" and the consolidator then rewrites `MEMORY.md` from an empty base.
     */
    private fun load(objectKey: String): Record? = try {
        minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .build(),
        ).use { response ->
            val json = response.bufferedReader(StandardCharsets.UTF_8).readText()
            Record(objectMapper.readValue(json, StoreWrapper::class.java), response.headers()["ETag"])
        }
    } catch (e: ErrorResponseException) {
        if (isMissingObject(e)) {
            log.debug("[minio-store] object not found: key={}", objectKey)
            null
        } else {
            throw unreadable(objectKey, e)
        }
    } catch (e: Exception) {
        throw unreadable(objectKey, e)
    }

    /** Whether this 404 means the object itself is absent, rather than the bucket or the route to it. */
    private fun isMissingObject(e: ErrorResponseException): Boolean = e.response().code == 404 && e.errorResponse().code() == "NoSuchKey"

    private fun unreadable(
        objectKey: String,
        cause: Exception,
    ): RuntimeException = IllegalStateException("Memory store object $objectKey could not be read", cause)

    private fun write(
        objectKey: String,
        key: String,
        value: Map<String, Any>,
        version: Long,
        precondition: Map<String, String> = emptyMap(),
    ): Boolean {
        val json = objectMapper.writeValueAsString(StoreWrapper(key, value, version))
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val builder = PutObjectArgs.builder()
            .bucket(bucketName)
            .`object`(objectKey)
            .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
            .contentType("application/json")
        if (precondition.isNotEmpty()) {
            builder.extraHeaders(precondition)
        }
        return try {
            minioClient.putObject(builder.build())
            log.debug("[minio-store] write() key={} version={}", objectKey, version)
            true
        } catch (e: ErrorResponseException) {
            if (e.response().code == 412) {
                log.debug("[minio-store] write() precondition failed: key={}", objectKey)
                false
            } else {
                throw IllegalStateException("Memory store object $objectKey could not be written", e)
            }
        }
    }

    private class Record(
        val wrapper: StoreWrapper,
        val etag: String?,
    )

    override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> {
        val prefix = buildPrefix(namespace)
        val items = mutableListOf<StoreItem>()
        var skipped = 0

        val results = minioClient.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .recursive(true)
                .build(),
        )

        for (result in results) {
            val objectKey = result.get().objectName()
            // Extract the item key from the object key: strip keyPrefix + namespace prefix
            val itemKey = objectKey.removePrefix(prefix)
            if (itemKey.isBlank()) continue

            if (skipped < offset) {
                skipped++
                continue
            }
            if (items.size >= limit) break

            // The same read path get() uses, so a listing cannot answer a different version for an object
            // than the object itself does, and a fault is not silently a missing file.
            val record = load(objectKey) ?: continue
            items.add(StoreItem(record.wrapper.key, record.wrapper.value, effectiveVersion(record.wrapper)))
        }

        return items
    }

    override fun delete(namespace: List<String>, key: String) {
        val objectKey = buildKey(namespace, key)
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(objectKey)
                    .build(),
            )
            log.debug("[minio-store] delete() key={}", objectKey)
        } catch (e: Exception) {
            log.warn("[minio-store] delete() failed for {}: {}", objectKey, e.message)
        }
    }

    /**
     * Builds the full MinIO object key from namespace and item key.
     *
     * Example: namespace=`["agents","myAgent"]`, key=`"/MEMORY.md"`
     * → `{keyPrefix}agents/myAgent/MEMORY.md`
     */
    private fun buildKey(namespace: List<String>, key: String): String {
        val nsPart = namespace.joinToString("/")
        val cleanKey = key.removePrefix("/")
        return if (nsPart.isEmpty()) {
            "$keyPrefix$cleanKey"
        } else {
            "$keyPrefix$nsPart/$cleanKey"
        }
    }

    /**
     * Builds the MinIO prefix for listing objects under a namespace.
     * Always ends with "/" to scope the listing to the namespace.
     */
    private fun buildPrefix(namespace: List<String>): String {
        val nsPart = namespace.joinToString("/")
        return if (nsPart.isEmpty()) {
            keyPrefix
        } else {
            "$keyPrefix$nsPart/"
        }
    }

    /**
     * Internal JSON wrapper that embeds both the item key and its value map.
     */
    data class StoreWrapper(
        val key: String,
        val value: Map<String, Any>,
        val version: Long = 0L,
    )
}
