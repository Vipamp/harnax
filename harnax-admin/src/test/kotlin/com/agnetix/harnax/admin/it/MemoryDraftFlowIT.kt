package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.util.MemoryRecordParser
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.errors.ErrorResponseException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A conversation's merge from the runtime's intake to its owner's long-term layer, on a real MySQL and a real
 * MinIO.
 *
 * [com.agnetix.harnax.admin.service.impl.MemoryDraftServiceImplTest] mocks both stores, and six things this
 * queue rests on are decided by what a server actually answers rather than by the service:
 *
 * 1. **The row survives its own columns.** `merged_md`, `base_md`, `sources` and `reject_reason` are written
 *    by one INSERT and read back by four different SELECTs; a column the result map misses answers as a blank
 *    candidate, which an owner would then approve as an empty memory.
 * 2. **The two conditional statements are conditional.** `updateContent` and `markReviewed` both carry
 *    `WHERE ... status = 'PENDING'`, and the whole "one open candidate per conversation" and "two tabs decide
 *    once" designs are those two row counts. A mock hands back whatever it was told to hand back.
 * 3. **The queue's scoping predicates are in the executed SQL.** The listing filters by tenant *and* account,
 *    so a second account of the same workspace and a workspace switch each have to answer with nothing.
 * 4. **The approval writes a real object the runtime's own reader decodes**, at the key the agent's next
 *    conversation will read — the bucket identity is the point of the intake's three cross-checks, and only
 *    the store can show it was right.
 * 5. **The clear removes bytes and counts honestly.** The draft file that still holds what the merge recorded
 *    goes; the ledger a later turn appended stays. Both answers come off a server, and the difference between
 *    them is what the owner is told.
 * 6. **The bearer the proposal arrives on gets nothing from the review half** — through the real filter chain,
 *    not through a stubbed security context.
 *
 * Everything enters through the wire: `/api/admin/internal/memory/drafts` on the shared secret for the
 * runtime, `/api/admin/memory-drafts` on a JWT for the owner, and `/api/admin/memory` to read back what the
 * approval left in the bucket.
 *
 * Deliberately not here: the store semantics themselves (a refused conditional write, a dropped connection
 * after a refusal, an envelope that is not an envelope) — those are [MemoryApprovalStoreIT] and the gateway's
 * own test, and this class would only be re-measuring them through an extra HTTP hop.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("Memory draft queue - intake, review and approval on a real stack")
class MemoryDraftFlowIT : BaseAdminIT() {

    companion object {
        private const val STORE_BUCKET = "harnax-store"

        private const val STORE_PREFIX = "store/"

        /** The other two buckets a `minio.enabled=true` admin instance addresses; see [seed]. */
        private const val OUTPUT_BUCKET = "harnax-output"

        private const val CLI_PACKAGE_BUCKET = "harnax-cli-packages"

        private const val ACCESS_KEY = "minioadmin"

        private const val SECRET_KEY = "minioadmin"

        /** Must match `admin.internal-api.secret` in application-it.yml. */
        private const val INTERNAL_SECRET = "it-internal-api-secret-0123456789abcdef"

        /** The envelope's two timestamps, fixed so a written object differs from a seeded one only in text. */
        private const val STAMP = "2026-10-05T13:20:15.148570Z"

        private const val TENANT = 1L

        /** The admin token's own namespace: tenant 1, `sys_user.id` 1, which is the owner segment. */
        private const val ADMIN_OWNER_PREFIX = "store/tenants/1/users/1/"

        /** A workspace this stack has no rows in; a switch there has to answer an empty queue. */
        private val foreignTenant = 940_002L

        /** What the owner had curated before this conversation said anything: nothing. */
        private const val NO_BASE_VERSION = 0L

        private const val LEDGER_DATE = "2026-10-06"

        /** The bytes the merge found in the conversation's draft file, and the text it produced from them. */
        private const val SESSION_DRAFT = "- the user likes terse answers\n- no trailing summaries"

        private const val MERGED = "# Memory\n- the user likes terse answers\n- no trailing summaries"

        private const val MERGED_SHORTER = "# Memory\n- the user likes terse answers"

        /** The ledger as the merge read it, and as a later turn left it: only the first may be cleared. */
        private const val LEDGER_RECORD = "- asked that the ledger be kept"

        private const val LEDGER_MOVED = "$LEDGER_RECORD\n- wrote again after the merge read it"

        /** A slice with no newline, so a leaked body shows up on one line of a JSON answer. */
        private const val MERGED_MARKER = "no trailing summaries"

        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)

