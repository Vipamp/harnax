package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
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
