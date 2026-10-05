package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.harness.minio.MinioBaseStore
import io.agentscope.core.agent.RuntimeContext
import io.minio.BucketExistsArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * The flat object key the runtime writes, read back from a live MinIO.
 *
 * Admin never asks the store for a namespace: it lists the bucket by a string prefix it builds itself
 * (`MemoryObjectKeys`), so the two modules have to spell the same key while sharing no code and no test.
 * [MinioBaseStore] builds its key from the namespace the route factory returns, and only the server says
 * what that comes to — these are the literals `MemoryObjectKeysTest` answers with on the other side.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryObjectKeyCrossCheckTest {

    companion object {
        private const val BUCKET = "harnax-store"
        private const val PREFIX = "store/"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"

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
}
