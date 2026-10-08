package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.admin.dto.MemoryDraftSource
import com.agnetix.harnax.admin.service.impl.MemoryStoreGateway
import com.agnetix.harnax.admin.util.MemoryRecordParser
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.ObjectProvider
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An approval's two writes against a MinIO that answers them.
 *
 * [com.agnetix.harnax.admin.service.impl.MemoryStoreGatewayTest] pins which keys the gateway names and which
 * headers it sends, and a stub can only ever answer what it was told to answer. Four promises an approval
 * rests on are decided by the server instead, and this class is the only place that measures them:
 *
 * 1. **A conditional write is refused rather than ignored.** Two conversations of one agent merge against the
 *    same base version; whoever approves second must lose. That is carried by `If-Match` / `If-None-Match: *`,
 *    and an S3-compatible gateway is free to accept those headers and act on them anyway — which would let
 *    both approvals write and show the owner one memory they never agreed to.
 *    `MinioBaseStoreCasTest` shows this for the coordination records the runtime writes; this shows it for
 *    the key the approval writes.
 * 2. **The bytes an approval leaves are readable by the writer's own reader.** `MemoryRecordParser` is admin's
 *    half of an envelope contract whose other half lives in `MinioBaseStore`, and what the round trip through a
 *    real server can falsify is the encoding and the document, not just the field names.
 * 3. **A missing object is answered as missing.** The clear path distinguishes "already gone" from "the store
 *    refused" by reading the response code and error code off a real 404; a bucket that spelled that differently
 *    would turn every re-approval into a 503.
 * 4. **A delete removes.** An approval that counted a conversation's file as merged away while the bucket still
 *    holds it would propose the same text again forever.
 * 5. **A refusal leaves the client usable.** The server answers an unfulfilled conditional write by dropping the
 *    connection it came in on, and the next write that reuses it is lost — a second approval's memory that a
 *    reviewer did approve. Whether a refusal costs its own connection or somebody else's is a property of the
 *    exchange, not of this class, so it is measured here and guarded by a header the gateway's own test pins.
 *    What is not asserted, by contrast, is that a refusal *without* that header loses the write after it: that
 *    was measured once on this image, and pinning it would pin the server's defect rather than the promise.
 *
 * Deliberately not here: the HTTP layer and the owner's identity (that is
 * [MemoryOwnerBucketMinioIT], which boots the application), and every branch a healthy server cannot be made
 * to produce — an object answering no ETag, a body that is not an envelope, a delete that silently fails.
 * Those are stubbed where they belong, in the gateway's own test.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MemoryStoreGateway - an approval against a real object store")
class MemoryApprovalStoreIT {

    companion object {
        /** The bucket the runtime writes memory into, and `minio.store-bucket` on this side. */
        private const val STORE_BUCKET = "harnax-store"

        private const val ACCESS_KEY = "minioadmin"

        private const val SECRET_KEY = "minioadmin"

        /** The owner half of every key here: a tenant id and a user id, which is what the key is built from. */
        private const val TENANT = 4L

        private const val OWNER = "7"

        private const val PEER_OWNER = "8"

        private const val SESSION = "sess-1"

        private const val OWNER_PREFIX = "store/tenants/4/users/7/"

        private const val PEER_PREFIX = "store/tenants/4/users/8/"

        /** What the writer's envelope looks like, in the field order `MinioBaseStore` serialises it in. */
        private const val STAMP = "2026-10-05T13:20:15.148570Z"

        private const val FIRST_MERGE = "# Memory\n- the user likes terse answers"

        private const val SECOND_MERGE = "# Memory\n- the user likes terse answers\n- no trailing summaries"

        /** A third candidate, so a test can tell its own write apart from the two that came before it. */
        private const val THIRD_MERGE =
            "# Memory\n- the user likes terse answers\n- no trailing summaries\n- English comments"

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
    }

    private val objectMapper = ObjectMapper()

    /** The store as this class seeds it, through its own client rather than through the gateway. */
    private val store: MinioClient by lazy {
        MinioClient.builder()
            .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
            .credentials(ACCESS_KEY, SECRET_KEY)
            .build()
    }

    private val gateway: MemoryStoreGateway by lazy {
        MemoryStoreGateway(providerOf(store), providerOf(properties), objectMapper)
    }

    private val properties = AdminMinioProperties().apply {
        enabled = true
        storeBucket = STORE_BUCKET
        storePrefix = "store/"
    }

