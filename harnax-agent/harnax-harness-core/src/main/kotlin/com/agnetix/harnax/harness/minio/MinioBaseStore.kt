package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
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
        val objectKey = buildKey(namespace, key)
        return try {
            val stream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(objectKey)
                    .build(),
            )
            val json = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val wrapper = objectMapper.readValue(json, StoreWrapper::class.java)
            StoreItem(wrapper.key, wrapper.value)
        } catch (e: Exception) {
            log.debug("[minio-store] get() not found: key={}", objectKey)
            null
        }
    }

    override fun put(namespace: List<String>, key: String, value: Map<String, Any>) {
        val objectKey = buildKey(namespace, key)
        val wrapper = StoreWrapper(key, value)
        val json = objectMapper.writeValueAsString(wrapper)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                .contentType("application/json")
                .build(),
        )
        log.debug("[minio-store] put() key={}", objectKey)
    }

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

            try {
                val stream = minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(bucketName)
                        .`object`(objectKey)
                        .build(),
                )
                val json = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val wrapper = objectMapper.readValue(json, StoreWrapper::class.java)
                items.add(StoreItem(wrapper.key, wrapper.value))
            } catch (e: Exception) {
                log.warn("[minio-store] search() failed to read object {}: {}", objectKey, e.message)
            }
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
    )
}