        /**
         * Points the real `MinioClient` bean at the container; the `it` profile configures no MinIO at all,
         * so without this every approval would be refused as 503 "no memory store is reachable".
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

    /** The store as this class seeds and checks it, through its own client rather than through the gateway. */
    private val store: MinioClient by lazy {
        MinioClient.builder()
            .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
            .credentials(ACCESS_KEY, SECRET_KEY)
            .build()
    }

    private val tag = Random.nextInt(100000, 999999)

    /** The bucket segment and the agent row's name at once, which is exactly why the intake cross-checks it. */
    private val agentName = "it_memory_flow_$tag"

    private val sessionTitle = "it_memory_flow_session_$tag"

    private val peerUsername = "it_memory_flow_peer_$tag"

    private var sessionId = ""

    private var draftId = -1L

    /** The digest of the candidate as it stands, refreshed by whichever case last wrote the row. */
    private var digest = ""

    private var peerId = 0L

    private lateinit var peerToken: String

    private fun sessionCuratedKey(): String = "${agentPrefix()}sessions/$sessionId/root/MEMORY.md"

    private fun sessionLedgerKey(): String = "${agentPrefix()}sessions/$sessionId/memory/$LEDGER_DATE.md"

    private fun curatedKey(): String = "${agentPrefix()}root/MEMORY.md"

    private fun agentPrefix(): String = "${ADMIN_OWNER_PREFIX}agents/$agentName/"

    /** One memory file's envelope, as `MinioBaseStore` writes it. */
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

    private fun bodyOf(objectKey: String): String? = try {
        store.getObject(GetObjectArgs.builder().bucket(STORE_BUCKET).`object`(objectKey).build())
            .use { it.readBytes().toString(StandardCharsets.UTF_8) }
    } catch (e: ErrorResponseException) {
        if (e.response().code == 404) null else throw e
    }

    private fun keysUnder(prefix: String): List<String> = store
        .listObjects(ListObjectsArgs.builder().bucket(STORE_BUCKET).prefix(prefix).recursive(true).build())
        .asSequence()
        .map { it.get().objectName() }
        .toList()
        .sorted()

    private fun textAt(objectKey: String): String? = bodyOf(objectKey)?.let { MemoryRecordParser.parse(it, json).content }

    /** The body a merge posts to the intake, in the shape `AdminApiClient` serialises. */
    private fun submit(
        mergedMarkdown: String,
        baseMarkdown: String? = null,
        baseVersion: Long = NO_BASE_VERSION,
        agent: String = agentName,
        session: String = sessionId,
        sources: List<Map<String, Any?>> = defaultSources,
    ): JsonNode = parseBody(
        exchange(
            HttpMethod.POST,
            "/api/admin/internal/memory/drafts",
            body = mapOf(
                "sessionId" to session,
                "agentName" to agent,
                "mergedMarkdown" to mergedMarkdown,
                "baseMarkdown" to baseMarkdown,
                "baseVersion" to baseVersion,
                "sources" to sources,
            ),
            token = INTERNAL_SECRET,
        ),
    )

    /** The sources as the merge reports them: the draft it took, and the ledger as it read it. */
    private val defaultSources: List<Map<String, Any?>> = listOf(
        mapOf("path" to "MEMORY.md", "content" to SESSION_DRAFT),
        mapOf("path" to "memory/$LEDGER_DATE.md", "content" to LEDGER_RECORD),
    )

    private fun queue(query: String = "pageSize=100"): List<JsonNode> = assertOk(getJson("/api/admin/memory-drafts?$query"))["records"].toList()

