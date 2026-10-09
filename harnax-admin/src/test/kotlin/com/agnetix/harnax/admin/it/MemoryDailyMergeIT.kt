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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One agent's day-by-day ledger, written by the approvals of two conversations of that agent.
 *
 * [MemoryDraftFlowIT] follows one candidate through intake, review and approval, and every assertion there is
 * about the conclusion layer and that conversation's own files. Since a candidate now decides on `1 + K`
 * objects — `root/MEMORY.md` and one file per day the merge put material into — four promises are made by the
 * combination of the queue, the store and the page, and nothing existing measures them:
 *
 * 1. **The day lands as its own object with its own envelope key.** `MinioBaseStore.search()` reports the
 *    envelope's `key` field as each listed object's item key, so a day written with `/MEMORY.md` in its
 *    envelope would reach the runtime's reader as a second conclusion file in the same namespace — invisible on
 *    a page that lists object keys, and wrong on every read side. Only the written object settles this.
 * 2. **The day's precondition is its own and is refused by name.** Two conversations merged the same day; the
 *    one that got there second has to lose, and `STALE_BASE` alone would tell its reviewer that the layer moved
 *    when the layer is exactly where the candidate says it is. [staleTarget] is the answer, and it comes from
 *    the bucket.
 * 3. **A refusal by day writes and clears nothing at all.** The candidate covers both layers, so half of it
 *    applying would be memory the owner never agreed to — and the conversation keeps its own files precisely so
 *    the same material can be re-merged against the day as it now stands.
 * 4. **That second attempt puts both conversations' material in one file** and leaves the day's own creation
 *    stamp where the first approval set it: a day is created once and amended after.
 *
 * Everything enters through the wire, as in the flow test: `/api/admin/internal/memory/drafts` on the shared
 * secret for the runtime, `/api/admin/memory-drafts` on a JWT for the owner, `/api/admin/memory` for what the
 * page shows. The target bodies are spelled with the same four keys `AdminApiClient` sends.
 *
 * Deliberately not here: the store semantics a refusal costs (a dropped connection, an unfulfilled `If-Match`)
 * — [MemoryApprovalStoreIT] measures those directly — and the single-candidate lifecycle, the queue's scoping
 * and the clear's three counts, which [MemoryDraftFlowIT] owns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("Memory daily merge - two conversations' same day into one agent ledger file")
class MemoryDailyMergeIT : BaseAdminIT() {

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

        /** The envelope's timestamps for a seeded file, fixed so a written object differs only in text. */
        private const val STAMP = "2026-10-05T13:20:15.148570Z"

        /** The admin token's own namespace: tenant 1, `sys_user.id` 1, which is the owner segment. */
        private const val ADMIN_OWNER_PREFIX = "store/tenants/1/users/1/"

        /** The one day both conversations merge into, which is also the day the stack is measured on. */
        private const val LEDGER_DATE = "2026-10-09"

        /** The route the runtime reports a day by, and the shape intake resolves through the store's own key builder. */
        private const val DAY_PATH = "memory/$LEDGER_DATE.md"

        private const val LINE_A = "- the nightly job runs after the Tuesday deploy"

        private const val LINE_B = "- the release window is 02:00 to 03:00"

        /** The day as the first conversation's merge produced it, and as the second one's did. */
        private const val DAY_A = "# Memory for $LEDGER_DATE\n$LINE_A"

        private const val DAY_B_STALE = "# Memory for $LEDGER_DATE\n$LINE_B"

        /** The day as the second conversation re-merged it: its own line plus the one it read. */
        private const val DAY_BOTH = "# Memory for $LEDGER_DATE\n$LINE_A\n$LINE_B"

        private const val MERGED_A = "# Memory\n- the user likes terse answers"

        private const val MERGED_B = "# Memory\n- the user likes terse answers\n- no trailing summaries"

        /** Each conversation's own two files, as its merge found them. */
        private const val DRAFT_A = "- what conversation A curated for itself"

        private const val LEDGER_A = "- what conversation A wrote that day"

        private const val DRAFT_B = "- what conversation B curated for itself"

        private const val LEDGER_B = "- what conversation B wrote that day"

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

    /** One agent, because the whole point is two conversations of it writing the same day of its ledger. */
    private val agentName = "it_daily_merge_$tag"

    private val titleA = "it_daily_merge_a_$tag"

    private val titleB = "it_daily_merge_b_$tag"

    private var sessionIdA = ""

    private var sessionIdB = ""

    /** The day's own creation stamp, read after the first approval and expected to survive the second. */
    private var dayCreatedAt = ""

