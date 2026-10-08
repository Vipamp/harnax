package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.admin.dto.MemoryDraftSource
import com.agnetix.harnax.admin.exception.BizException
import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.Result
import io.minio.errors.ErrorResponseException
import io.minio.messages.Item
import okhttp3.Headers.Companion.toHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.springframework.beans.factory.ObjectProvider
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the store is asked for, and nothing else.
 *
 * The memory bucket is shared: every tenant and every user of the deployment writes into the same
 * `harnax-store`. So the assertions here are not about shapes but about the exact keys handed to a faked
 * `MinioClient` — one key outside the caller's own `store/tenants/<t>/users/<u>/` prefix is another person's
 * memory read or deleted, and a delete that swallowed an error would report a cleaned owner whose memory is
 * still sitting in the bucket.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryStoreGateway - the caller's own keys only")
class MemoryStoreGatewayTest {

    private val tenantId = 4L
    private val userId = "7"
    private val ownerPrefix = "store/tenants/4/users/7/"

    @Mock
    private lateinit var minioClient: MinioClient

    @Mock
    private lateinit var clientProvider: ObjectProvider<MinioClient>

    @Mock
    private lateinit var propertiesProvider: ObjectProvider<AdminMinioProperties>

    private val objectMapper = ObjectMapper()

    private lateinit var gateway: MemoryStoreGateway

    @BeforeEach
    fun setUp() {
        `when`(clientProvider.ifAvailable).thenReturn(minioClient)
        `when`(propertiesProvider.ifAvailable).thenReturn(
            AdminMinioProperties().apply {
                enabled = true
                storeBucket = "harnax-store"
                storePrefix = "store/"
            },
        )
        gateway = MemoryStoreGateway(clientProvider, propertiesProvider, objectMapper)
    }

