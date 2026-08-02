package com.agnetix.harnax.harness.output

import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Method
import java.util.Base64

/**
 * Tests for [HarnessAgentWrapper.detectAndPersistOutputFiles] channel/web branching.
 *
 * Verifies:
 * - Channel sessions (chn-*): return filePath-only attachments (no MinIO upload)
 * - Web sessions: persist to MinIO and return objectKey + url
 * - Web sessions without store: return empty list
 * - Error resilience: individual file failure doesn't block others
 */
class DetectAndPersistOutputFilesTest {

    private fun createWrapper(
        sessionId: String,
        detector: OutputFileDetector? = null,
        sandboxManager: KeepAliveSandboxManager? = null,
        store: OutputFileStore? = null,
    ): HarnessAgentWrapper {
        val harnessAgent = mock<HarnessAgent>()
        val tokenStatBuilder = mock<TokenStatBuilder>()
        val tokenStatAdaptor = mock<TokenStatAdaptor>()

        return HarnessAgentWrapper(
            harnessAgent = harnessAgent,
            dangerousTools = emptySet(),
            tokenStatBuilder = tokenStatBuilder,
            tokenStatAdaptor = tokenStatAdaptor,
            sessionId = sessionId,
            keepAliveSandboxManager = sandboxManager,
            outputFileDetector = detector,
            outputFileStore = store,
        )
    }