    private fun agentPrefix(): String = "${ADMIN_OWNER_PREFIX}agents/$agentName/"

    private fun layerCuratedKey(): String = "${agentPrefix()}root/MEMORY.md"

    private fun layerDayKey(): String = "${agentPrefix()}$DAY_PATH"

    private fun sessionCuratedKey(sessionId: String): String = "${agentPrefix()}sessions/$sessionId/root/MEMORY.md"

    private fun sessionLedgerKey(sessionId: String): String = "${agentPrefix()}sessions/$sessionId/$DAY_PATH"

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

    /** The envelope as the store holds it, so the assertions can speak of `key`, `version` and `created_at`. */
    private fun envelopeAt(objectKey: String): JsonNode = json.readTree(bodyOf(objectKey)!!)

    /** One day target in the shape `AdminApiClient` posts: the day, the version it was read at, and both texts. */
    private fun dayTarget(
        expectedVersion: Long,
        baseText: String?,
        mergedText: String,
        path: String = DAY_PATH,
    ): Map<String, Any?> = mapOf(
        "path" to path,
        "expectedVersion" to expectedVersion,
        "baseText" to baseText,
        "mergedText" to mergedText,
    )

    private fun submit(
        mergedMarkdown: String,
        sessionId: String,
        baseVersion: Long,
        sources: List<Map<String, Any?>>,
        targets: List<Map<String, Any?>>,
        agent: String = agentName,
        baseMarkdown: String? = null,
    ): JsonNode = parseBody(
        exchange(
            HttpMethod.POST,
            "/api/admin/internal/memory/drafts",
            body = mapOf(
                "sessionId" to sessionId,
                "agentName" to agent,
                "mergedMarkdown" to mergedMarkdown,
                "baseMarkdown" to baseMarkdown,
                "baseVersion" to baseVersion,
                "sources" to sources,
                "targets" to targets,
            ),
            token = INTERNAL_SECRET,
        ),
    )

    /** The conversation's own two files, as its merge reported them; both are cleared when the bytes still match. */
    private fun sessionSources(draft: String, ledger: String): List<Map<String, Any?>> = listOf(
        mapOf("path" to "MEMORY.md", "content" to draft),
        mapOf("path" to DAY_PATH, "content" to ledger),
    )

    private fun queueRow(sessionId: String): JsonNode? = assertOk(getJson("/api/admin/memory-drafts?sessionId=$sessionId&pageSize=100"))["records"]
        .toList()
        .firstOrNull { it["agentName"].asText() == agentName }

    private fun detail(id: Long): JsonNode = assertOk(getJson("/api/admin/memory-drafts/$id"))

    /** The queue as the page shows it with no status picked: the candidates still waiting on a decision. */
    private fun pendingRows(): Int = assertOk(getJson("/api/admin/memory-drafts?pageSize=100"))["records"]
        .toList()
        .count { it["agentName"].asText() == agentName }

    private fun approve(
        id: Long,
        expectedDigest: String,
    ): JsonNode = parseBody(
        exchange(
            HttpMethod.POST,
            "/api/admin/memory-drafts/$id/approve",
            body = mapOf("expectedDigest" to expectedDigest),
            token = adminToken(),
        ),
    )