    private fun queueRow(status: String? = null): JsonNode? = queue("sessionId=$sessionId&pageSize=100" + (status?.let { "&status=$it" } ?: ""))
        .firstOrNull { it["agentName"].asText() == agentName }

    private fun detail(id: Long): JsonNode = assertOk(getJson("/api/admin/memory-drafts/$id"))

    private fun digestOf(id: Long): String = detail(id)["contentDigest"].asText()

    private fun approve(
        id: Long,
        expectedDigest: String,
        token: String? = adminToken(),
    ): JsonNode = parseBody(
        exchange(
            HttpMethod.POST,
            "/api/admin/memory-drafts/$id/approve",
            body = mapOf("expectedDigest" to expectedDigest),
            token = token,
        ),
    )

    private fun reject(
        id: Long,
        reason: String?,
        token: String? = adminToken(),
    ): JsonNode = parseBody(
        exchange(
            HttpMethod.POST,
            "/api/admin/memory-drafts/$id/reject",
            body = mapOf("reason" to reason),
            token = token,
        ),
    )

    private fun createUser(username: String): Long {
        assertOk(
            postJson(
                "/api/admin/users",
                mapOf(
                    "username" to username,
                    "password" to "abcdef123456",
                    "nickname" to "IT memory flow $username",
                    "email" to "$username@it.harnax.com",
                    "phone" to "139${Random.nextLong(10000000, 99999999)}",
                    "gender" to 1,
                ),
            ),
        )
        val row = findInPage("/api/admin/users/page", "keyword=$username") { it["username"]?.asText() == username }
        assertNotNull(row, "the account the candidate belongs to should exist")
        return row["id"].asLong()
    }