    private fun invokeDetect(wrapper: HarnessAgentWrapper, callStartTime: Long): List<com.agnetix.harnax.agent.protocol.FileAttachment> {
        val method: Method = HarnessAgentWrapper::class.java.getDeclaredMethod(
            "detectAndPersistOutputFiles",
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(wrapper, callStartTime) as List<com.agnetix.harnax.agent.protocol.FileAttachment>
    }

    private fun mockSandboxManager(sandbox: DockerSandbox): KeepAliveSandboxManager {
        val manager = mock<KeepAliveSandboxManager>()
        whenever(manager.getSandbox(any())).thenReturn(sandbox)
        return manager
    }

    // ==================== Channel session (chn-*) ====================

    @Nested
    inner class ChannelSession {

        @Test
        fun `channel session returns filePath without objectKey or url`() {
            val sandbox = mock<DockerSandbox>()
            val detector = mock<OutputFileDetector>()
            val sandboxManager = mockSandboxManager(sandbox)

            whenever(detector.detect(any(), any(), any())).thenReturn(
                listOf(DetectedFile("/workspace/output/report.pptx", "report.pptx", 69293)),
            )

            val wrapper = createWrapper(
                sessionId = "chn-da0b56ff-c712-4bb6",
                detector = detector,
                sandboxManager = sandboxManager,
                store = null, // No store needed for channel
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertEquals(1, attachments.size)
            val att = attachments[0]
            assertEquals("report.pptx", att.fileName)
            assertEquals("/workspace/output/report.pptx", att.filePath)
            assertEquals(69293L, att.fileSize)
            assertEquals("", att.objectKey, "Channel session should have empty objectKey")
            assertEquals("", att.url, "Channel session should have empty url")
        }

        @Test
        fun `channel session with multiple files returns all with filePath`() {
            val sandbox = mock<DockerSandbox>()
            val detector = mock<OutputFileDetector>()
            val sandboxManager = mockSandboxManager(sandbox)

            whenever(detector.detect(any(), any(), any())).thenReturn(
                listOf(
                    DetectedFile("/workspace/output/a.xlsx", "a.xlsx", 1000),
                    DetectedFile("/workspace/output/b.pdf", "b.pdf", 2000),
                ),
            )

            val wrapper = createWrapper(
                sessionId = "chn-multi-file-test",
                detector = detector,
                sandboxManager = sandboxManager,
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertEquals(2, attachments.size)
            assertTrue(attachments.all { it.objectKey.isEmpty() })
            assertTrue(attachments.all { it.url.isEmpty() })
            assertTrue(attachments.all { it.filePath.startsWith("/workspace/output/") })
        }
    }

    // ==================== Web session ====================

    @Nested
    inner class WebSession {

        @Test
        fun `web session persists to MinIO and returns objectKey and url`() {
            val sandbox = mock<DockerSandbox>()
            val detector = mock<OutputFileDetector>()
            val sandboxManager = mockSandboxManager(sandbox)
            val store = mock<OutputFileStore>()

            // Mock base64 extraction
            val fileContent = "fake-xlsx-bytes"
            val base64Content = Base64.getEncoder().encodeToString(fileContent.toByteArray())
            whenever(sandbox.exec(anyOrNull(), any(), any())).thenReturn(
                ExecResult(0, base64Content, "", false),
            )

            whenever(detector.detect(any(), any(), any())).thenReturn(
                listOf(DetectedFile("/workspace/output/data.xlsx", "data.xlsx", 15)),
            )

            whenever(store.persist(any(), any(), any(), any())).thenReturn(
                StoredFile(
                    fileId = "file-abc",
                    url = "http://admin:8080/api/output-files/web/sess/file-abc?name=data.xlsx",
                    objectKey = "web/web-sess-1/file-abc",
                ),
            )

            val wrapper = createWrapper(
                sessionId = "web-sess-1",
                detector = detector,
                sandboxManager = sandboxManager,
                store = store,
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertEquals(1, attachments.size)
            val att = attachments[0]
            assertEquals("data.xlsx", att.fileName)
            assertEquals("web/web-sess-1/file-abc", att.objectKey)
            assertTrue(att.url.contains("file-abc"))
            assertEquals("/workspace/output/data.xlsx", att.filePath)
        }

        @Test
        fun `web session without store returns empty list`() {
            val sandbox = mock<DockerSandbox>()
            val detector = mock<OutputFileDetector>()
            val sandboxManager = mockSandboxManager(sandbox)

            whenever(detector.detect(any(), any(), any())).thenReturn(
                listOf(DetectedFile("/workspace/output/report.pdf", "report.pdf", 5000)),
            )

            val wrapper = createWrapper(
                sessionId = "web-no-store",
                detector = detector,
                sandboxManager = sandboxManager,
                store = null, // No store configured
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertTrue(attachments.isEmpty(), "Web session without store should return empty")
        }
    }

    // ==================== Guard conditions ====================

    @Nested
    inner class GuardConditions {

        @Test
        fun `returns empty when detector is null`() {
            val wrapper = createWrapper(
                sessionId = "chn-test",
                detector = null,
                sandboxManager = mock(),
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertTrue(attachments.isEmpty())
        }

        @Test
        fun `returns empty when sandboxManager is null`() {
            val wrapper = createWrapper(
                sessionId = "chn-test",
                detector = OutputFileDetector(),
                sandboxManager = null,
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertTrue(attachments.isEmpty())
        }

        @Test
        fun `returns empty when no sandbox available for session`() {
            val detector = mock<OutputFileDetector>()
            val sandboxManager = mock<KeepAliveSandboxManager>()
            whenever(sandboxManager.getSandbox(any())).thenReturn(null)

            val wrapper = createWrapper(
                sessionId = "chn-no-sandbox",
                detector = detector,
                sandboxManager = sandboxManager,
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertTrue(attachments.isEmpty())
        }

        @Test
        fun `returns empty when no files detected`() {
            val sandbox = mock<DockerSandbox>()
            val detector = mock<OutputFileDetector>()
            val sandboxManager = mockSandboxManager(sandbox)

            whenever(detector.detect(any(), any(), any())).thenReturn(emptyList())

            val wrapper = createWrapper(
                sessionId = "chn-empty",
                detector = detector,
                sandboxManager = sandboxManager,
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertTrue(attachments.isEmpty())
        }
    }

    // ==================== Error resilience ====================

    @Nested
    inner class ErrorResilience {

        @Test
        fun `detection exception returns empty list gracefully`() {
            val sandbox = mock<DockerSandbox>()
            val detector = mock<OutputFileDetector>()
            val sandboxManager = mockSandboxManager(sandbox)

            whenever(detector.detect(any(), any(), any())).thenThrow(RuntimeException("Sandbox exploded"))

            val wrapper = createWrapper(
                sessionId = "chn-error",
                detector = detector,
                sandboxManager = sandboxManager,
            )

            val attachments = invokeDetect(wrapper, System.currentTimeMillis())

            assertTrue(attachments.isEmpty(), "Should return empty on exception, not crash")
        }
    }
}