    private fun createSession(title: String, agentId: Long): String {
        assertOk(postJson("/api/admin/sessions", mapOf("title" to title, "agentId" to agentId)))
        val row = findInPage("/api/admin/sessions/page", "keyword=$title") { it["title"]?.asText() == title }
        assertNotNull(row, "the conversation that proposed the merge should exist")
        return row!!["sessionId"].asText()
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

        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName, "IT daily merge agent")))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") { it["name"]?.asText() == agentName }
        assertNotNull(agent, "the agent whose ledger both conversations merge into should exist")
        sessionIdA = createSession(titleA, agent!!["id"].asLong())
        sessionIdB = createSession(titleB, agent["id"].asLong())

        // Both conversations' own layers as their merges left them. Every source below reports these exact
        // bytes, so a cleared file is a file the approval proved it merged away.
        put(sessionCuratedKey(sessionIdA), envelope("/MEMORY.md", DRAFT_A))
        put(sessionLedgerKey(sessionIdA), envelope("/$LEDGER_DATE.md", LEDGER_A))
        put(sessionCuratedKey(sessionIdB), envelope("/MEMORY.md", DRAFT_B))
        put(sessionLedgerKey(sessionIdB), envelope("/$LEDGER_DATE.md", LEDGER_B))
    }

    @Test
    @Order(1)
    fun `approving a candidate writes that day as its own object in the agent's ledger`() {
        val filed = submit(
            mergedMarkdown = MERGED_A,
            sessionId = sessionIdA,
            baseVersion = 0L,
            sources = sessionSources(DRAFT_A, LEDGER_A),
            targets = listOf(dayTarget(expectedVersion = 0L, baseText = null, mergedText = DAY_A)),
        )
        assertTrue(filed["code"].asInt() == 200, "the intake refused a valid merge with one day: $filed")
        val draftId = filed["data"].asLong()

        val row = requireNotNull(queueRow(sessionIdA)) { "the owner's queue should list the candidate" }
        assertEquals(1, row["targetCount"].asInt(), "the list has to say this decision covers two objects")

        val shown = detail(draftId)
        val targets = shown["targets"]
        assertEquals(1, targets.size(), "the day the approval writes is shown with the candidate, not only counted")
        assertEquals(DAY_PATH, targets[0]["path"].asText())
        assertEquals(0L, targets[0]["expectedVersion"].asLong(), "the agent had no such day, so this is a create")
        assertFalse(targets[0].hasNonNull("baseText"), "a day created here is shown as having no text before it")
        assertEquals(DAY_A, targets[0]["mergedText"].asText())

        val decision = assertOk(approve(draftId, shown["contentDigest"].asText()))
        assertEquals("APPROVED", decision["outcome"].asText())
        assertEquals(1L, decision["longTermVersion"].asLong())
        assertEquals(1, decision["dailyTargetsApplied"].asInt(), "one day written, which is what the candidate covered")
        assertEquals(2, decision["clearedSources"].asInt(), "both of the conversation's files still held what the merge read")

        // The object itself: the key the runtime's reader will search under, and the envelope key that tells it
        // this is a day rather than a second conclusion file.
        val written = envelopeAt(layerDayKey())
        assertEquals("/$LEDGER_DATE.md", written["key"].asText(), "the day reports its own item key, not /MEMORY.md")
        assertEquals(1L, written["version"].asLong())
        assertEquals(DAY_A, written["value"]["content"].asText())
        dayCreatedAt = written["value"]["created_at"].asText()
        assertEquals(DAY_A, textAt(layerDayKey()), "and the runtime's own decoder reads the same text back")

        assertEquals(MERGED_A, textAt(layerCuratedKey()), "the conclusion layer is written by the same click")
        assertEquals(
            listOf(layerCuratedKey(), layerDayKey(), sessionCuratedKey(sessionIdB), sessionLedgerKey(sessionIdB)).sorted(),
            keysUnder(agentPrefix()),
            "the agent's ledger holds exactly the one day, and only conversation B still has its own files",
        )
        assertEquals(listOf(layerDayKey()), keysUnder("${agentPrefix()}memory/"))

        // The page a reviewer actually has: the day is a line, and its text is the candidate's verbatim.
        val memory = assertOk(getJson("/api/admin/memory/$agentName"))
        assertEquals(MERGED_A, memory["content"].asText())
        assertEquals(listOf(LEDGER_DATE), memory["entries"].map { it["date"].asText() })
        assertEquals(DAY_A, memory["entries"][0]["content"].asText())
        assertEquals(
            listOf(LEDGER_DATE),
            assertOk(getJson("/api/admin/memory")).first { it["agentId"].asText() == agentName }["dates"].map { it.asText() },
            "the listing names the day without fetching its text",
        )
    }

    @Test
    @Order(2)
    fun `a conversation that merged the day before the first approval wrote it is refused by name and changes nothing`() {
        // B's merge read the conclusion layer after A's approval landed, so that precondition holds; the day it
        // read when it did not exist is the object that moved. That asymmetry is exactly what staleTarget is for
        // — "STALE_BASE" alone would blame a layer this candidate describes correctly.
        val filed = submit(
            mergedMarkdown = MERGED_B,
            sessionId = sessionIdB,
            baseVersion = 1L,
            sources = sessionSources(DRAFT_B, LEDGER_B),
            targets = listOf(dayTarget(expectedVersion = 0L, baseText = null, mergedText = DAY_B_STALE)),
        )
        val draftId = filed["data"].asLong()
        assertTrue(draftId > 0, "the second conversation files its own candidate: $filed")

        val decision = assertOk(approve(draftId, detail(draftId)["contentDigest"].asText()))
        assertEquals("STALE_BASE", decision["outcome"].asText())
        assertEquals(DAY_PATH, decision["staleTarget"].asText(), "the refusal has to name the day that moved")
        assertEquals(1L, decision["currentBaseVersion"].asLong(), "and the layer it did not blame stays where it is")

        // Nothing at all applied: the day is still A's, the layer is still A's, and B keeps both of its files so
        // its material can be re-merged against the day as it now stands.
        assertEquals(DAY_A, textAt(layerDayKey()), "the loser did not overwrite the day")
        assertEquals(1L, envelopeAt(layerDayKey())["version"].asLong())
        assertEquals(MERGED_A, textAt(layerCuratedKey()))
        assertEquals("PENDING", detail(draftId)["status"].asText(), "and the candidate is still waiting to be re-read")
        assertEquals(
            listOf(layerCuratedKey(), layerDayKey(), sessionCuratedKey(sessionIdB), sessionLedgerKey(sessionIdB)).sorted(),
            keysUnder(agentPrefix()),
            "a refusal by day cleared none of the conversation's own layer either",
        )
    }

    @Test
    @Order(3)
    fun `the refused conversation re-merges against the day as it stands and both conversations' material lands in one file`() {
        val pendingId = requireNotNull(queueRow(sessionIdB)) { "B's candidate is still in the open queue" }["id"].asLong()
        val resubmitted = submit(
            mergedMarkdown = MERGED_B,
            sessionId = sessionIdB,
            baseVersion = 1L,
            sources = sessionSources(DRAFT_B, LEDGER_B),
            targets = listOf(dayTarget(expectedVersion = 1L, baseText = DAY_A, mergedText = DAY_BOTH)),
        )
        assertEquals(pendingId, resubmitted["data"].asLong(), "one undecided conversation stays one candidate")

        val shown = detail(pendingId)
        assertEquals(DAY_A, shown["targets"][0]["baseText"].asText(), "the day as the merge read it is what the screen diffs against")
        val decision = assertOk(approve(pendingId, shown["contentDigest"].asText()))
        assertEquals("APPROVED", decision["outcome"].asText())
        assertEquals(2L, decision["longTermVersion"].asLong())
        assertEquals(1, decision["dailyTargetsApplied"].asInt(), "the day this candidate names is written")
        assertEquals(2, decision["clearedSources"].asInt())

        val written = envelopeAt(layerDayKey())
        assertEquals(DAY_BOTH, written["value"]["content"].asText(), "one file holds both conversations' lines")
        assertEquals(DAY_BOTH, textAt(layerDayKey()), "and the runtime's own decoder reads all of it back")
        assertTrue(
            LINE_A in textAt(layerDayKey())!!,
            "the line the first approval put in that day survived the second one's rewrite",
        )
        assertEquals(2L, written["version"].asLong())
        assertEquals("/$LEDGER_DATE.md", written["key"].asText(), "amending a day does not move its item key")
        assertEquals(
            dayCreatedAt,
            written["value"]["created_at"].asText(),
            "the day was created when the first approval wrote it, not when this one amended it",
        )

        assertEquals(MERGED_B, textAt(layerCuratedKey()))
        assertEquals(
            listOf(layerCuratedKey(), layerDayKey()).sorted(),
            keysUnder(agentPrefix()),
            "both conversations' own files are gone, and the ledger is still exactly one object",
        )
        val memory = assertOk(getJson("/api/admin/memory/$agentName"))
        assertEquals(listOf(LEDGER_DATE), memory["entries"].map { it["date"].asText() })
        assertEquals(DAY_BOTH, memory["entries"][0]["content"].asText())
    }

    @Test
    @Order(4)
    fun `a candidate naming a day that is not a day, an empty day, or the same day twice never reaches a queue`() {
        val rows = pendingRows()
        val bucket = keysUnder(agentPrefix())

        fun refused(answer: JsonNode, day: String) {
            assertEquals(400, answer["code"].asInt(), "a target no approval could apply is an error, not a queued decision: $answer")
            assertTrue(answer["message"].asText().contains(day), "the refusal names the day it is refusing: ${answer["message"]}")
        }

        val sources = sessionSources(DRAFT_A, LEDGER_A)
        refused(
            submit(MERGED_A, sessionIdA, 2L, sources, listOf(dayTarget(0L, null, DAY_A, path = "memory/notes.md"))),
            "memory/notes.md",
        )
        refused(
            submit(MERGED_A, sessionIdA, 2L, sources, listOf(dayTarget(0L, null, "   "))),
            DAY_PATH,
        )
        refused(
            submit(MERGED_A, sessionIdA, 2L, sources, listOf(dayTarget(0L, null, DAY_A), dayTarget(1L, DAY_A, DAY_BOTH))),
            DAY_PATH,
        )

        assertEquals(rows, pendingRows(), "and none of the three queued a row of its own")
        assertEquals(bucket, keysUnder(agentPrefix()), "nor did any of them reach the bucket")
    }
}