    @BeforeAll
    fun seed() {
        // minio.enabled=true boots more than this queue: the output and CLI-package controllers address their
        // own buckets, and admin never creates one, so all three are made here or an unrelated request inside
        // this context dies on BucketNotFound.
        listOf(STORE_BUCKET, OUTPUT_BUCKET, CLI_PACKAGE_BUCKET).forEach { bucket ->
            if (!store.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                store.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
            }
        }

        peerId = createUser(peerUsername)
        peerToken = jwtUtil.generateToken(peerId, peerUsername, TENANT, 0)

        // A real session row, because the intake's whole identity claim is that it can read an owner, a tenant
        // and an agent off the session id.
        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName, "IT memory flow agent")))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") { it["name"]?.asText() == agentName }
        assertNotNull(agent, "the agent whose layer the merge joins should exist")
        assertOk(postJson("/api/admin/sessions", mapOf("title" to sessionTitle, "agentId" to agent!!["id"].asLong())))
        val session = findInPage("/api/admin/sessions/page", "keyword=$sessionTitle") { it["title"]?.asText() == sessionTitle }
        assertNotNull(session, "the conversation that proposed the merge should exist")
        sessionId = session!!["sessionId"].asText()

        // The conversation's own layer as the merge left it: the draft whose bytes it recorded, and a ledger
        // that a later turn has since appended to — which the clear must not touch.
        put(sessionCuratedKey(), envelope("/MEMORY.md", SESSION_DRAFT))
        put(sessionLedgerKey(), envelope("/$LEDGER_DATE.md", LEDGER_MOVED))
    }

    @Test
    @Order(1)
    fun `the intake files a candidate and the owner reads back exactly the bytes it merged`() {
        val answered = submit(MERGED)
        assertTrue(answered["code"].asInt() == 200, "the intake refused a valid merge: $answered")
        draftId = answered["data"].asLong()
        assertTrue(draftId > 0, "the intake answers the candidate row, which is all the runtime can report: $answered")

        val row = queueRow()
        assertNotNull(row, "the owner's queue should list the candidate: ${queue()}")
        assertEquals("PENDING", row!!["status"].asText())
        assertEquals(agentName, row["agentName"].asText())
        assertEquals(sessionId, row["sessionId"].asText(), "the queue has to say which conversation wrote it")
        assertEquals(2, row["sourceCount"].asInt(), "both files the merge read, counted off the stored JSON")
        assertEquals(MERGED.length, row["mergedChars"].asInt(), "the list carries a size, not the text")
        assertFalse(row.has("mergedMd"), "a listing that carried every candidate's whole text is a page of memory")

        val shown = detail(draftId)
        assertEquals(MERGED, shown["mergedMd"].asText(), "the stored text is the bytes the runtime posted")
        assertFalse(shown.has("baseMd"), "an owner with no long-term layer yet is shown none, not an empty file")
        assertEquals(NO_BASE_VERSION, shown["baseVersion"].asLong())
        assertEquals(
            listOf("MEMORY.md", "memory/$LEDGER_DATE.md"),
            shown["sources"].map { it["path"].asText() },
            "the sources are stored in the canonical order the digest is taken over",
        )
        assertEquals(SESSION_DRAFT, shown["sources"][0]["content"].asText(), "each file's bytes survive the column")
        digest = shown["contentDigest"].asText()
        assertEquals(64, digest.length, "an approval has to be given a real digest to sign back")
    }

    @Test
    @Order(2)
    fun `a second merge from the same conversation rewrites the open candidate and invalidates the first digest`() {
        val resubmitted = submit(MERGED_SHORTER)
        assertEquals(draftId, resubmitted["data"].asLong(), "one undecided conversation stays one candidate")
        assertEquals(1, queue("sessionId=$sessionId&pageSize=100").size, "and the queue did not grow a second row")

        val current = digestOf(draftId)
        assertNotEquals(digest, current, "a patch that does not move the digest would let a stale approval land")
        assertEquals(MERGED_SHORTER, detail(draftId)["mergedMd"].asText())

        val stale = approve(draftId, digest)
        assertEquals("DRAFT_CHANGED", assertOk(stale)["outcome"].asText(), "the refusal is an outcome, not an error")
        assertEquals(current, stale["data"]["currentDigest"].asText(), "carrying the digest to sign against is the whole fix")

        // Refused, and nothing left the bucket: no long-term object, and both conversation files still there.
        assertNull(bodyOf(curatedKey()), "a digest that no longer matches must not buy a write")
        assertEquals(
            listOf(sessionLedgerKey(), sessionCuratedKey()),
            keysUnder(agentPrefix()),
            "and it must not clear the conversation's own layer either",
        )
        digest = current
    }

    @Test
    @Order(3)
    fun `approval writes the layer where the agent's next conversation reads it and clears only the file that still matches`() {
        val decision = assertOk(approve(draftId, digest))

        assertEquals("APPROVED", decision["outcome"].asText())
        assertEquals(1L, decision["longTermVersion"].asLong(), "the owner had no layer, so this is version one")
        assertEquals(1, decision["clearedSources"].asInt(), "the draft still held the bytes the merge recorded")
        assertEquals(1, decision["keptSources"].asInt(), "the ledger moved after the read, so it stays")
        assertEquals(0, decision["absentSources"].asInt())

        assertEquals(MERGED_SHORTER, textAt(curatedKey()), "the text is what the runtime's own reader decodes")
        assertEquals(
            listOf(curatedKey(), sessionLedgerKey()),
            keysUnder(agentPrefix()),
            "the merged-away draft is gone from the bucket, not merely reported gone",
        )
        assertEquals(LEDGER_MOVED, textAt(sessionLedgerKey()), "a turn that wrote after the merge keeps its words")

        // The owner's memory page, read through the ordinary route: this is what makes the next conversation
        // of the same agent start with this text, which is the promise the whole approval exists for.
        val memory = assertOk(getJson("/api/admin/memory/$agentName"))
        assertEquals(MERGED_SHORTER, memory["content"].asText())
        assertEquals(
            emptyList<String>(),
            memory["entries"].map { it["date"].asText() },
            "the conversation's own ledger is not a day of the owner's long-term memory",
        )
        assertTrue(
            assertOk(getJson("/api/admin/memory")).any { it["agentId"].asText() == agentName },
            "and the agent now appears in the memory listing at all",
        )
    }

    @Test
    @Order(4)
    fun `a second approval answers the decision that beat it and rewrites nothing`() {
        val late = approve(draftId, digest)

        assertEquals("ALREADY_REVIEWED", assertOk(late)["outcome"].asText())
        assertEquals("admin", late["data"]["reviewedBy"].asText(), "the owner is told who decided, not that somebody did")
        assertEquals("APPROVED", detail(draftId)["status"].asText())

        assertEquals(
            1L,
            json.readTree(bodyOf(curatedKey())!!).path("version").asLong(),
            "a late click did not bump the layer's version",
        )
        assertEquals(listOf(curatedKey(), sessionLedgerKey()), keysUnder(agentPrefix()))

        val approved = queue("status=APPROVED&pageSize=100").map { it["agentName"].asText() }
        assertTrue(agentName in approved, "a decided candidate stays findable: $approved")
        val shown = queueRow("APPROVED")!!
        assertTrue(shown.hasNonNull("reviewedAt"), "the trail carries when: $shown")
        assertEquals("admin", shown["reviewedBy"].asText())
        assertTrue(
            queue().none { it["agentName"].asText() == agentName },
            "and the open queue — what the page shows when nobody picks a status — has moved on",
        )
    }

    @Test
    @Order(5)
    fun `a merge that arrives after the decision files its own row and a rejection closes it with a reason`() {
        val filed = submit(MERGED)
        val lateId = filed["data"].asLong()
        assertNotEquals(draftId, lateId, "a decided candidate is not reopened; the newer merge waits on its own row")
        assertEquals("PENDING", detail(lateId)["status"].asText())

        assertEquals(400, reject(lateId, "  ")["code"].asInt(), "a blank reason is an error, not an outcome")
        val overlong = reject(lateId, "x".repeat(513))
        assertEquals(400, overlong["code"].asInt())
        assertTrue(overlong["message"].asText().contains("512"), "the refusal names the column's width: ${overlong["message"]}")

        assertEquals("REJECTED", assertOk(reject(lateId, "Duplicates a rule the layer already holds"))["outcome"].asText())

        val shown = detail(lateId)
        assertEquals("REJECTED", shown["status"].asText())
        assertEquals("Duplicates a rule the layer already holds", shown["rejectReason"].asText(), "the reason survives its column")
        assertEquals("admin", shown["reviewedBy"].asText())

        // A rejection touches neither layer: the conversation keeps its own files, so the same material is
        // proposed again next window — which is what the owner was told when they wrote the reason.
        assertEquals(listOf(curatedKey(), sessionLedgerKey()), keysUnder(agentPrefix()))
        assertEquals(MERGED_SHORTER, textAt(curatedKey()), "the approved layer did not move")
    }

    @Test
    @Order(6)
    fun `the bearer a proposal arrives on and an account that is not the owner get nothing from the review half`() {
        val gateName = "it_memory_gate_$tag"
        assertOk(postJson("/api/admin/agents", agentCreateBody(gateName, "IT memory gate agent")))
        val gateAgent = findInPage("/api/admin/agents/page", "name=$gateName") { it["name"]?.asText() == gateName }
        assertNotNull(gateAgent, "the second agent should exist")
        val gateTitle = "it_memory_gate_session_$tag"
        assertOk(postJson("/api/admin/sessions", mapOf("title" to gateTitle, "agentId" to gateAgent!!["id"].asLong())))
        val gateSession = findInPage("/api/admin/sessions/page", "keyword=$gateTitle") { it["title"]?.asText() == gateTitle }
        assertNotNull(gateSession, "the second conversation should exist")
        val gateSessionId = gateSession!!["sessionId"].asText()
        put("${ADMIN_OWNER_PREFIX}agents/$gateName/sessions/$gateSessionId/root/MEMORY.md", envelope("/MEMORY.md", SESSION_DRAFT))

        val gateId = submit(MERGED, session = gateSessionId, agent = gateName, sources = listOf(mapOf("path" to "MEMORY.md", "content" to SESSION_DRAFT)))["data"].asLong()

        // The same string is both the sandbox's `platform.internalToken` and a credential
        // JwtAuthenticationFilter accepts on every non-internal route, so this is the proposer holding the
        // gate's own key: it may file, and it may read or decide nothing.
        val list = parseBody(exchange(HttpMethod.GET, "/api/admin/memory-drafts", token = INTERNAL_SECRET))
        val read = parseBody(exchange(HttpMethod.GET, "/api/admin/memory-drafts/$gateId", token = INTERNAL_SECRET))
        val approved = approve(gateId, digestOf(gateId), token = INTERNAL_SECRET)
        val refused = reject(gateId, "self-rejected", token = INTERNAL_SECRET)
        listOf(list, read, approved, refused).forEach { answer ->
            assertEquals(401, answer["code"].asInt(), "a proposal's author cannot read or decide it: $answer")
        }
        assertEquals("PENDING", detail(gateId)["status"].asText(), "and four refusals left the candidate undecided")

        // A second account of the same workspace: same tenant, different owner, so the account predicate is
        // what has to answer. It gets the same shape as an unknown id, and no text either way.
        val peerList = assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/memory-drafts?pageSize=100", token = peerToken)))["records"]
        assertTrue(
            peerList.none { it["agentName"].asText().startsWith("it_memory_") },
            "one account's recollections are not another account's queue: $peerList",
        )
        listOf(
            parseBody(exchange(HttpMethod.GET, "/api/admin/memory-drafts/$gateId", token = peerToken)),
            approve(gateId, digestOf(gateId), token = peerToken),
            reject(gateId, "not mine to reject", token = peerToken),
        ).forEach { answer ->
            assertEquals(404, answer["code"].asInt(), "a foreign candidate is answered as a missing one: $answer")
        }
        assertTrue(
            parseBody(exchange(HttpMethod.GET, "/api/admin/memory-drafts/$gateId", token = peerToken))
                .toString()
                .contains(MERGED_MARKER)
                .not(),
            "and the refusal never carries the memory text back",
        )

        // A workspace switch moves the tenant the queue is scoped by, so the row is out of range there too —
        // while the candidate itself stays exactly where it was.
        val switched = parseBody(exchange(HttpMethod.GET, "/api/admin/memory-drafts?pageSize=100", tenantId = foreignTenant))
        assertEquals(200, switched["code"].asInt(), "an empty queue in a workspace one is not in is not an error")
        assertTrue(
            switched["data"]["records"].none { it["agentName"].asText().startsWith("it_memory_") },
            "and no row of this account's shows there: $switched",
        )
        assertEquals("PENDING", detail(gateId)["status"].asText())
    }

    @Test
    @Order(7)
    fun `a merge that names another agent, or no conversation at all, is refused and queues nothing`() {
        val before = queue("pageSize=100").size

        val wrongAgent = submit(MERGED, agent = "ItSomeOtherAgent")
        assertEquals(400, wrongAgent["code"].asInt(), "a proposal naming an agent its conversation did not run is refused")
        assertTrue(
            wrongAgent["message"].asText().contains(agentName) && wrongAgent["message"].asText().contains("ItSomeOtherAgent"),
            "the runtime has to be told which two names disagree: ${wrongAgent["message"]}",
        )

        // A channel conversation has no `sys_user` behind its creator, so there is nobody whose long-term
        // layer this could join and no queue to file it in.
        val noOwner = submit(MERGED, session = "chn-orphan-$tag")
        assertEquals(404, noOwner["code"].asInt(), "an unplaceable session answers 404, got $noOwner")
        assertNull(noOwner["data"], "and it answers no row id")

        assertEquals(before, queue("pageSize=100").size, "a refused proposal must not sit in anybody's queue")
        assertEquals(
            listOf(curatedKey(), sessionLedgerKey()),
            keysUnder(agentPrefix()),
            "nor did either refusal touch the bucket",
        )
    }
}