    @BeforeAll
    fun createBucket() {
        var lastError: Exception? = null
        repeat(20) {
            try {
                if (!store.bucketExists(BucketExistsArgs.builder().bucket(STORE_BUCKET).build())) {
                    store.makeBucket(MakeBucketArgs.builder().bucket(STORE_BUCKET).build())
                }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("MinIO did not become usable within 10 s", lastError)
    }

    private fun newClient(): MinioClient = MinioClient.builder()
        .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
        .credentials(ACCESS_KEY, SECRET_KEY)
        .build()

    /** One memory file's envelope, as the runtime would have written it: [content] at `value.content`. */
    private fun envelope(
        itemKey: String,
        content: String,
        version: Long = 1,
        createdAt: String = STAMP,
    ): String = "{\"key\":\"$itemKey\",\"value\":{\"created_at\":\"$createdAt\",\"encoding\":\"utf-8\"," +
        "\"modified_at\":\"$STAMP\",\"content\":${objectMapper.writeValueAsString(content)}},\"version\":$version}"

    private fun put(
        client: MinioClient,
        objectKey: String,
        body: String,
        precondition: Map<String, String>? = null,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val builder = PutObjectArgs.builder()
            .bucket(STORE_BUCKET)
            .`object`(objectKey)
            .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
            .contentType("application/json")
        precondition?.let { builder.extraHeaders(it) }
        client.putObject(builder.build())
    }

    /**
     * The headers of a conditional write, spelled the way [MemoryStoreGateway] spells them: the condition
     * itself and a connection the refusal is free to spend.
     */
    private fun conditional(condition: Pair<String, String>): Map<String, String> = mapOf(condition, "Connection" to "close")

    /** The object's body, or null when the bucket does not hold that key. */
    private fun bodyOf(
        client: MinioClient,
        objectKey: String,
    ): String? = try {
        client.getObject(GetObjectArgs.builder().bucket(STORE_BUCKET).`object`(objectKey).build())
            .use { it.readBytes().toString(StandardCharsets.UTF_8) }
    } catch (e: ErrorResponseException) {
        if (e.response().code == 404) null else throw e
    }

    private fun nodeOf(objectKey: String): JsonNode = objectMapper.readTree(bodyOf(store, objectKey))

    /** The server's own version tag of one object, which is what an `If-Match` write has to carry. */
    private fun etagOf(
        client: MinioClient,
        objectKey: String,
    ): String = client
        .statObject(StatObjectArgs.builder().bucket(STORE_BUCKET).`object`(objectKey).build())
        .etag()
        .let { if (it.startsWith("\"")) it else "\"$it\"" }

    private fun curatedKey(
        agent: String,
        ownerPrefix: String = OWNER_PREFIX,
    ): String = "${ownerPrefix}agents/$agent/root/MEMORY.md"

    private fun sessionKey(
        agent: String,
        route: String,
        item: String,
        sessionId: String = SESSION,
    ): String = "${OWNER_PREFIX}agents/$agent/sessions/$sessionId/$route/$item"

    /** Recursive, like the gateway: a non-recursive listing answers common prefixes instead of object names. */
    private fun keysUnder(prefix: String): List<String> = store
        .listObjects(ListObjectsArgs.builder().bucket(STORE_BUCKET).prefix(prefix).recursive(true).build())
        .asSequence()
        .map { it.get().objectName() }
        .toList()
        .sorted()

    @Test
    fun `the store refuses a conditional write whose precondition no longer holds`() {
        // Its own client because a refusal costs a connection (see the test after this one), and this class
        // issues several refusals in a row.
        val store = newClient()
        val key = curatedKey("EnforcementIt")
        put(store, key, envelope("/MEMORY.md", FIRST_MERGE), precondition = conditional("If-None-Match" to "*"))

        // A create is a create only while the object is absent — otherwise two approvals of a memory that did
        // not exist yet would both file their own layer and the second would silently win.
        assertEquals(
            412,
            assertThrows(ErrorResponseException::class.java) {
                put(store, key, envelope("/MEMORY.md", SECOND_MERGE), precondition = conditional("If-None-Match" to "*"))
            }.response().code,
        )

        val held = etagOf(store, key)
        assertEquals(
            412,
            assertThrows(ErrorResponseException::class.java) {
                put(
                    store,
                    key,
                    envelope("/MEMORY.md", SECOND_MERGE),
                    precondition = conditional("If-Match" to "\"00000000000000000000000000000000\""),
                )
            }.response().code,
            "a tag the object does not carry must not buy a write",
        )
        put(store, key, envelope("/MEMORY.md", SECOND_MERGE, version = 2), precondition = conditional("If-Match" to held))

        assertEquals(SECOND_MERGE, MemoryRecordParser.parse(bodyOf(store, key)!!, objectMapper).content)
        // The tag moves with the bytes, so a write that read the object before this one cannot follow it.
        assertEquals(
            412,
            assertThrows(ErrorResponseException::class.java) {
                put(store, key, envelope("/MEMORY.md", FIRST_MERGE, version = 3), precondition = conditional("If-Match" to held))
            }.response().code,
            "the object the read saw is not the object still there",
        )
        assertEquals(SECOND_MERGE, MemoryRecordParser.parse(bodyOf(store, key)!!, objectMapper).content)
    }

    @Test
    fun `a refused approval costs its own connection and not the next write`() {
        val store = newClient()
        val agent = "SequenceIt"
        val key = curatedKey(agent)
        put(store, key, envelope("/MEMORY.md", FIRST_MERGE))

        // The losing half of a race between two approvals of one base, conditioned the way the gateway
        // conditions a write — its `Connection: close` included, a header the gateway's own test pins and
        // which only a real exchange can show to be load-bearing.
        assertEquals(
            412,
            assertThrows(ErrorResponseException::class.java) {
                put(
                    store,
                    key,
                    envelope("/MEMORY.md", SECOND_MERGE, version = 2),
                    precondition = conditional("If-Match" to "\"00000000000000000000000000000000\""),
                )
            }.response().code,
        )
        assertEquals(FIRST_MERGE, MemoryRecordParser.parse(bodyOf(store, key)!!, objectMapper).content)

        // The next approval on the same client has to reach the bucket. Without that close, MinIO leaves the
        // refused request's connection in okhttp's pool and hands it to this write, which then dies in the
        // transport and loses a memory its reviewer did approve.
        val gateway = MemoryStoreGateway(providerOf(store), providerOf(properties), objectMapper)
        assertTrue(
            gateway.writeCuratedIfVersion(TENANT, OWNER, agent, 1L, THIRD_MERGE),
            "the write after a refusal has to reach the bucket, or a reviewer approved something that never landed",
        )

        val layer = gateway.readCuratedLayer(TENANT, OWNER, agent)
        assertEquals(THIRD_MERGE, layer.content)
        assertEquals(2L, layer.version, "only the refused write and this one have touched the layer")
    }

    @Test
    fun `an approval writes the layer where the runtime reads it`() {
        val absent = gateway.readCuratedLayer(TENANT, OWNER, "RoundTripIt")
        assertEquals("", absent.content)
        assertEquals(0L, absent.version, "no layer yet is the base a first candidate is filed against")

        assertTrue(gateway.writeCuratedIfVersion(TENANT, OWNER, "RoundTripIt", absent.version, FIRST_MERGE))

        val layer = gateway.readCuratedLayer(TENANT, OWNER, "RoundTripIt")
        assertEquals(FIRST_MERGE, layer.content)
        assertEquals(1L, layer.version)
        assertEquals(
            FIRST_MERGE,
            MemoryRecordParser.parse(bodyOf(store, curatedKey("RoundTripIt"))!!, objectMapper).content,
            "the bytes a conversation's next merge reads are the ones this write left, through the server",
        )
        assertEquals(listOf(curatedKey("RoundTripIt")), keysUnder("${OWNER_PREFIX}agents/RoundTripIt/"))
    }

    @Test
    fun `the second approval of one base leaves the layer as the first approval wrote it`() {
        assertTrue(gateway.writeCuratedIfVersion(TENANT, OWNER, "StaleIt", 0L, FIRST_MERGE))

        assertFalse(
            gateway.writeCuratedIfVersion(TENANT, OWNER, "StaleIt", 0L, SECOND_MERGE),
            "the candidate read a layer that has since been approved once must not overwrite it",
        )

        val node = nodeOf(curatedKey("StaleIt"))
        assertEquals(FIRST_MERGE, node.path("value").path("content").asText())
        assertEquals(1L, node.path("version").asLong(), "the loser did not even bump the version")
    }

    @Test
    fun `an approval that fits the bytes it read replaces them and keeps the layer's own creation stamp`() {
        // Seeded the way a conversation's earlier approval would have left it, one version behind what the
        // candidate read.
        put(store, curatedKey("ReplaceIt"), envelope("/MEMORY.md", FIRST_MERGE, createdAt = "2026-01-01T00:00:00Z"))

        assertTrue(gateway.writeCuratedIfVersion(TENANT, OWNER, "ReplaceIt", 1L, SECOND_MERGE))

        val node = nodeOf(curatedKey("ReplaceIt"))
        assertEquals(SECOND_MERGE, node.path("value").path("content").asText())
        assertEquals(2L, node.path("version").asLong())
        assertEquals(
            "2026-01-01T00:00:00Z",
            node.path("value").path("created_at").asText(),
            "a memory was created when the first approval created it, not when a later one amended it",
        )
    }

    @Test
    fun `a clear takes only the file that still holds the merged bytes out of the bucket`() {
        val draft = sessionKey("ClearIt", "root", "MEMORY.md")
        val ledger = sessionKey("ClearIt", "memory", "2026-10-05.md")
        put(store, draft, envelope("/MEMORY.md", "- recorded"))
        put(store, ledger, envelope("/2026-10-05.md", "- something a later turn appended after the merge read it"))

        val cleared = gateway.clearSessionSources(
            TENANT,
            OWNER,
            "ClearIt",
            SESSION,
            listOf(
                MemoryDraftSource("MEMORY.md", "- recorded"),
                MemoryDraftSource("memory/2026-10-05.md", "- recorded"),
            ),
        )

        assertEquals(1, cleared.cleared)
        assertEquals(1, cleared.kept)
        assertEquals(0, cleared.absent)
        assertNull(bodyOf(store, draft), "the delete has to have removed the object, not merely been answered OK")
        assertNotNull(bodyOf(store, ledger))
    }

    @Test
    fun `a file that is not in the bucket is answered absent rather than as a store fault`() {
        val cleared = gateway.clearSessionSources(
            TENANT,
            OWNER,
            "AbsentIt",
            SESSION,
            listOf(MemoryDraftSource("MEMORY.md", "- already merged away")),
        )

        assertEquals(MemoryStoreGateway.ClearedSources(cleared = 0, kept = 0, absent = 1), cleared)
    }

    @Test
    fun `applying an approval touches this owner's prefix and nothing else`() {
        val peerKey = curatedKey("NeighbourIt", PEER_PREFIX)
        val otherSession = sessionKey("NeighbourIt", "root", "MEMORY.md", sessionId = "sess-2")
        val ledger = sessionKey("NeighbourIt", "memory", "2026-10-06.md")
        put(store, curatedKey("NeighbourIt"), envelope("/MEMORY.md", "- what this owner curated before"))
        put(store, peerKey, envelope("/MEMORY.md", "- another owner's memory"))
        put(store, otherSession, envelope("/MEMORY.md", "- a conversation this approval is not about"))
        put(store, ledger, envelope("/2026-10-06.md", "- recorded"))

        assertTrue(gateway.writeCuratedIfVersion(TENANT, OWNER, "NeighbourIt", 1L, SECOND_MERGE))
        val cleared = gateway.clearSessionSources(
            TENANT,
            OWNER,
            "NeighbourIt",
            SESSION,
            listOf(MemoryDraftSource("memory/2026-10-06.md", "- recorded")),
        )

        assertEquals(1, cleared.cleared)
        assertEquals(SECOND_MERGE, gateway.readCuratedLayer(TENANT, OWNER, "NeighbourIt").content)
        assertEquals(
            "- another owner's memory",
            MemoryRecordParser.parse(bodyOf(store, peerKey)!!, objectMapper).content,
        )
        assertNotNull(bodyOf(store, otherSession), "one conversation's layer is not another's")
    }

    /**
     * An [ObjectProvider] that always answers [value].
     *
     * The gateway takes its client and its properties through one because `minio.enabled=false` is a supported
     * deployment; here the store is on, so both providers answer the real objects and nothing else about the
     * container is Spring's doing.
     */
    private fun <T : Any> providerOf(value: T): ObjectProvider<T> = object : ObjectProvider<T> {
        override fun getObject(): T = value

        override fun getObject(vararg args: Any?): T = value

        override fun getIfAvailable(): T = value

        override fun getIfUnique(): T = value

        override fun iterator(): MutableIterator<T> = mutableListOf(value).iterator()
    }
}