    /** One listing row: the key the store holds and when it was written. */
    private fun stored(
        key: String,
        modified: ZonedDateTime = ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, ZoneOffset.UTC),
    ): Result<Item> = Result(
        mock(Item::class.java).apply {
            `when`(objectName()).thenReturn(key)
            `when`(lastModified()).thenReturn(modified)
        },
    )

    private fun bucket(vararg entries: Result<Item>) {
        `when`(minioClient.listObjects(any<ListObjectsArgs>())).thenReturn(entries.toList())
    }

    /** Different rows per requested prefix, for a sweep that has to address more than one owner namespace. */
    private fun bucketByPrefix(vararg entriesByPrefix: Pair<String, List<Result<Item>>>) {
        val byPrefix = entriesByPrefix.toMap()
        `when`(minioClient.listObjects(any<ListObjectsArgs>())).thenAnswer { invocation ->
            byPrefix[invocation.getArgument<ListObjectsArgs>(0).prefix() ?: ""] ?: emptyList<Result<Item>>()
        }
    }

    /** The envelope `MinioBaseStore` writes, with [content] at `value.content`. */
    private fun wrapper(
        content: String,
        key: String = "/MEMORY.md",
        modifiedAt: String? = "2026-10-05T12:00:00Z",
    ): String {
        val embedded = if (modifiedAt == null) "" else ",\"modified_at\":\"$modifiedAt\""
        return """{"key":${objectMapper.writeValueAsString(key)},"value":{"content":${
            objectMapper.writeValueAsString(
                content,
            )
        }$embedded},"version":3}"""
    }

    /** Serves the envelope [wrapper] builds, with [content] at `value.content`. */
    private fun serveWrapper(
        content: String,
        modifiedAt: String? = "2026-10-05T12:00:00Z",
    ) = serveBody(wrapper(content, modifiedAt = modifiedAt))

    /**
     * Serves one body per requested object key.
     *
     * An object stream answers once, so a single stubbed stream cannot back both memory routes of one agent:
     * the gateway's second read would hit an end of file instead of that object's own text.
     */
    private fun serveObjects(vararg bodiesByObject: Pair<String, String>) {
        val byObject = bodiesByObject.toMap()
        `when`(minioClient.getObject(any<GetObjectArgs>())).thenAnswer { invocation ->
            val requested = invocation.getArgument<GetObjectArgs>(0).`object`()
            objectStream(byObject[requested] ?: error("nothing stubbed for object '$requested'"))
        }
    }

    private fun serveBody(body: String) {
        // Built before the stubbing opens: creating and stubbing a mock inside a `thenReturn(...)` argument
        // nests one stubbing in another and Mockito answers with UnfinishedStubbing.
        val response = objectStream(body)
        `when`(minioClient.getObject(any<GetObjectArgs>())).thenReturn(response)
    }

    /**
     * A `GetObjectResponse` that hands out [body] once, the way an object stream does.
     *
     * Both read entry points are stubbed because a JDK reader may bulk-read or peek a single byte depending
     * on how its buffer fills; the position is shared, so either path reads the same bytes.
     *
     * [etag] is what the server answers for this exact body, and the write path refuses to replace an object
     * that does not carry one — a read-side stub without it stays a read-side stub.
     */
    private fun objectStream(
        body: String,
        etag: String? = null,
    ): GetObjectResponse {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val position = AtomicInteger(0)
        return mock(GetObjectResponse::class.java).apply {
            `when`(headers()).thenReturn(
                (if (etag == null) emptyMap() else mapOf("ETag" to etag)).toHeaders(),
            )
            `when`(read()).thenAnswer {
                val at = position.get()
                if (at < bytes.size) {
                    position.incrementAndGet()
                    bytes[at].toInt() and 0xff
                } else {
                    -1
                }
            }
            `when`(read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer { invocation ->
                val buffer = invocation.getArgument<ByteArray>(0)
                val offset = invocation.getArgument<Int>(1)
                val length = invocation.getArgument<Int>(2)
                val at = position.get()
                val remaining = bytes.size - at
                if (remaining <= 0) {
                    -1
                } else {
                    val taken = minOf(length, remaining)
                    System.arraycopy(bytes, at, buffer, offset, taken)
                    position.addAndGet(taken)
                    taken
                }
            }
        }
    }

    /**
     * A minio 404/`NoSuchKey`, the answer an object store gives for a key that is not there.
     *
     * Each mock is stubbed on its own line: beginning a nested stubbing while the outer one is still
     * unfinished is what Mockito reports as `UnfinishedStubbing`, not as a useful failure message.
     */
    private fun missingObject(): ErrorResponseException {
        val http = mock(okhttp3.Response::class.java)
        `when`(http.code).thenReturn(404)
        val error = mock(io.minio.messages.ErrorResponse::class.java)
        `when`(error.code()).thenReturn("NoSuchKey")
        val failure = mock(ErrorResponseException::class.java)
        `when`(failure.response()).thenReturn(http)
        `when`(failure.errorResponse()).thenReturn(error)
        return failure
    }

    /**
     * A stand-in bucket: [objects] holds the bodies by key, a read of a key that is not in it answers 404, and
     * a delete really takes the key away.
     *
     * The delete is what makes the clear-side cases mean something: the gateway reads the object back after
     * removing it, and a stub that kept answering the old body would turn a working delete into a fault and a
     * broken one into a success. With [honoursDelete] false the bucket answers a remove and keeps the object,
     * which is the store behaviour that read-back exists to catch.
     */
    private fun fakeBucket(
        objects: MutableMap<String, String>,
        honoursDelete: Boolean = true,
    ) {
        `when`(minioClient.getObject(any<GetObjectArgs>())).thenAnswer { invocation ->
            val requested = invocation.getArgument<GetObjectArgs>(0).`object`()
            val body = objects[requested] ?: throw missingObject()
            objectStream(body, etag = "\"${requested.hashCode()}\"")
        }
        `when`(minioClient.removeObject(any<RemoveObjectArgs>())).thenAnswer { invocation ->
            if (honoursDelete) objects.remove(invocation.getArgument<RemoveObjectArgs>(0).`object`())
            null
        }
    }

    /** Every `(bucket, prefix)` the gateway asked to list, oldest call first. */
    private fun listedPrefixes(): List<Pair<String, String?>> = argumentCaptor<ListObjectsArgs>().apply {
        verify(minioClient, atLeastOnce()).listObjects(capture())
    }.allValues.map { it.bucket() to it.prefix() }

    /** Every `(bucket, object key)` the gateway asked to delete, oldest call first. */
    private fun deletedKeys(): List<Pair<String, String>> = argumentCaptor<RemoveObjectArgs>().apply {
        verify(minioClient, atLeastOnce()).removeObject(capture())
    }.allValues.map { it.bucket() to it.`object`() }

    @Nested
    @DisplayName("Listing the caller's agents")
    inner class Listing {

        @Test
        fun `the listing addresses the caller's own owner prefix and the store bucket`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            serveWrapper("- the user likes terse answers")

            gateway.listAgents(tenantId, userId)

            assertEquals(listOf("harnax-store" to ownerPrefix), listedPrefixes())
        }

        @Test
        fun `an agent is reported with its curated text its storage time and its ledger dates`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/memory/2026-10-04.md"),
                stored("${ownerPrefix}agents/Research/memory/2026-10-05.md"),
            )
            serveWrapper("- the user likes terse answers")

            val agents = gateway.listAgents(tenantId, userId)

            assertEquals(1, agents.size)
            val agent = agents.first()
            assertEquals("Research", agent.agentId)
            assertEquals("- the user likes terse answers", agent.content)
            assertEquals("2026-10-05T12:00:00Z", agent.lastModified)
            assertEquals(listOf("2026-10-04", "2026-10-05"), agent.dates)
        }

        @Test
        fun `two agents of one owner are two rows`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Ops/memory/2026-10-05.md"),
            )
            serveWrapper("- anything")

            val agents = gateway.listAgents(tenantId, userId)

            assertEquals(setOf("Research", "Ops"), agents.map { it.agentId }.toSet())
        }

        @Test
        fun `an agent with only ledgers has empty curated text`() {
            bucket(stored("${ownerPrefix}agents/Ops/memory/2026-10-05.md"))
            serveWrapper("- a ledger line")

            val agent = gateway.listAgents(tenantId, userId).first()

            assertEquals("", agent.content)
            assertNull(agent.lastModified)
            assertEquals(listOf("2026-10-05"), agent.dates)
        }

        /** The bucket is shared and the prefix filter is server-side; a stray key must not become a row. */
        @Test
        fun `a key outside the caller's prefix is not listed as the caller's memory`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("store/tenants/5/users/8/agents/Research/root/MEMORY.md"),
                stored("store/tenants/4/users/7/sessions/sess-1/agent_state.json"),
            )
            serveWrapper("- mine")

            val agents = gateway.listAgents(tenantId, userId)

            assertEquals(1, agents.size)
            assertEquals("Research", agents.first().agentId)
        }

        @Test
        fun `an owner with nothing in the bucket is an empty list and not an error`() {
            bucket()

            assertTrue(gateway.listAgents(tenantId, userId).isEmpty())
        }

        /**
         * The page answers for the long-term layer only.
         *
         * A conversation's `root/MEMORY.md` is a draft that has not been merged yet and its ledger has not
         * earned a place in the curated memory, so counting either would tell the owner that a new
         * conversation will be told something it will not. The dates carry this case rather than the text:
         * the listing reads only one curated object, and which one that is depends on the filter. That the
         * same session keys still go on a delete is the case in [Deleting], which is also what proves they
         * decoded here at all instead of being dropped as unparseable.
         */
        @Test
        fun `a conversation's own layer is not shown as the owner's memory`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/memory/2026-10-05.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/memory/2026-10-06.md"),
            )
            serveWrapper("- the curated layer")

            val row = gateway.listAgents(tenantId, userId).single()

            assertEquals("- the curated layer", row.content)
            assertEquals(listOf("2026-10-05"), row.dates, "the draft's day does not join the owner's ledger dates")
        }

        /**
         * How many conversations of this agent still hold memory that has not been merged.
         *
         * The long-term layer is the only thing a new conversation is told, so an owner who has just switched
         * an agent to two layers sees one row per agent and concludes the recent conversations were forgotten.
         * The count is the other half of that answer, and it counts conversations rather than objects: one
         * conversation's draft and its ledger are the one merge that is pending, and reporting them as two
         * would make the same agent look twice as far behind.
         */
        @Test
        fun `the pending count is the conversations whose own layer still holds memory`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/memory/2026-10-06.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-B/memory/2026-10-07.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-C/root/MEMORY.md"),
            )
            serveWrapper("- the curated layer")

            val row = gateway.listAgents(tenantId, userId).single()

            assertEquals(3, row.pendingSessionLayers, "the draft and the ledger of sess-A are one pending merge")
        }

        /**
         * What a conversation layer looks like once its memory has been merged is not "nothing pending".
         *
         * The consolidation pass leaves its own state object behind and upstream retires an old day into
         * `archive/`; neither is material waiting for the long-term layer, and the merge deliberately reads
         * neither. Counting them would keep an agent's pending figure above zero forever, which is the answer
         * an owner cannot do anything with.
         */
        @Test
        fun `the passes state object and an archived day are not counted as unmerged memory`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/memory/.consolidation_state"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/memory/archive/2026-08-01.md"),
            )
            serveWrapper("- the curated layer")

            assertEquals(0, gateway.listAgents(tenantId, userId).single().pendingSessionLayers)
        }

        @Test
        fun `each agent's pending count covers only its own conversations`() {
            bucket(
                stored("${ownerPrefix}agents/Research/sessions/sess-A/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Ops/sessions/sess-B/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Ops/sessions/sess-C/memory/2026-10-08.md"),
            )
            serveWrapper("- anything")

            val pending = gateway.listAgents(tenantId, userId).associate { it.agentId to it.pendingSessionLayers }

            assertEquals(mapOf("Research" to 1, "Ops" to 2), pending)
        }

        /** "No memory" and "the store did not answer" are different answers, and only one of them is a 200. */
        @Test
        fun `a store that cannot list is thrown at rather than reported as empty`() {
            `when`(minioClient.listObjects(any<ListObjectsArgs>())).thenThrow(RuntimeException("connection refused"))

            val failure = assertThrows(BizException::class.java) { gateway.listAgents(tenantId, userId) }

            assertEquals(503, failure.code)
            assertTrue(failure.message!!.contains("could not be listed"), failure.message ?: "")
        }
    }

    @Nested
    @DisplayName("Reading one agent")
    inner class Reading {

        @Test
        fun `the curated text and every daily entry come back from value content`() {
            val curatedKey = "${ownerPrefix}agents/Research/root/MEMORY.md"
            val dailyKey = "${ownerPrefix}agents/Research/memory/2026-10-05.md"
            bucket(stored(curatedKey), stored(dailyKey))
            // Two different bodies: one text for both routes would pass even if the gateway read MEMORY.md
            // again and reported it as the day's ledger.
            serveObjects(
                curatedKey to wrapper("- the user likes terse answers"),
                dailyKey to wrapper("## 14:02\n- asked about the store layout", key = "/2026-10-05.md"),
            )

            val detail = gateway.readAgent(tenantId, userId, "Research")

            assertEquals("Research", detail?.agentId)
            assertEquals("- the user likes terse answers", detail?.content)
            assertEquals("2026-10-05T12:00:00Z", detail?.lastModified)
            assertEquals(listOf("2026-10-05"), detail?.entries?.map { it.date })
            assertEquals("## 14:02\n- asked about the store layout", detail?.entries?.first()?.content)
        }

        @Test
        fun `reading still only ever addresses the caller's own prefix`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            serveWrapper("- mine")

            gateway.readAgent(tenantId, userId, "Research")

            assertEquals(listOf("harnax-store" to ownerPrefix), listedPrefixes())
        }

        @Test
        fun `an agent the caller has no memory of is null`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            serveWrapper("- mine")

            assertNull(gateway.readAgent(tenantId, userId, "Other"))
        }

        @Test
        fun `an object that is not a store wrapper is shown as empty rather than as JSON`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            serveBody("this is not a wrapper at all")

            assertEquals("", gateway.readAgent(tenantId, userId, "Research")?.content)
        }

        /**
         * Rule 3, on the read leg: the listing said this object is there, so a refusal is the store failing,
         * not an owner having nothing. Shown as an empty `MEMORY.md` it is indistinguishable from a curated
         * layer that was genuinely cleared, and the owner stops looking at a live bucket.
         */
        @Test
        fun `an object the store refused to return is a fault and not an empty memory`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            `when`(minioClient.getObject(any<GetObjectArgs>())).thenThrow(RuntimeException("connection reset"))

            val failure = assertThrows(BizException::class.java) { gateway.readAgent(tenantId, userId, "Research") }

            assertEquals(503, failure.code)
            assertTrue(failure.message!!.contains("could not be read"), failure.message ?: "")
        }

        /** Gone between the listing and the read is a real state — a concurrent delete, or the sweep of the same owner. */
        @Test
        fun `an object that went away between the listing and the read is empty, not a fault`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            val missing = missingObject()
            `when`(minioClient.getObject(any<GetObjectArgs>())).thenThrow(missing)

            val detail = gateway.readAgent(tenantId, userId, "Research")

            assertEquals("", detail?.content)
        }

        /**
         * The path variable is the traversal boundary, and it is refused before a prefix is built, so a
         * caller cannot make the gateway ask for another owner's namespace at all.
         */
        @Test
        fun `an agent id that could name a path never reaches the store`() {
            listOf("../", "a/b", "..", "agents/Ops/root", "..\\..\\x", "Research/../../other").forEach { candidate ->
                reset(minioClient)

                val failure = assertThrows(BizException::class.java) {
                    gateway.readAgent(tenantId, userId, candidate)
                }

                assertEquals(400, failure.code, "'$candidate' should have been refused")
                verify(minioClient, never()).listObjects(any<ListObjectsArgs>())
            }
        }
    }

    @Nested
    @DisplayName("Deleting one agent")
    inner class Deleting {

        @Test
        fun `both routes of that one agent go and nothing else`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/memory/2026-10-04.md"),
                stored("${ownerPrefix}agents/Ops/root/MEMORY.md"),
            )

            val removed = gateway.deleteAgent(tenantId, userId, "Research")

            assertEquals(2, removed)
            assertEquals(
                listOf(
                    "harnax-store" to "${ownerPrefix}agents/Research/root/MEMORY.md",
                    "harnax-store" to "${ownerPrefix}agents/Research/memory/2026-10-04.md",
                ),
                deletedKeys(),
            )
        }

        /**
         * The half the page does not show, the delete still has to take.
         *
         * The listing hides a conversation's own layer, so an agent deleted from that page would otherwise
         * leave its draft and its ledgers in the bucket under a name the owner can no longer see. The delete
         * asks for the agent's prefix instead of picking objects out of a decoded listing, and the two session
         * keys going here is what shows that one prefix really reaches both routes of both layers.
         */
        @Test
        fun `a conversation's own layer goes with the agent although the page hides it`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/memory/2026-10-04.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/sessions/sess-A/memory/2026-10-06.md"),
            )

            assertEquals(4, gateway.deleteAgent(tenantId, userId, "Research"))
            assertEquals(
                listOf(
                    "${ownerPrefix}agents/Research/root/MEMORY.md",
                    "${ownerPrefix}agents/Research/memory/2026-10-04.md",
                    "${ownerPrefix}agents/Research/sessions/sess-A/root/MEMORY.md",
                    "${ownerPrefix}agents/Research/sessions/sess-A/memory/2026-10-06.md",
                ),
                deletedKeys().map { it.second },
            )
        }

        @Test
        fun `a delete asks for the caller's prefix and every key it removes stays inside it`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))

            gateway.deleteAgent(tenantId, userId, "Research")

            // The agent's own prefix rather than the owner's: what goes is decided by what the bucket lists
            // under that agent, not by this class picking agents out of the names it listed.
            assertEquals(listOf("harnax-store" to "${ownerPrefix}agents/Research/"), listedPrefixes())
            deletedKeys().forEach { (bucket, key) ->
                assertEquals("harnax-store", bucket)
                assertTrue(key.startsWith(ownerPrefix), "$key escapes $ownerPrefix")
            }
        }

        @Test
        fun `an agent id that could name a path is refused before anything is listed or deleted`() {
            val failure = assertThrows(BizException::class.java) {
                gateway.deleteAgent(tenantId, userId, "../../tenants/5/users/8/agents/Ops")
            }

            assertEquals(400, failure.code)
            verify(minioClient, never()).listObjects(any<ListObjectsArgs>())
            verify(minioClient, never()).removeObject(any<RemoveObjectArgs>())
        }

        @Test
        fun `an agent with no memory deletes nothing and is not an error`() {
            bucket(stored("${ownerPrefix}agents/Ops/root/MEMORY.md"))

            assertEquals(0, gateway.deleteAgent(tenantId, userId, "Research"))
            verify(minioClient, never()).removeObject(any<RemoveObjectArgs>())
        }

        @Test
        fun `an object that cannot be removed is reported instead of swallowed`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/memory/2026-10-04.md"),
            )
            doNothing().doThrow(RuntimeException("access denied"))
                .`when`(minioClient).removeObject(any<RemoveObjectArgs>())

            val failure = assertThrows(BizException::class.java) {
                gateway.deleteAgent(tenantId, userId, "Research")
            }

            assertEquals(503, failure.code)
            // The half that did go is named, so an operator retrying knows how much is left.
            assertTrue(failure.message!!.contains("1 of 2"), failure.message ?: "")
        }
    }

    @Nested
    @DisplayName("The whole-owner sweep")
    inner class OwnerSweep {

        @Test
        fun `every agent of the user goes and the prefix never leaves that user`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Research/memory/2026-10-05.md"),
                stored("${ownerPrefix}agents/Ops/root/MEMORY.md"),
            )

            val removed = gateway.deleteUser(listOf(tenantId), userId)

            assertEquals(3, removed)
            assertEquals(listOf("harnax-store" to ownerPrefix), listedPrefixes())
            deletedKeys().forEach { (_, key) -> assertTrue(key.startsWith(ownerPrefix), key) }
        }

        /**
         * An agent whose own name contains a slash writes `agents/<first>/<second>/root/MEMORY.md`, a key no
         * decoder here can place and no agent-scoped endpoint can name — so the page cannot show it either.
         * The sweep asks the bucket for the owner's prefix rather than for objects it decoded, which is what
         * keeps an owner's memory from outliving them under a name nothing can point at.
         */
        @Test
        fun `a key no decoder can place still goes with its owner`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("${ownerPrefix}agents/Deep/Nested/root/MEMORY.md"),
            )

            assertEquals(2, gateway.deleteUser(listOf(tenantId), userId))
            assertTrue(
                deletedKeys().map { it.second }.contains("${ownerPrefix}agents/Deep/Nested/root/MEMORY.md"),
                "the key the page cannot name is still the owner's memory: ${deletedKeys()}",
            )
        }

        /**
         * The bucket is keyed on the tenant of the agent that was talked to, not on the row's home tenant, so
         * an owner who is a member of two of them has memory under both. Sweeping only `sys_user.tenant_id`
         * leaves the other one behind and reports the account as cleaned.
         */
        @Test
        fun `every membership named by the caller gets its own prefix`() {
            bucketByPrefix(
                ownerPrefix to listOf(stored("${ownerPrefix}agents/Research/root/MEMORY.md")),
                "store/tenants/5/users/7/" to listOf(stored("store/tenants/5/users/7/agents/Ops/root/MEMORY.md")),
            )

            val removed = gateway.deleteUser(listOf(4L, 5L), userId)

            assertEquals(2, removed)
            assertEquals(
                listOf("harnax-store" to ownerPrefix, "harnax-store" to "store/tenants/5/users/7/"),
                listedPrefixes(),
            )
        }

        /** A membership listed twice is one prefix, so a repeated tenant cannot delete the same key twice. */
        @Test
        fun `the same tenant named twice is swept once`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))

            val removed = gateway.deleteUser(listOf(tenantId, tenantId), userId)

            assertEquals(1, removed)
            assertEquals(listOf("harnax-store" to ownerPrefix), listedPrefixes())
        }

        /** The sweep of an owner with no membership at all addresses nothing, rather than a guessed workspace. */
        @Test
        fun `no memberships delete nothing`() {
            assertEquals(0, gateway.deleteUser(emptyList(), userId))
            verify(minioClient, never()).listObjects(any<ListObjectsArgs>())
            verify(minioClient, never()).removeObject(any<RemoveObjectArgs>())
        }

        /** A listing that returns a foreign key must not get that key deleted, prefix or no prefix. */
        @Test
        fun `a foreign key in the listing is never removed`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("store/tenants/5/users/8/agents/Research/root/MEMORY.md"),
            )

            assertEquals(1, gateway.deleteUser(listOf(tenantId), userId))
            assertEquals(listOf("harnax-store" to "${ownerPrefix}agents/Research/root/MEMORY.md"), deletedKeys())
        }

        @Test
        fun `a sweep that cannot delete throws so the account deletion can roll back`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            doThrow(RuntimeException("access denied")).`when`(minioClient).removeObject(any<RemoveObjectArgs>())

            assertThrows(BizException::class.java) { gateway.deleteUser(listOf(tenantId), userId) }
        }

        @Test
        fun `another user id is only ever another owner prefix`() {
            bucket()

            gateway.deleteUser(listOf(tenantId), "8")

            assertEquals(listOf("harnax-store" to "store/tenants/4/users/8/"), listedPrefixes())
        }
    }

    /**
     * `harnax.memory.tenant-scoped` decides the key on the writer's side, and this side has to follow the same
     * env var: with it off the runtime writes `store/users/<uid>/…`, and a gateway that kept prefixing the
     * tenant would list an empty area and tell a live owner they have no memory.
     */
    @Nested
    @DisplayName("A deployment with unscoped memory keys")
    inner class UnscopedTenantKeying {

        private fun unscopedGateway() = MemoryStoreGateway(clientProvider, propertiesProvider, objectMapper, false)

        @Test
        fun `the listing drops the tenant from the owner prefix`() {
            bucket(stored("store/users/7/agents/Research/root/MEMORY.md"))
            serveWrapper("- mine")

            val agents = unscopedGateway().listAgents(tenantId, userId)

            assertEquals(listOf("harnax-store" to "store/users/7/"), listedPrefixes())
            assertEquals(listOf("Research"), agents.map { it.agentId })
        }

        @Test
        fun `the sweep of an owner is one prefix, whichever tenant the agent lived in`() {
            bucket(stored("store/users/7/agents/Research/root/MEMORY.md"))

            val removed = unscopedGateway().deleteUser(listOf(4L, 5L), userId)

            assertEquals(1, removed)
            assertEquals(listOf("harnax-store" to "store/users/7/"), listedPrefixes())
        }
    }

    @Nested
    @DisplayName("A deployment with no store")
    inner class Unavailable {

        @Test
        fun `no MinIO client is an explicit answer rather than an empty memory`() {
            `when`(clientProvider.ifAvailable).thenReturn(null)

            assertFalse(gateway.isAvailable())
            val failure = assertThrows(BizException::class.java) { gateway.listAgents(tenantId, userId) }
            assertEquals(503, failure.code)
        }

        @Test
        fun `a blank store bucket is refused before any key is built`() {
            `when`(propertiesProvider.ifAvailable).thenReturn(AdminMinioProperties().apply { storeBucket = "" })

            val failure = assertThrows(BizException::class.java) { gateway.deleteUser(listOf(tenantId), userId) }

            assertEquals(503, failure.code)
            verify(minioClient, never()).removeObject(any<RemoveObjectArgs>())
        }
    }

    /**
     * The write half: an approval replaces the owner's curated layer and clears the conversation's own files.
     *
     * What is pinned here is which key each write addresses and which precondition it asks the store to
     * enforce, because a mocked client cannot enforce anything — it can only be shown what was asked of it.
     * The server's own answers (a 412 on a real conditional write, an object that survives a real delete, the
     * ETag a real GET carries) are measured against a live MinIO in `MemoryApprovalStoreIT`.
     */
    @Nested
    @DisplayName("Applying what a reviewer approved")
    inner class ApprovalWrites {

        private val curatedKey = "${ownerPrefix}agents/Research/root/MEMORY.md"

        private fun sessionKey(
            route: String,
            item: String,
        ) = "${ownerPrefix}agents/Research/sessions/sess-1/$route/$item"

        /** The store refusing a conditional write, because the object moved between this read and this put. */
        private fun preconditionFailed(): ErrorResponseException {
            val http = mock(okhttp3.Response::class.java)
            `when`(http.code).thenReturn(412)
            val error = mock(io.minio.messages.ErrorResponse::class.java)
            `when`(error.code()).thenReturn("PreconditionFailed")
            val failure = mock(ErrorResponseException::class.java)
            `when`(failure.response()).thenReturn(http)
            `when`(failure.errorResponse()).thenReturn(error)
            return failure
        }

        /** Every object the gateway asked to write: its key, its conditional headers, and its body bytes. */
        private fun writes(): List<Triple<String, Map<String, String>, String>> = argumentCaptor<PutObjectArgs>().apply {
            verify(minioClient, atLeastOnce()).putObject(capture())
        }.allValues.map { args ->
            Triple(
                args.`object`(),
                // The version precondition travels as an extra header, which is how MinioBaseStore sends it too.
                args.extraHeaders().entries().associate { it.key to it.value },
                args.stream().readBytes().toString(StandardCharsets.UTF_8),
            )
        }

        @Test
        fun `a first approval creates the layer conditionally on its absence`() {
            val missing = missingObject()
            `when`(minioClient.getObject(any<GetObjectArgs>())).thenThrow(missing)

            assertTrue(gateway.writeCuratedIfVersion(tenantId, userId, "Research", 0L, "# Memory\n- approved"))

            val (key, headers, body) = writes().single()
            assertEquals(curatedKey, key, "the write addresses the one curated key of this owner and agent")
            assertEquals("*", headers["If-None-Match"], "a create is refused by the store if anybody got there first")
            assertEquals(
                "close",
                headers["Connection"],
                "a refusal must cost this write's own connection, not the next approval's write",
            )
            assertNull(headers["If-Match"])
            val envelope = objectMapper.readTree(body)
            assertEquals("/MEMORY.md", envelope.path("key").asText())
            assertEquals(1L, envelope.path("version").asLong(), "an absent layer becomes version 1")
            assertEquals("# Memory\n- approved", envelope.path("value").path("content").asText())
            // The runtime reads this object back through MinioBaseStore, so the document is written as that
            // class writes it — field order included, because the pinned cross-check literal has one.
            val positions = listOf("created_at", "encoding", "modified_at", "content").map { body.indexOf("\"$it\"") }
            assertTrue(positions.all { it >= 0 }, "every field the envelope contract names is present: $body")
            assertEquals(positions.sorted(), positions, "value fields must keep the writer's order")
            assertTrue(body.indexOf("\"key\"") < positions.first())
            assertTrue(body.indexOf("\"version\"") > positions.last())
        }

        @Test
        fun `a replacement keeps the layer's own creation stamp and bumps its version`() {
            val objects = mutableMapOf(
                curatedKey to """
                    {"key":"/MEMORY.md","value":{"created_at":"2026-01-01T00:00:00Z","encoding":"utf-8",
                    "modified_at":"2026-01-02T00:00:00Z","content":"- old"},"version":3}
                """.trim().replace("\n", ""),
            )
            fakeBucket(objects)

            assertTrue(gateway.writeCuratedIfVersion(tenantId, userId, "Research", 3L, "- new"))

            val (_, headers, body) = writes().single()
            assertEquals("\"${curatedKey.hashCode()}\"", headers["If-Match"], "the write is conditioned on the bytes the read just returned")
            assertEquals("close", headers["Connection"], "and it is not the write after this one that pays for a refusal")
            val envelope = objectMapper.readTree(body)
            assertEquals(
                "2026-01-01T00:00:00Z",
                envelope.path("value").path("created_at").asText(),
                "the file was created when it was created, not when a reviewer approved a candidate",
            )
            assertNotEquals("2026-01-02T00:00:00Z", envelope.path("value").path("modified_at").asText(), "the text changed now, so that stamp moves")
            assertEquals(4L, envelope.path("version").asLong())
        }

        @Test
        fun `a candidate merged against an older version never reaches the store`() {
            val objects = mutableMapOf(curatedKey to wrapper("- old"))
            fakeBucket(objects)

            assertFalse(gateway.writeCuratedIfVersion(tenantId, userId, "Research", 2L, "- mine"))

            verify(minioClient, never()).putObject(any<PutObjectArgs>())
            assertEquals("- old", objectMapper.readTree(objects.getValue(curatedKey)).path("value").path("content").asText())
        }

        @Test
        fun `a layer that moved after this read is refused by the store rather than overwritten`() {
            val objects = mutableMapOf(curatedKey to wrapper("- the merge that got there first"))
            fakeBucket(objects)
            // The version still matched when it was read, so the guard that catches this one is the ETag on the
            // wire: a second approval of the same base raced past the first and the store took its side.
            val conflict = preconditionFailed()
            `when`(minioClient.putObject(any<PutObjectArgs>())).thenThrow(conflict)

            assertFalse(gateway.writeCuratedIfVersion(tenantId, userId, "Research", 3L, "- mine"))

            assertEquals(
                "- the merge that got there first",
                objectMapper.readTree(objects.getValue(curatedKey)).path("value").path("content").asText(),
                "a refused approval leaves the layer exactly as the approval that won wrote it",
            )
        }

        @Test
        fun `a layer that answers no ETag is not replaced unconditionally`() {
            // version 3 as `wrapper` writes it, and no ETag header on the response.
            serveBody(wrapper("- old"))

            assertFalse(gateway.writeCuratedIfVersion(tenantId, userId, "Research", 3L, "- new"))

            verify(minioClient, never()).putObject(any<PutObjectArgs>())
        }

        @Test
        fun `a body that is not a store envelope is refused rather than read as no memory`() {
            serveBody("plain text that is not JSON at all")

            val failure = assertThrows(BizException::class.java) { gateway.readCuratedLayer(tenantId, userId, "Research") }

            assertEquals(503, failure.code, "'no memory yet' would let an approval create a second object over this one")
            verify(minioClient, never()).putObject(any<PutObjectArgs>())
        }

        @Test
        fun `an envelope with no embedded version reads as version 1, the way the store answers it`() {
            serveBody("""{"key":"/MEMORY.md","value":{"content":"- seeded by hand"},"version":0}""")

            assertEquals(1L, gateway.readCuratedLayer(tenantId, userId, "Research").version)
        }

        @Test
        fun `an absent layer reads as empty at version 0`() {
            val missing = missingObject()
            `when`(minioClient.getObject(any<GetObjectArgs>())).thenThrow(missing)

            val layer = gateway.readCuratedLayer(tenantId, userId, "Research")

            assertEquals("", layer.content)
            assertEquals(0L, layer.version, "0 is the version a first candidate is filed against")
        }

        @Test
        fun `an agent id that could name a path never reaches the store`() {
            listOf("../", "a/b", "..", "agents/Ops/root", "..\\..\\x").forEach { candidate ->
                reset(minioClient)

                assertEquals(
                    400,
                    assertThrows(BizException::class.java) {
                        gateway.writeCuratedIfVersion(tenantId, userId, candidate, 0L, "- x")
                    }.code,
                    "'$candidate' should have been refused as a write target",
                )
                assertThrows(BizException::class.java) { gateway.readCuratedLayer(tenantId, userId, candidate) }

                verify(minioClient, never()).getObject(any<GetObjectArgs>())
            }
        }

        @Test
        fun `only a source that still holds the merged bytes is cleared`() {
            val matched = sessionKey("root", "MEMORY.md")
            val moved = sessionKey("memory", "2026-10-05.md")
            val objects = mutableMapOf(
                matched to wrapper("- recorded"),
                moved to wrapper("- something a later turn appended"),
            )
            fakeBucket(objects)

            val cleared = gateway.clearSessionSources(
                tenantId,
                userId,
                "Research",
                "sess-1",
                listOf(
                    MemoryDraftSource("MEMORY.md", "- recorded"),
                    MemoryDraftSource("memory/2026-10-05.md", "- recorded"),
                ),
            )

            assertEquals(1, cleared.cleared)
            assertEquals(1, cleared.kept, "the ledger moved after the merge read it, so its next candidate still has it")
            assertEquals(0, cleared.absent)
            assertFalse(objects.containsKey(matched))
            assertTrue(objects.containsKey(moved))
        }

        @Test
        fun `a path the merge never reads is not addressed at all`() {
            fakeBucket(mutableMapOf())

            val cleared = gateway.clearSessionSources(
                tenantId,
                userId,
                "Research",
                "sess-1",
                listOf(MemoryDraftSource("root/MEMORY.md", "- x"), MemoryDraftSource("notes.md", "- x")),
            )

            assertEquals(0, cleared.cleared)
            assertEquals(2, cleared.kept)
            verify(minioClient, never()).getObject(any<GetObjectArgs>())
        }

        @Test
        fun `a source that is already gone is counted apart from one that was cleared`() {
            fakeBucket(mutableMapOf())

            val cleared = gateway.clearSessionSources(
                tenantId,
                userId,
                "Research",
                "sess-1",
                listOf(MemoryDraftSource("MEMORY.md", "- x")),
            )

            assertEquals(MemoryStoreGateway.ClearedSources(0, 0, 1), cleared)
            verify(minioClient, never()).removeObject(any<RemoveObjectArgs>())
        }

        @Test
        fun `an object that survives its own deletion is a fault, not a clear`() {
            val objects = mutableMapOf(sessionKey("root", "MEMORY.md") to wrapper("- recorded"))
            // The bucket answers the remove and keeps the object, the way a store that swallows it would.
            fakeBucket(objects, honoursDelete = false)

            val failure = assertThrows(BizException::class.java) {
                gateway.clearSessionSources(
                    tenantId,
                    userId,
                    "Research",
                    "sess-1",
                    listOf(MemoryDraftSource("MEMORY.md", "- recorded")),
                )
            }

            assertEquals(503, failure.code, "counting it cleared would leave the queue claiming a merge that is still sitting there")
        }

        @Test
        fun `a conversation id that could name a path clears nothing`() {
            val cleared = gateway.clearSessionSources(
                tenantId,
                userId,
                "Research",
                "sess-1/../../other",
                listOf(MemoryDraftSource("MEMORY.md", "- x")),
            )

            assertEquals(0, cleared.cleared)
            assertEquals(1, cleared.kept)
            verify(minioClient, never()).getObject(any<GetObjectArgs>())
        }
    }
}
