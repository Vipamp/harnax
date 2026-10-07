package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.harness.minio.MinioBaseStore
import io.agentscope.core.agent.RuntimeContext
import io.minio.BucketExistsArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * A conversation's id is a path segment of its owner's memory key, so it must never be able to move memory
 * outside that owner (design 11.2, acceptance 4).
 *
 * [com.agnetix.harnax.harness.HarnessAgentLauncher] takes the session id as an argument and the bucket
 * appends it raw — `agents/<agentId>/sessions/<sessionId>` — which is what makes an id of `../../someone/else`
 * worth measuring rather than arguing about. Two shapes matter, and they are measured against the server that
 * actually stores the objects, because whether a `..` is literal or resolved is that server's decision:
 * an id that nests deeper has to stay under the agent so the whole-agent reclamation takes it with everything
 * else, and an id that walks up must never leave an object behind outside it. The first is asserted by
 * reading the stored keys back; the second is asserted on the listing rather than on the exception, so a
 * storage that starts resolving traversal goes red instead of passing on a refusal it no longer makes.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemorySessionKeyShapeTest {

    companion object {
        private const val BUCKET = "harnax-store"
        private const val PREFIX = "store/"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"
        private const val TENANT = 4L
        private const val OWNER = "1"
        private const val NESTING_AGENT = "Nesting"
        private const val TRAVERSAL_AGENT = "Traversal"

        /** Ids that add path segments below the session rather than taking any away. */
        private val NESTING_IDS = listOf("sess-a/b", "sess-..%2f..%2fevil")

        /** Ids that ask the bucket to climb out of the agent segment, one of them far enough for another user. */
        private val TRAVERSAL_IDS = listOf(
            "../evil",
            "../../evil",
            "sess-1/../..",
            "../../../../users/9999/agents",
            "../../../../../../etc",
        )

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
    }

    private val log = LoggerFactory.getLogger(MemorySessionKeyShapeTest::class.java)

    private lateinit var client: MinioClient
    private lateinit var store: MinioBaseStore

    @BeforeAll
    fun createBucket() {
        client = MinioClient.builder()
            .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
            .credentials(ACCESS_KEY, SECRET_KEY)
            .build()
        var lastError: Exception? = null
        repeat(20) {
            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build())
                }
                store = MinioBaseStore(client, BUCKET, PREFIX)
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("MinIO did not become usable within 10 s", lastError)
    }

    private fun agentPrefix(agentId: String): String = "${PREFIX}tenants/$TENANT/users/$OWNER/agents/$agentId/"

    /** Every object key of the bucket, read straight off the server rather than through the store's own prefix. */
    private fun keys(): Set<String> = client
        .listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(PREFIX).recursive(true).build())
        .map { it.get().objectName() }
        .toSet()

    /** Both layers of one conversation's bucket, the way a turn's flush writes them. */
    private fun writeBothLayers(
        agentId: String,
        sessionId: String,
        marker: String,
    ) {
        val routes = MemoryDomain(store, TENANT, OWNER, agentId, true).routes(sessionId)
        val rc = RuntimeContext.builder().sessionId(sessionId).build()
        routes.getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc, MemoryFilesystemRoutes.CURATED_ITEM_KEY, "curated draft of $sessionId")
        routes.getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc, "/$marker.md", "ledger of $sessionId")
    }

    @Test
    fun `a session id that nests deeper still writes inside its own agent`() {
        NESTING_IDS.forEachIndexed { i, sessionId ->
            val before = keys()
            writeBothLayers(NESTING_AGENT, sessionId, "nest$i")
            val added = keys() - before

            assertEquals(
                setOf(
                    "${agentPrefix(NESTING_AGENT)}sessions/$sessionId/root/MEMORY.md",
                    "${agentPrefix(NESTING_AGENT)}sessions/$sessionId/memory/nest$i.md",
                ),
                added,
                "a deeper session id has to nest, not replace, the session segment",
            )
        }
    }

    @Test
    fun `a session id that walks up never leaves memory outside its own agent`() {
        // The control proves the listing sees this agent's objects at all, so an empty diff below is a refusal
        // rather than a probe that stopped looking.
        val controlBefore = keys()
        writeBothLayers(TRAVERSAL_AGENT, "sess-control", "control")
        assertEquals(2, (keys() - controlBefore).size, "the control conversation has to reach the bucket")

        TRAVERSAL_IDS.forEachIndexed { i, sessionId ->
            val before = keys()
            val failure = runCatching { writeBothLayers(TRAVERSAL_AGENT, sessionId, "walk$i") }.exceptionOrNull()
            val outside = (keys() - before).filter { !it.startsWith(agentPrefix(TRAVERSAL_AGENT)) }

            log.info(
                "session id <{}> left {} object(s) outside its agent, refused as: {}",
                sessionId,
                outside.size,
                failure?.let { f -> "${f::class.simpleName}: ${f.message} / cause ${f.cause?.let { c -> "${c::class.simpleName}: ${c.message}" }}" },
            )
            assertTrue(
                outside.isEmpty(),
                "session id <$sessionId> put ${outside.size} object(s) outside ${agentPrefix(TRAVERSAL_AGENT)}: $outside",
            )
        }
    }
}
