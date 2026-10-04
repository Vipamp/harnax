package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.admin.exception.BizException
import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import io.minio.Result
import io.minio.messages.Item
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
     */
    private fun objectStream(body: String): GetObjectResponse {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val position = AtomicInteger(0)
        return mock(GetObjectResponse::class.java).apply {
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

        @Test
        fun `a delete asks for the caller's prefix and every key it removes stays inside it`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))

            gateway.deleteAgent(tenantId, userId, "Research")

            assertEquals(listOf("harnax-store" to ownerPrefix), listedPrefixes())
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

            val removed = gateway.deleteUser(tenantId, userId)

            assertEquals(3, removed)
            assertEquals(listOf("harnax-store" to ownerPrefix), listedPrefixes())
            deletedKeys().forEach { (_, key) -> assertTrue(key.startsWith(ownerPrefix), key) }
        }

        /** A listing that returns a foreign key must not get that key deleted, prefix or no prefix. */
        @Test
        fun `a foreign key in the listing is never removed`() {
            bucket(
                stored("${ownerPrefix}agents/Research/root/MEMORY.md"),
                stored("store/tenants/5/users/8/agents/Research/root/MEMORY.md"),
            )

            assertEquals(1, gateway.deleteUser(tenantId, userId))
            assertEquals(listOf("harnax-store" to "${ownerPrefix}agents/Research/root/MEMORY.md"), deletedKeys())
        }

        @Test
        fun `a sweep that cannot delete throws so the account deletion can roll back`() {
            bucket(stored("${ownerPrefix}agents/Research/root/MEMORY.md"))
            doThrow(RuntimeException("access denied")).`when`(minioClient).removeObject(any<RemoveObjectArgs>())

            assertThrows(BizException::class.java) { gateway.deleteUser(tenantId, userId) }
        }

        @Test
        fun `another user id is only ever another owner prefix`() {
            bucket()

            gateway.deleteUser(tenantId, "8")

            assertEquals(listOf("harnax-store" to "store/tenants/4/users/8/"), listedPrefixes())
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

            val failure = assertThrows(BizException::class.java) { gateway.deleteUser(tenantId, userId) }

            assertEquals(503, failure.code)
            verify(minioClient, never()).removeObject(any<RemoveObjectArgs>())
        }
    }
}
