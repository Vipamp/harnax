package com.agnetix.harnax.admin.it

import io.minio.BucketExistsArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The admin memory endpoints against a MinIO that holds the bytes the agent runtime writes.
 *
 * Three things this class is the only place that can show, and none of them is a shape:
 *
 * 1. **The writer's bytes are the reader's bytes.** `MinioBaseStore` (harnax-harness-core) wraps every
 *    memory file in a JSON envelope, and [com.agnetix.harnax.admin.util.MemoryRecordParser] digs the text
 *    out of `value.content`. The two modules cannot see each other's classes, so the shape is carried as a
 *    literal on both sides: [envelope] below spells the body printed by
 *    `MemoryObjectKeyCrossCheckTest.the wrapped body is the envelope admin's reader decodes` in harness-core,
 *    whose own KDoc names this class. If the writer changes its envelope that case goes red; if this fixture
 *    was not updated with it, the detail case below is the one that shows an owner an empty memory.
 * 2. **Real server semantics.** A prefix listing with the recursive flag has to actually answer with the
 *    object names under the caller's owner prefix rather than with common prefixes, a delete has to actually
 *    remove them, and a neighbouring owner's objects in the same bucket have to actually still be there
 *    afterwards. A mocked client cannot show any of that, because a mock answers whatever the stub was told
 *    to answer.
 * 3. **The HTTP layer.** A JWT-authenticated request through Spring Security to the three endpoints, with
 *    the `ResultVo` envelope read the way a browser reads it — including that an anonymous caller is turned
 *    away by the filter, and that a path-shaped agent id is refused by *some* layer in front of the store.
 *
 * What this class deliberately does *not* prove:
 * - It never runs the agent runtime. The bytes above are *pinned* in harness-core's
 *   `MemoryObjectKeyCrossCheckTest`, which writes them through the real route factory; here they are seeded
 *   directly, because booting a model-backed agent to produce one file is not an admin test.
 * - There is no "the store is unreachable answers 503" case.
 *   [com.agnetix.harnax.admin.service.impl.MemoryStoreGatewayTest] already maps every store refusal to a 503
 *   with a stub, and stopping the shared container here to fake a fault would take the other cases down with
 *   it — that is a mapping test, not a server test.
 * - The user-delete sweep (`UserMemoryCleaner`) is covered there too: which prefixes it names needs no live
 *   bucket to prove.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemoryOwnerBucketMinioIT : BaseAdminIT() {

    companion object {
        /** The bucket `MinioBaseStore` writes into; `minio.store-bucket` on the read side. */
        private const val STORE_BUCKET = "harnax-store"

        private const val STORE_PREFIX = "store/"

        /** Every other bucket a `minio.enabled=true` admin instance addresses; see [seedBucket]. */
        private const val OUTPUT_BUCKET = "harnax-output"

        private const val CLI_PACKAGE_BUCKET = "harnax-cli-packages"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"

        /**
         * The envelope of one memory file, recorded from a live write in harness-core:
         * `{"key":"/MEMORY.md","value":{"created_at":"2026-10-05T13:20:15.148570Z","encoding":"utf-8",
         * "modified_at":"2026-10-05T13:20:15.148570Z","content":"- a line"},"version":1}`.
         *
         * That string is the stdout of
         * `MemoryObjectKeyCrossCheckTest.the wrapped body is the envelope admin's reader decodes`, where the
         * order inside `value` comes from the framework's `HashMap` and not from the wrapper class — which is
         * why it had to be read off a real write. The two instants are the only part that ever moves, so this
         * class fixes both to [STAMP] and varies only the item key and the text.
         */
        private const val STAMP = "2026-10-05T13:20:15.148570Z"

        /** The workspace `adminToken()` claims, and therefore the tenant half of every key read here. */
        private const val TENANT = 1L

        /**
         * The owner prefix the gateway lists for this caller: `store/` + `tenants/1/users/1/`.
         *
         * Both 1s are data, not accidents — the tenant is the token's claim and the user is the seeded
         * `admin` row's `sys_user.id`, which is the value [com.agnetix.harnax.admin.util.MemoryObjectKeys]
         * spells into the key rather than the username.
         */
        private const val ADMIN_OWNER_PREFIX = "store/tenants/1/users/1/"

        private const val LISTED_AGENT = "ItMemory"
        private const val DELETABLE_AGENT = "ItDeletable"
        private const val PEER_AGENT = "PeerAgent"

        private const val CURATED = "# Memory\n- the user likes terse answers"
        private const val LEDGER_FIRST = "## 09:12\n- asked about the store layout"
        private const val LEDGER_SECOND = "## 14:02\n- confirmed the bucket prefix"
        private const val PEER_CURATED = "# Memory of the peer\n- never shown to user 1"

        /** A slice of [CURATED] with no newline in it, so a leaked body can be spotted in an error page. */
        private const val CURATED_MARKER = "likes terse answers"

        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)

        /**
         * Points the real `MinioClient` bean at the container.
         *
         * The `it` profile configures no MinIO at all, so `minio.enabled` is false and
         * [com.agnetix.harnax.admin.config.AdminMinioConfig] keeps the client away — every memory call would
         * then answer 503 "not configured on this instance". The container is started here rather than in
         * [seedBucket] because the registry has to see a mapped port before the context is created.
         */
        @JvmStatic
        @DynamicPropertySource
        fun minioProperties(registry: DynamicPropertyRegistry) {
            minio.start()
            registry.add("minio.enabled") { "true" }
            registry.add("minio.endpoint") { "http://${minio.host}:${minio.getMappedPort(9000)}" }
            registry.add("minio.access-key") { ACCESS_KEY }
            registry.add("minio.secret-key") { SECRET_KEY }
            registry.add("minio.store-bucket") { STORE_BUCKET }
            registry.add("minio.store-prefix") { STORE_PREFIX }
        }
    }

    /** The store as this class sees it: the endpoint the Spring bean talks to, through its own client. */
    private val store: MinioClient by lazy {
        MinioClient.builder()
            .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
            .credentials(ACCESS_KEY, SECRET_KEY)
            .build()
    }

    private val tag = Random.nextInt(100000, 999999)
    private val peerUsername = "it_mem_peer_$tag"
    private val freshUsername = "it_mem_fresh_$tag"

    private var peerId = 0L
    private var freshId = 0L
    private lateinit var peerToken: String
    private lateinit var freshToken: String

    /** The memory file [itemKey] holding [content], exactly as the writer would have wrapped it. */
    private fun envelope(
        itemKey: String,
        content: String,
    ): String = "{\"key\":\"$itemKey\",\"value\":{\"created_at\":\"$STAMP\",\"encoding\":\"utf-8\"," +
        "\"modified_at\":\"$STAMP\",\"content\":${json.writeValueAsString(content)}},\"version\":1}"

    private fun put(
        objectKey: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        store.putObject(
            PutObjectArgs.builder()
                .bucket(STORE_BUCKET)
                .`object`(objectKey)
                .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                .contentType("application/json")
                .build(),
        )
    }

    /** Recursive, like the gateway: a non-recursive listing answers with common prefixes, not object names. */
    private fun keysUnder(prefix: String): List<String> = store
        .listObjects(ListObjectsArgs.builder().bucket(STORE_BUCKET).prefix(prefix).recursive(true).build())
        .asSequence()
        .map { it.get().objectName() }
        .toList()
        .sorted()

    /** The curated layer's key of one agent of one owner. */
    private fun curatedKey(
        agent: String,
        ownerPrefix: String = ADMIN_OWNER_PREFIX,
    ): String = "${ownerPrefix}agents/$agent/root/MEMORY.md"

    /** One daily ledger's key of one agent of one owner. */
    private fun ledgerKey(
        date: String,
        agent: String,
        ownerPrefix: String = ADMIN_OWNER_PREFIX,
    ): String = "${ownerPrefix}agents/$agent/memory/$date.md"

    private fun agentPrefix(
        agent: String,
        ownerPrefix: String = ADMIN_OWNER_PREFIX,
    ): String = "${ownerPrefix}agents/$agent/"

    private fun peerOwnerPrefix(): String = "store/tenants/$TENANT/users/$peerId/"

    /** Creates a real `sys_user` row through the admin API and answers its id, the segment the bucket uses. */
    private fun createUser(username: String): Long {
        assertOk(
            postJson(
                "/api/admin/users",
                mapOf(
                    "username" to username,
                    "password" to "abcdef123456",
                    "nickname" to "IT Memory $username",
                    "email" to "$username@it.harnax.com",
                    "phone" to "138${Random.nextLong(10000000, 99999999)}",
                    "gender" to 1,
                ),
            ),
        )
        val row = findInPage("/api/admin/users/page", "keyword=$username") { it["username"]?.asText() == username }
        assertNotNull(row, "the account the memory is seeded for should exist")
        return row["id"].asLong()
    }

    @BeforeAll
    fun seedBucket() {
        // Turning minio.enabled=true on does not only give the memory gateway a store: it also boots
        // OutputFileController and TeamArtifactController, which read `harnax-output`, and
        // CliPackageAutoRegistrar, which writes `harnax-cli-packages`. Admin never creates a bucket — a real
        // deployment has all three provisioned — so they are made here, or an unrelated request inside this
        // context would die on BucketNotFound.
        listOf(STORE_BUCKET, OUTPUT_BUCKET, CLI_PACKAGE_BUCKET).forEach { bucket ->
            if (!store.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                store.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
            }
        }

        // The owner segment is `sys_user.id` (MemoryObjectKeys.userSegment), never the username, and the
        // tenant comes from the caller's token claim — so the admin token (id 1, tenant 1) reads exactly
        // store/tenants/1/users/1/. A second account is created through the API because a principal with no
        // row of its own is refused before the store is ever listed.
        peerId = createUser(peerUsername)
        freshId = createUser(freshUsername)
        peerToken = jwtUtil.generateToken(peerId, peerUsername, TENANT, 0)
        freshToken = jwtUtil.generateToken(freshId, freshUsername, TENANT, 0)

        put(curatedKey(LISTED_AGENT), envelope("/MEMORY.md", CURATED))
        put(ledgerKey("2026-10-04", LISTED_AGENT), envelope("/2026-10-04.md", LEDGER_FIRST))
        put(ledgerKey("2026-10-05", LISTED_AGENT), envelope("/2026-10-05.md", LEDGER_SECOND))

        put(curatedKey(DELETABLE_AGENT), envelope("/MEMORY.md", CURATED))
        put(ledgerKey("2026-10-05", DELETABLE_AGENT), envelope("/2026-10-05.md", LEDGER_SECOND))

        // Two neighbours that must survive every call this class makes: another owner in the same tenant,
        // and this same owner's copy in another workspace.
        put(curatedKey(PEER_AGENT, peerOwnerPrefix()), envelope("/MEMORY.md", PEER_CURATED))
        put(ledgerKey("2026-10-05", PEER_AGENT, peerOwnerPrefix()), envelope("/2026-10-05.md", LEDGER_FIRST))
        put(curatedKey(LISTED_AGENT, "store/tenants/2/users/1/"), envelope("/MEMORY.md", PEER_CURATED))
    }

    @Test
    @Order(1)
    fun `a delete removes exactly that agent's two routes and leaves every neighbour alone`() {
        val data = assertOk(deleteJson("/api/admin/memory/$DELETABLE_AGENT"))

        assertEquals(DELETABLE_AGENT, data["agentId"].asText())
        assertEquals(
            2,
            data["deletedObjects"].asInt(),
            "the curated layer and the one ledger went, and the count is what an operator reads",
        )
        assertEquals(
            emptyList<String>(),
            keysUnder(agentPrefix(DELETABLE_AGENT)),
            "the container's own listing says both objects are gone",
        )
        // Everything else in the shared bucket stays: this caller's other agent, the other owner in this
        // tenant, and this caller's copy under another tenant.
        assertEquals(3, keysUnder(ADMIN_OWNER_PREFIX).size, "only the named agent's prefix went empty")
        assertEquals(2, keysUnder(peerOwnerPrefix()).size, "another owner's memory is never in scope")
        assertEquals(1, keysUnder("store/tenants/2/users/1/").size, "another workspace's copy is never in scope")
    }

    @Test
    @Order(2)
    fun `deleting the same agent again is a success with zero objects`() {
        val data = assertOk(deleteJson("/api/admin/memory/$DELETABLE_AGENT"))

        assertEquals(0, data["deletedObjects"].asInt(), "an idempotent second call is not a failure")
        assertEquals(
            emptyList<String>(),
            keysUnder(agentPrefix(DELETABLE_AGENT)),
            "and it had nothing left to remove",
        )
    }

    @Test
    @Order(3)
    fun `the listing answers the caller's agent with its curated text and its ledger dates`() {
        val agents = assertOk(getJson("/api/admin/memory"))

        val row = agents.firstOrNull { it["agentId"]?.asText() == LISTED_AGENT }
        assertNotNull(row, "the agent seeded for this caller should come back: $agents")
        assertEquals(CURATED, row["content"].asText(), "value.content is the text the caller has to see")
        assertEquals(
            listOf("2026-10-04", "2026-10-05"),
            row["dates"].map { it.asText() },
            "the ledger is dated out of the object names, oldest first",
        )
        assertNotNull(row["lastModified"], "the write time comes from the object, not from the envelope")
        assertTrue(
            agents.none { it["agentId"]?.asText() == PEER_AGENT },
            "a listing of the caller's own prefix never carries another owner's agent: $agents",
        )
    }

    @Test
    @Order(4)
    fun `the detail endpoint answers the curated text and every daily entry`() {
        val data = assertOk(getJson("/api/admin/memory/$LISTED_AGENT"))

        // This is the case that goes red if the envelope shape drifted: an unreadable wrapper decodes as an
        // empty string, and the caller would be shown a memory that is really sitting in the bucket.
        assertEquals(LISTED_AGENT, data["agentId"].asText())
        assertEquals(CURATED, data["content"].asText())
        val entries = data["entries"]
        assertNotNull(entries, "both routes of the agent are in the detail: $data")
        assertEquals(listOf("2026-10-04", "2026-10-05"), entries.map { it["date"].asText() })
        assertEquals(listOf(LEDGER_FIRST, LEDGER_SECOND), entries.map { it["content"].asText() })
    }

    @Test
    @Order(5)
    fun `an agent the caller has no memory of answers 404 in the envelope`() {
        val node = getJson("/api/admin/memory/ItNoSuchAgent")

        assertEquals(404, node["code"].asInt())
        assertEquals("No memory for this agent", node["message"].asText())
        assertNoData(node)
    }

    @Test
    @Order(6)
    fun `an agent id that could name a path is refused and never touches the store`() {
        // What a live stack does with these is not what a unit test can say. An id that still spells one
        // path segment reaches `MemoryObjectKeys.isValidAgentId` and is refused the way this module refuses
        // business failures — HTTP 200 with `code` 400. An id that has to be *encoded* to look like a path
        // (`%2F`, `%5C`) or that is a traversal segment (`..`, `%2E%2E`) is thrown away by Spring Security's
        // firewall before `JwtAuthenticationFilter` runs at all: the request never reaches the controller and
        // surfaces as an error dispatch, which is why no memory text comes back either way.
        val before = keysUnder(STORE_PREFIX)

        listOf("/api/admin/memory/a..b", "/api/admin/memory/...").forEach { path ->
            assertRefusedByTheEnvelope(exchange(HttpMethod.GET, path), path)
            assertRefusedByTheEnvelope(exchange(HttpMethod.DELETE, path), path)
        }

        listOf(
            "/api/admin/memory/a%2Fb",
            "/api/admin/memory/a%5Cb",
            "/api/admin/memory/..",
            "/api/admin/memory/%2E%2E",
        ).forEach { path ->
            assertTurnedAwayBeforeTheController(exchange(HttpMethod.GET, path), path)
            assertTurnedAwayBeforeTheController(exchange(HttpMethod.DELETE, path), path)
        }

        assertEquals(before, keysUnder(STORE_PREFIX), "a refused agent id must not remove or create a byte")
    }

    @Test
    @Order(7)
    fun `a request with no JWT does not get a listing`() {
        listOf(
            exchange(HttpMethod.GET, "/api/admin/memory", token = null),
            exchange(HttpMethod.GET, "/api/admin/memory/$LISTED_AGENT", token = null),
            exchange(HttpMethod.DELETE, "/api/admin/memory/$LISTED_AGENT", token = null),
        ).forEach { response ->
            assertEquals(401, response.statusCode.value(), "the security filter turns an anonymous caller away")
            assertTrue(
                response.body?.contains(CURATED_MARKER) != true,
                "an unauthenticated answer cannot carry the caller's memory text: ${response.body}",
            )
        }
        assertEquals(3, keysUnder(agentPrefix(LISTED_AGENT)).size, "and nothing left the bucket either")
    }

    @Test
    @Order(8)
    fun `a second owner reads only their own prefix`() {
        val own = assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/memory", token = peerToken)))

        assertEquals(
            listOf(PEER_AGENT),
            own.map { it["agentId"].asText() },
            "the peer sees the agent seeded under its own account",
        )
        assertEquals(PEER_CURATED, own[0]["content"].asText())
        assertTrue(
            own.none { it["agentId"]?.asText() == LISTED_AGENT },
            "and never the memory of the account it is not: $own",
        )

        val asAdmin = assertOk(getJson("/api/admin/memory"))
        assertTrue(
            asAdmin.none { it["agentId"]?.asText() == PEER_AGENT },
            "the first caller still never sees the peer's agent either: $asAdmin",
        )
    }

    @Test
    @Order(9)
    fun `an owner with an empty bucket answers a success with an empty list`() {
        val node = parseBody(exchange(HttpMethod.GET, "/api/admin/memory", token = freshToken))

        assertEquals(200, node["code"].asInt(), "no memory yet is not an error")
        val data = node["data"]
        assertNotNull(data, "an empty listing still answers with its array: $node")
        assertTrue(data.isArray && data.isEmpty(), "an owner with nothing written has an empty list")

        val detail = parseBody(exchange(HttpMethod.GET, "/api/admin/memory/$LISTED_AGENT", token = freshToken))
        assertEquals(404, detail["code"].asInt(), "an agent nobody seeded for this owner is absent, not empty")

        val removed = assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/memory/$LISTED_AGENT", token = freshToken)))
        assertEquals(0, removed["deletedObjects"].asInt(), "and a sweep of an empty prefix removes nothing")
        assertEquals(
            3,
            keysUnder(agentPrefix(LISTED_AGENT)).size,
            "not even somebody else's objects of the same agent name went",
        )
    }

    /**
     * An agent id that still spells one path segment (`a..b`, `...`) does reach the controller, which refuses
     * it the way this module refuses every business failure: HTTP 200 with the 400 inside the envelope and no
     * data. A client reads the code, not the status.
     */
    private fun assertRefusedByTheEnvelope(
        response: ResponseEntity<String>,
        path: String,
    ) {
        val body = response.body.orEmpty()
        assertEquals(200, response.statusCode.value(), "$path is refused inside the envelope, not over HTTP: $body")
        val node: JsonNode = json.readTree(body)
        assertEquals(400, node["code"].asInt(), "$path should be refused as an unusable agent id, but answered: $body")
        assertNoData(node)
    }

    /**
     * An id that only looks like a path once it is encoded (`%2F`, `%5C`) or that *is* a traversal segment
     * (`..`, `%2E%2E`) is discarded by Spring Security's firewall before `JwtAuthenticationFilter` runs, so
     * the controller never sees it: both verbs answer the 401 of the error dispatch that lands on the
     * protected `/error`, with the GET body carrying the authentication failure and the DELETE body empty.
     * Refused by a different layer than [assertRefusedByTheEnvelope], but just as far from the store.
     */
    private fun assertTurnedAwayBeforeTheController(
        response: ResponseEntity<String>,
        path: String,
    ) {
        val body = response.body.orEmpty()
        assertEquals(401, response.statusCode.value(), "$path never reaches the controller: $body")
        assertTrue(!body.contains(CURATED_MARKER), "$path leaked the caller's memory text: $body")
    }

    /** ResultVo drops a null `data`, so an error envelope either omits the key or answers it as null. */
    private fun assertNoData(node: JsonNode) {
        val data = node["data"]
        assertTrue(data == null || data.isNull, "an error envelope carries no data: $node")
    }
}
