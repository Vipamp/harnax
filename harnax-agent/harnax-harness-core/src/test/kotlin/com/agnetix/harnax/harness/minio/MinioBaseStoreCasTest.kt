package com.agnetix.harnax.harness.minio

import com.sun.net.httpserver.HttpServer
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.minio.BucketExistsArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The compare-and-swap promise [BaseStore.putIfVersion] makes to [io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate],
 * measured against a real S3-compatible server. A mock would prove nothing here: whether two writers can both
 * claim the same slot is decided by the server, not by this class.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioBaseStoreCasTest {

    companion object {
        private const val BUCKET = "harnax-store"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"
        private val NAMESPACE = listOf("coordination", "periodic")

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
    }

    private lateinit var client: MinioClient

    private fun newClient(): MinioClient = MinioClient.builder()
        .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
        .credentials(ACCESS_KEY, SECRET_KEY)
        .build()

    @BeforeAll
    fun createBucket() {
        client = newClient()
        var lastError: Exception? = null
        repeat(20) {
            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build())
                }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("MinIO did not become usable within 10 s", lastError)
    }

    @Test
    fun `claims an absent slot`() {
        val store = MinioBaseStore(client, BUCKET)
        assertTrue(store.putIfVersion(NAMESPACE, "memory-flush:agent-1", mapOf("lastClaimAt" to 1L), 0L))
        assertNotNull(store.get(NAMESPACE, "memory-flush:agent-1"))
    }

    @Test
    fun `claims a held slot at the version it was read at`() {
        val store = MinioBaseStore(client, BUCKET)
        val key = "memory-flush:agent-2"
        assertTrue(store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 1L), 0L))
        val held = store.get(NAMESPACE, key)
        assertNotNull(held)
        assertTrue(store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 2L), held!!.version))
    }

    @Test
    fun `leaves a slot written by put claimable at its version`() {
        val store = MinioBaseStore(client, BUCKET)
        val key = "memory-flush:agent-3"
        store.put(NAMESPACE, key, mapOf("lastClaimAt" to 1L))
        val held = store.get(NAMESPACE, key)
        assertNotNull(held)
        assertTrue(held!!.version > 0L, "a record written by put() must not look unclaimed")
        assertTrue(store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 2L), held.version))
    }

    @Test
    fun `loses a claim made against a stale version`() {
        val store = MinioBaseStore(client, BUCKET)
        val key = "memory-flush:agent-4"
        assertTrue(store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 1L), 0L))
        val held = store.get(NAMESPACE, key)
        assertNotNull(held)
        assertTrue(store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 2L), held!!.version))
        assertFalse(store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 3L), held.version))
        val after = store.get(NAMESPACE, key)
        assertNotNull(after)
        assertEquals(2L, (after!!.value["lastClaimAt"] as Number).toLong())
    }

    @Test
    fun `awards a slot to one writer of many`() {
        val key = "memory-flush:agent-5"
        val writers = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val winners = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()
        repeat(writers) {
            thread {
                // One client per writer: the gate this record serves decides claims across nodes, so the
                // race has to look like separate processes rather than threads sharing a connection pool.
                val store = MinioBaseStore(newClient(), BUCKET)
                try {
                    start.await()
                    if (store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 1L), 0L)) {
                        winners.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(120, TimeUnit.SECONDS), "writers did not finish")
        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(1, winners.get())
    }

    @Test
    fun `reports the same version through search as through get`() {
        val store = MinioBaseStore(client, BUCKET)
        val key = "memory-flush:agent-6"
        store.put(NAMESPACE, key, mapOf("lastClaimAt" to 1L))
        val listed = store.search(NAMESPACE, 100, 0).first { it.key == key }
        val direct = store.get(NAMESPACE, key)
        assertNotNull(direct)
        assertEquals(direct!!.version, listed.version)
        assertTrue(store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 2L), listed.version))
    }

    @Test
    fun `lets the upstream gate claim once per gap`() {
        val store = MinioBaseStore(client, BUCKET)
        val gate = StoreBackedPeriodicGate(store)
        val slot = "memory-flush:agent-7"
        assertTrue(gate.tryClaim(slot, Duration.ofMinutes(5)))
        assertFalse(gate.tryClaim(slot, Duration.ofMinutes(5)))
    }

    @Test
    fun `keeps the upstream gate silent when the store cannot compare-and-swap`() {
        val gate = StoreBackedPeriodicGate(NoCasStore())
        assertFalse(gate.tryClaim("memory-flush:agent-8", Duration.ofMinutes(5)))
        assertFalse(gate.tryClaim("memory-flush:agent-8", Duration.ofMinutes(5)))
    }

    @Test
    fun `counts a record written before versions existed as a held slot`() {
        val store = MinioBaseStore(client, BUCKET)
        val key = "memory-flush:agent-9"
        val objectKey = objectKeyWrittenBy(store, NAMESPACE, key)
        // Re-save the same object the way an envelope without a version field looks — an object written
        // before this class embedded versions, or seeded by hand.
        saveRaw(objectKey, """{"key":"$key","value":{"lastClaimAt":1}}""")

        val held = store.get(NAMESPACE, key)
        assertNotNull(held)
        assertTrue(held!!.version >= 1L, "a live record must not report the version of an absent slot")
        assertFalse(
            store.putIfVersion(NAMESPACE, key, mapOf("lastClaimAt" to 2L), 0L),
            "an existing record cannot be claimed as though it were never written",
        )
    }

    @Test
    fun `fails a read the server refused instead of answering that nothing is there`() {
        faultyStore { store ->
            // A read the store could not complete has to surface: RemoteFilesystem turns a null into
            // "File not found", and the consolidator then rewrites MEMORY.md from an empty base.
            val failure = assertThrows(RuntimeException::class.java) {
                store.get(NAMESPACE, "memory-flush:agent-10")
            }
            assertTrue(
                failure.message.orEmpty().contains("memory-flush:agent-10"),
                "the failure has to name the object it could not read, got: ${failure.message}",
            )
        }
    }

    @Test
    fun `refuses a blind overwrite when it cannot read the version first`() {
        faultyStore { store ->
            // put() works out the next version by reading the current one; writing anyway after a failed
            // read would reset a held slot to its first version.
            assertThrows(RuntimeException::class.java) {
                store.put(NAMESPACE, "memory-flush:agent-11", mapOf("lastClaimAt" to 1L))
            }
        }
    }

    /** The object key the store itself chose for [key], read back from a listing rather than rebuilt here. */
    private fun objectKeyWrittenBy(
        store: MinioBaseStore,
        namespace: List<String>,
        key: String,
    ): String {
        store.put(namespace, key, mapOf("lastClaimAt" to 0L))
        val prefix = "store/" + namespace.joinToString("/") + "/"
        val found = client
            .listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix).recursive(true).build())
            .mapNotNull { result -> runCatching { result.get().objectName() }.getOrNull() }
            .firstOrNull { it.removePrefix(prefix) == key }
        return requireNotNull(found) { "the store did not write '$key' where a listing can find it" }
    }

    /** Writes [json] to [objectKey] straight through the client, bypassing the store's own envelope. */
    private fun saveRaw(
        objectKey: String,
        json: String,
    ) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        client.putObject(
            PutObjectArgs.builder()
                .bucket(BUCKET)
                .`object`(objectKey)
                .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                .contentType("application/json")
                .build(),
        )
    }

    /**
     * Runs [test] against a store whose server answers every request with a server fault. The fault comes
     * from a real HTTP exchange because whether a failed read is distinguishable from a missing object is
     * decided by the response, not by this class.
     */
    private fun <T> faultyStore(test: (MinioBaseStore) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body =
                (
                    """<?xml version="1.0" encoding="UTF-8"?><Error><Code>InternalError</Code>""" +
                        """<Message>fault</Message></Error>"""
                    ).toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = MinioBaseStore(
                MinioClient.builder()
                    .endpoint("http://127.0.0.1:${server.address.port}")
                    .credentials(ACCESS_KEY, SECRET_KEY)
                    // Pinned so the request that faults is the object read, not a region lookup first.
                    .region("us-east-1")
                    .build(),
                BUCKET,
            )
            return test(store)
        } finally {
            server.stop(0)
        }
    }

    /** A store that answers every claim the way an unversioned backend does: the write never lands. */
    private class NoCasStore : BaseStore {
        private val items = ConcurrentHashMap<String, Map<String, Any>>()

        override fun get(namespace: List<String>, key: String): StoreItem? = items[key]?.let { StoreItem(key, it) }

        override fun put(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
        ) {
            items[key] = value
        }

        override fun search(
            namespace: List<String>,
            limit: Int,
            offset: Int,
        ): List<StoreItem> = items.map { (key, value) -> StoreItem(key, value) }

        override fun delete(namespace: List<String>, key: String) {
            items.remove(key)
        }
    }
}
