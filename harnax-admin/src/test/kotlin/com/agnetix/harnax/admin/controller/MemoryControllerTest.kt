package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.MemoryAgentResponse
import com.agnetix.harnax.admin.dto.MemoryDailyEntryResponse
import com.agnetix.harnax.admin.dto.MemoryDetailResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.MemoryService
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod

/**
 * The envelope these three endpoints put on the wire.
 *
 * `harnax-admin` answers a business failure with HTTP 200 and an error `code` inside the envelope, and it
 * answers an unexpected failure with 500 and a fixed message rather than the exception text — memory objects
 * are the last place to start leaking a stack summary into a browser console. The cases below pin that shape
 * per endpoint, including the two codes the memory store actually produces: 401 for a caller with no session
 * and 503 for a bucket that could not be listed or emptied.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryController - the response envelope")
class MemoryControllerTest {

    @Mock
    private lateinit var memoryService: MemoryService

    @InjectMocks
    private lateinit var controller: MemoryController

    private val agents = listOf(
        MemoryAgentResponse(
            agentId = "Research",
            content = "# Memory\n- prefers terse answers",
            lastModified = "2026-10-05T12:00:00Z",
            dates = listOf("2026-10-04", "2026-10-05"),
        ),
    )

    private val detail = MemoryDetailResponse(
        agentId = "Research",
        content = "# Memory\n- prefers terse answers",
        lastModified = "2026-10-05T12:00:00Z",
        entries = listOf(
            MemoryDailyEntryResponse(
                date = "2026-10-05",
                content = "## 14:02\n- asked about the store layout",
                lastModified = "2026-10-05T14:02:00Z",
            ),
        ),
    )

    @BeforeEach
    fun setUp() {
        `when`(memoryService.listMyMemory()).thenReturn(agents)
        `when`(memoryService.readMyMemory(AGENT)).thenReturn(detail)
        `when`(memoryService.deleteMyMemory(AGENT)).thenReturn(3)
    }

    @Nested
    @DisplayName("Route shape")
    inner class RouteShape {

        @Test
        @DisplayName("the endpoints sit on the logged-in admin prefix, not on the internal one")
        fun `class mapping should be the logged-in admin prefix`() {
            val mapping = AnnotatedElementUtils.findMergedAnnotation(
                MemoryController::class.java,
                RequestMapping::class.java,
            )
            assertNotNull(mapping, "the controller has to declare its own prefix")
            val path = mapping!!.path.single()
            assertEquals("/api/admin/memory", path)
            // InternalApiAuthFilter gates exactly `/api/admin/internal/**`; a memory path inside it would be
            // reachable with the shared secret and no session at all.
            assertFalse(path.startsWith("/api/admin/internal"), "memory must not live under the internal prefix")
        }

        @Test
        @DisplayName("each agent route is a method mapping under the class prefix")
        fun `method mappings should carry the agent path variable`() {
            val read = mappingOf("readMemory", String::class.java)
            val delete = mappingOf("deleteMemory", String::class.java)
            val list = mappingOf("listMemory")

            assertArrayEquals(arrayOf("/{$AGENT_PLACEHOLDER}"), read.path)
            assertArrayEquals(arrayOf("/{$AGENT_PLACEHOLDER}"), delete.path)
            assertTrue(list.path.isEmpty(), "the listing has no path of its own beyond the class prefix")
            assertArrayEquals(arrayOf(RequestMethod.GET), read.method)
            assertArrayEquals(arrayOf(RequestMethod.DELETE), delete.method)
        }

        /**
         * The mapping Spring resolves for one method.
         *
         * `RequestMappingHandlerMapping` asks for `@RequestMapping` through a merged lookup, and that is where
         * a `@GetMapping("/{agentId}")` shorthand becomes the `path` attribute. Reading the annotation straight
         * off the JDK proxy instead answers with the empty default, so it reports a correctly routed
         * controller as broken.
         */
        private fun mappingOf(
            name: String,
            vararg parameterTypes: Class<*>,
        ): RequestMapping {
            val method = MemoryController::class.java.getDeclaredMethod(name, *parameterTypes)
            val mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
            assertNotNull(mapping, "$name carries no request mapping at all")
            return mapping!!
        }
    }

    @Nested
    @DisplayName("GET /api/admin/memory")
    inner class ListEndpoint {

        @Test
        fun `listMemory should wrap the agents in a success envelope`() {
            val result = controller.listMemory()

            assertTrue(result.isSuccess())
            assertEquals(200, result.code)
            assertEquals("success", result.message)
            assertEquals(agents, result.data)
        }

        @Test
        fun `listMemory should treat an empty bucket as a success with an empty list`() {
            `when`(memoryService.listMyMemory()).thenReturn(emptyList())

            val result = controller.listMemory()

            assertTrue(result.isSuccess(), "no memory yet is not an error")
            assertEquals(emptyList<MemoryAgentResponse>(), result.data)
        }

        @Test
        fun `listMemory should pass a 401 through as the envelope code`() {
            `when`(memoryService.listMyMemory()).thenThrow(BizException(401, "User not logged in"))

            val result = controller.listMemory()

            assertFalse(result.isSuccess())
            assertEquals(401, result.code)
            assertEquals("User not logged in", result.message)
            assertNull(result.data, "an error envelope carries no data, and non_null drops the key")
        }

        @Test
        fun `listMemory should pass a 503 store failure through as the envelope code`() {
            `when`(memoryService.listMyMemory()).thenThrow(BizException(503, "Memory store could not be listed"))

            val result = controller.listMemory()

            assertEquals(503, result.code)
            assertEquals("Memory store could not be listed", result.message)
        }

        @Test
        fun `listMemory should hide an unexpected failure behind a fixed message`() {
            `when`(memoryService.listMyMemory()).thenThrow(IllegalStateException("MinIO XML leaked here"))

            val result = controller.listMemory()

            assertEquals(500, result.code)
            assertEquals("Failed to list memory", result.message)
            assertFalse(result.message.contains("MinIO"), "the store's own error text does not belong in the envelope")
        }
    }

    @Nested
    @DisplayName("GET /api/admin/memory/{agentId}")
    inner class ReadEndpoint {

        @Test
        fun `readMemory should return the curated layer and the daily entries`() {
            val result = controller.readMemory(AGENT)

            assertTrue(result.isSuccess())
            assertEquals(AGENT, result.data?.agentId)
            assertEquals("# Memory\n- prefers terse answers", result.data?.content)
            assertEquals("2026-10-05", result.data?.entries?.single()?.date)
        }

        @Test
        fun `readMemory should answer 404 when the agent has no memory`() {
            `when`(memoryService.readMyMemory(AGENT)).thenReturn(null)

            val result = controller.readMemory(AGENT)

            assertFalse(result.isSuccess())
            assertEquals(404, result.code)
            assertEquals("No memory for this agent", result.message)
            assertNull(result.data)
        }

        @Test
        fun `readMemory should answer 400 for an agent id that cannot be a key segment`() {
            `when`(memoryService.readMyMemory(TRAVERSAL)).thenThrow(BizException("Invalid agent id"))

            val result = controller.readMemory(TRAVERSAL)

            assertEquals(400, result.code)
            assertEquals("Invalid agent id", result.message)
        }

        @Test
        fun `readMemory should hide an unexpected failure behind a fixed message`() {
            `when`(memoryService.readMyMemory(AGENT)).thenThrow(RuntimeException("socket closed"))

            val result = controller.readMemory(AGENT)

            assertEquals(500, result.code)
            assertEquals("Failed to read memory", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/memory/{agentId}")
    inner class DeleteEndpoint {

        @Test
        fun `deleteMemory should report how many objects left`() {
            val result = controller.deleteMemory(AGENT)

            assertTrue(result.isSuccess())
            assertEquals(AGENT, result.data?.agentId)
            assertEquals(3, result.data?.deletedObjects)
        }

        @Test
        fun `deleteMemory should count a delete that removed nothing as success`() {
            `when`(memoryService.deleteMyMemory(AGENT)).thenReturn(0)

            val result = controller.deleteMemory(AGENT)

            assertTrue(result.isSuccess(), "deleting an agent with no memory is not an error")
            assertEquals(0, result.data?.deletedObjects)
        }

        @Test
        fun `deleteMemory should pass a 503 partial failure through so the caller sees the cleanup did not finish`() {
            `when`(memoryService.deleteMyMemory(AGENT))
                .thenThrow(BizException(503, "Memory could not be fully deleted (1 of 3 object(s) removed before failing)"))

            val result = controller.deleteMemory(AGENT)

            assertEquals(503, result.code)
            assertTrue(result.message.contains("1 of 3"), "the operator has to learn the bucket is only partly gone")
            assertNull(result.data)
        }

        @Test
        fun `deleteMemory should hide an unexpected failure behind a fixed message`() {
            `when`(memoryService.deleteMyMemory(AGENT)).thenThrow(RuntimeException("access denied"))

            val result = controller.deleteMemory(AGENT)

            assertEquals(500, result.code)
            assertEquals("Failed to delete memory", result.message)
        }
    }

    private companion object {
        const val AGENT = "Research"
        const val AGENT_PLACEHOLDER = "agentId"
        const val TRAVERSAL = "../secret"
    }
}
