package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.harness.minio.MinioBaseStore
import io.agentscope.core.agent.RuntimeContext
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets

/**
 * The flat object key the runtime writes, read back from a live MinIO.
 *
 * Admin never asks the store for a namespace: it lists the bucket by a string prefix it builds itself
 * (`MemoryObjectKeys`), so the two modules have to spell the same key while sharing no code and no test.
 * [MinioBaseStore] builds its key from the namespace the route factory returns, and only the server says
 * what that comes to — these are the literals `MemoryObjectKeysTest` answers with on the other side.
 *
 * The key is only half of that contract. The other half is the *body*: every memory file arrives wrapped in
 * a `StoreWrapper` JSON envelope, and `MemoryRecordParser` digs the text out of `value.content`. Neither
 * module can see the other's classes, so the bytes this class writes are recorded here as a literal, and
 * `MemoryOwnerBucketMinioIT` in harnax-admin seeds its bucket with that same literal. If the writer's
 * envelope ever changes shape, the envelope case below goes red and the reader's fixture is stale in the
 * same commit — which is the only way a contract with no shared type can fail loudly.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryObjectKeyCrossCheckTest {

    companion object {
        private const val BUCKET = "harnax-store"
        private const val PREFIX = "store/"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"

        /**
         * One written memory file, byte for byte, as [MinioBaseStore] wraps it: the item key, then the value
         * map (`created_at`, `encoding`, `modified_at` and then `content` — the order the framework's
         * `HashMap` serialises in, not the order the fields are declared), then the version, which is 1 for
         * a first write. Only the two instants move, and [INSTANT] blanks them to `<timestamp>`.
         *
         * This is the shape `MemoryOwnerBucketMinioIT` in harnax-admin seeds its bucket from; changing one
         * byte here is a contract change on both sides of the store at once.
         */
        private const val ENVELOPE =
            "{\"key\":\"/MEMORY.md\",\"value\":{\"created_at\":\"<timestamp>\",\"encoding\":\"utf-8\"," +
                "\"modified_at\":\"<timestamp>\",\"content\":\"- a line\"},\"version\":1}"

        /** The ISO instant the writer stamps into `created_at` and `modified_at`. */
        private val INSTANT = Regex("2\\d{3}-\\d{2}-\\d{2}T[0-9:.+-]+Z?")

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
    }

    private fun client(): MinioClient = MinioClient.builder()
        .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
        .credentials(ACCESS_KEY, SECRET_KEY)
        .build()

    @BeforeAll
    fun createBucket() {
        val client = client()
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build())
        }
    }

    /** One owner per case, so each listing sees exactly what that case wrote. */
    private fun writeMemoryMd(
        owner: String,
        tenantScoped: Boolean,
        agentId: String = "Research",
        itemKey: String = "/MEMORY.md",
        segmentRoute: String = MemoryFilesystemRoutes.MEMORY_MD_ROUTE,
    ) {
        val routes = MemoryFilesystemRoutes.routes(
            MinioBaseStore(client(), BUCKET, PREFIX),
            tenantId = 4L,
            userId = owner,
            agentId = agentId,
            tenantScoped = tenantScoped,
        )
        val written = routes.getValue(segmentRoute).write(RuntimeContext.builder().sessionId("sess-1").build(), itemKey, "- a line")
        assertEquals(true, written.isSuccess, "the write should have landed: ${written.error()}")
    }

    /**
     * The keys one owner prefix sees, which is how admin finds a memory file at all.
     *
     * Recursive because a non-recursive listing answers with common prefixes (`…/agents/`) rather than
     * object names, and `MemoryStoreGateway` lists with the same flag.
     */
    private fun keysUnder(prefix: String): List<String> = client()
        .listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix).recursive(true).build())
        .asSequence()
        .map { it.get().objectName() }
        .toList()

    /** The whole body of one object, exactly as the server holds it — envelope and all. */
    private fun rawBody(objectKey: String): String = client()
        .getObject(GetObjectArgs.builder().bucket(BUCKET).`object`(objectKey).build())
        .use { it.readBytes().toString(StandardCharsets.UTF_8) }

    /**
     * The envelope those bytes carry, which is the other half of the contract admin reads against.
     *
     * `MemoryRecordParser` digs the file text out of `value.content` and falls back to `value.modified_at`,
     * so a body that moves either is a memory page that shows an owner nothing. Neither module can see the
     * other's classes, so the shape is recorded here as one literal ([ENVELOPE]) and
     * `MemoryOwnerBucketMinioIT` in harnax-admin seeds its bucket with that same string.
     *
     * The field order inside `value` is *not* a tidy declaration order: the framework builds the store value
     * as a `HashMap` (`RemoteFilesystem.fileDataToStoreValue`), so `content` serialises last, after
     * `created_at`, `encoding` and `modified_at`. That is why this has to be read off a live write rather
     * than written down from the wrapper class.
     */
    @Test
    fun `the wrapped body is the envelope admin's reader decodes`() {
        writeMemoryMd(owner = "enveloped", tenantScoped = true)

        val body = rawBody("store/tenants/4/users/enveloped/agents/Research/root/MEMORY.md")

        assertTrue(
            body.startsWith("""{"key":"/MEMORY.md","value":{"""),
            "the wrapper has to open with the item key and then the value object, the way" +
                "MemoryOwnerBucketMinioIT in harnax-admin spells its fixture; the body was: $body",
        )
        assertTrue(
            body.contains("\"content\":\"- a line\""),
            "the file text belongs at value.content — read the raw body and a caller sees JSON; the body was: $body",
        )
        assertTrue(
            body.contains("\"encoding\":\"utf-8\""),
            "the encoding field stays, or a base64 file would be shown as text; the body was: $body",
        )
        assertTrue(
            body.contains("\"version\":1"),
            "a first write has to answer version 1, or a reader replaying this body reads a stale object; the body was: $body",
        )
        assertEquals(
            ENVELOPE,
            body.replace(INSTANT, "<timestamp>"),
            "the recorded envelope drifted, so harnax-admin's MemoryOwnerBucketMinioIT seeds a body this" +
                " writer no longer produces; the raw body was: $body",
        )
    }

    @Test
    fun `the curated file lands at the key admin lists by`() {
        writeMemoryMd(owner = "keyed", tenantScoped = true)

        assertEquals(
            listOf("store/tenants/4/users/keyed/agents/Research/root/MEMORY.md"),
            keysUnder("store/tenants/4/users/keyed/"),
            "the runtime's key must equal the string MemoryObjectKeys.memoryMdKey builds",
        )
    }

    @Test
    fun `the daily ledger lands at the key admin lists by`() {
        writeMemoryMd(
            owner = "ledgered",
            tenantScoped = true,
            itemKey = "/2026-10-05.md",
            segmentRoute = MemoryFilesystemRoutes.MEMORY_DIR_ROUTE,
        )

        assertEquals(
            listOf("store/tenants/4/users/ledgered/agents/Research/memory/2026-10-05.md"),
            keysUnder("store/tenants/4/users/ledgered/"),
            "the ledger segment is `memory`, not the route name `memory/`",
        )
    }

    @Test
    fun `with the tenant segment off the key starts at users`() {
        // The same switch read on both sides: harness.memory.tenant-scoped on the write, harnax.memory
        // .tenant-scoped on the read. Off has to move the whole bucket, not just the listing.
        writeMemoryMd(owner = "unscoped", tenantScoped = false)

        assertEquals(
            listOf("store/users/unscoped/agents/Research/root/MEMORY.md"),
            keysUnder("store/users/unscoped/"),
        )
        assertEquals(
            emptyList<String>(),
            keysUnder("store/tenants/4/users/unscoped/"),
            "and nothing lands under a tenant",
        )
    }

    @Test
    fun `an agent name with a space is one key segment not two`() {
        // `agent.name` is a free varchar(100), and admin address-lists names like this on purpose; a store
        // that re-shaped the segment would put the file where the read side never looks.
        writeMemoryMd(owner = "punctuated", tenantScoped = true, agentId = "Ops Agent")

        assertEquals(
            listOf("store/tenants/4/users/punctuated/agents/Ops Agent/root/MEMORY.md"),
            keysUnder("store/tenants/4/users/punctuated/"),
        )
    }

    /** The same write through the conversation's own bucket, which is the two routes keyed one layer deeper. */
    private fun writeSessionMemoryMd(
        owner: String,
        sessionId: String,
        tenantScoped: Boolean = true,
    ) {
        val routes = MemoryFilesystemRoutes.sessionRoutes(
            MinioBaseStore(client(), BUCKET, PREFIX),
            tenantId = 4L,
            userId = owner,
            agentId = "Research",
            sessionId = sessionId,
            tenantScoped = tenantScoped,
        )
        val written = routes.getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(RuntimeContext.builder().sessionId(sessionId).build(), "/MEMORY.md", "- a line")
        assertEquals(true, written.isSuccess, "the write should have landed: ${written.error()}")
    }

    @Test
    fun `the session layer lands inside its agent so one prefix covers both layers`() {
        writeMemoryMd(owner = "layered", tenantScoped = true)
        writeSessionMemoryMd(owner = "layered", sessionId = "sess-A")

        assertEquals(
            listOf(
                "store/tenants/4/users/layered/agents/Research/root/MEMORY.md",
                "store/tenants/4/users/layered/agents/Research/sessions/sess-A/root/MEMORY.md",
            ),
            keysUnder("store/tenants/4/users/layered/agents/Research/").sorted(),
            "deleting this agent is what reclaims a conversation's memory, and that sweep lists by this prefix",
        )
    }

    @Test
    fun `the session key starts at users when the tenant segment is off`() {
        writeSessionMemoryMd(owner = "free", sessionId = "sess-B", tenantScoped = false)

        assertEquals(
            listOf("store/users/free/agents/Research/sessions/sess-B/root/MEMORY.md"),
            keysUnder("store/users/free/"),
        )
    }

    /**
     * Both layers of one owner with the tenant segment off, in one listing.
     *
     * harnax-admin reads its page off the same switch (`harnax.memory.tenant-scoped`), so this is the case
     * that says an unscoped deployment still finds a conversation's bucket: the switch moves the session
     * layer exactly as it moves the long-term one, rather than leaving one keyed by a tenant that is not
     * in the other's prefix.
     */
    @Test
    fun `turning the tenant segment off moves both layers together`() {
        writeMemoryMd(owner = "bothfree", tenantScoped = false)
        writeSessionMemoryMd(owner = "bothfree", sessionId = "sess-C", tenantScoped = false)

        assertEquals(
            listOf(
                "store/users/bothfree/agents/Research/root/MEMORY.md",
                "store/users/bothfree/agents/Research/sessions/sess-C/root/MEMORY.md",
            ),
            keysUnder("store/users/bothfree/").sorted(),
            "one owner, one key root, two layers under it",
        )
    }

    /** A bucket bound to one owner, on the same store the routes above write through. */
    private fun memoryDomain(owner: String) = MemoryDomain(
        store = MinioBaseStore(client(), BUCKET, PREFIX),
        tenantId = 4L,
        owner = owner,
        agentId = "Research",
        tenantScoped = true,
    )

    /** The store handed upstream for [owner], with its progress relocated into that owner's bucket. */
    private fun progressStore(owner: String): BucketScopedWatermarkStore = memoryDomain(owner)
        .let { BucketScopedWatermarkStore(it.store, it.namespace(null)) }

    /** What upstream keeps as that progress: one epoch-millis under `ts`. */
    private fun ts(
        millis: Long,
    ): Map<String, Any> = mapOf("ts" to millis)

    /**
     * The progress of a consolidation pass, at the address upstream gives it.
     *
     * `MemoryConsolidator` keeps this at one namespace for the whole deployment, so two owners sharing a
     * store share the number that decides which of each one's daily ledgers count as already merged — and an
     * entry skipped for that reason produces no error and no log line. Only the server says where the
     * relocated address really lands.
     */
    @Test
    fun `each owner's consolidation progress is an object in its own bucket`() {
        val ns = BucketScopedWatermarkStore.UPSTREAM_NAMESPACE
        val key = BucketScopedWatermarkStore.UPSTREAM_KEY

        progressStore("progress-a").put(ns, key, ts(1_000L))
        progressStore("progress-b").put(ns, key, ts(2_000L))

        assertEquals(
            listOf("store/tenants/4/users/progress-a/agents/Research/memory/watermark"),
            keysUnder("store/tenants/4/users/progress-a/"),
            "beside the ledgers it counts, so the sweep that deletes this agent's memory takes it too",
        )
        assertEquals(
            listOf("store/tenants/4/users/progress-b/agents/Research/memory/watermark"),
            keysUnder("store/tenants/4/users/progress-b/"),
        )
        assertEquals(
            emptyList<String>(),
            keysUnder("store/memory/"),
            "the address upstream would have used stays empty, or one owner still advances another's progress",
        )
    }

    @Test
    fun `the relocated progress still compares versions on this store`() {
        // Advancing the progress is a compare-and-set, because two replicas of one bucket may consolidate at
        // the same moment. Relocation must not cost that: a progress object every pass writes blind is worse
        // than the shared address it replaced.
        val ns = BucketScopedWatermarkStore.UPSTREAM_NAMESPACE
        val key = BucketScopedWatermarkStore.UPSTREAM_KEY
        val store = progressStore("progress-cas")

        store.put(ns, key, ts(1_000L))

        assertEquals(1L, store.get(ns, key)?.version, "a first write answers version 1, the way the routes' own writes do")
        assertEquals(
            true,
            store.putIfVersion(ns, key, ts(2_000L), 1L),
            "the pass that read version 1 has to be able to claim it",
        )
        assertEquals(
            false,
            store.putIfVersion(ns, key, ts(3_000L), 1L),
            "and a pass still holding version 1 has to lose, on this address of this bucket",
        )
    }
}
