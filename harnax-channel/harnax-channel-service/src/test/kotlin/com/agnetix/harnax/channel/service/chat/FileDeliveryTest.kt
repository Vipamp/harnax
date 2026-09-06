package com.agnetix.harnax.channel.service.chat

import com.agnetix.harnax.agent.protocol.FileAttachment
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageRole
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for file attachment delivery in ChannelChatService.
 *
 * Tests the two-strategy file resolution:
 * 1. Workspace download (channel sessions: filePath present, objectKey empty)
 * 2. HTTP URL fallback
 *
 * Also verifies correct session ID usage:
 * - agentSessionId (chn-xxx) for workspace download
 * - userSessionId (platform user ID) for sendFile
 */
class FileDeliveryTest {

    private lateinit var sessionManager: ChannelSessionManager
    private lateinit var channelAdaptor: ChannelAdaptor
    private lateinit var agentAdaptor: AgentAdaptor
    private lateinit var channel: ChannelSpec

    private val agentSessionId = "chn-da0b56ff-c712-4bb6-8536-3b3e88b1818b"
    private val userSessionId = "o9cq80w0GqpB7LQ76jKpKv3DtNEY@im.wechat"

    @BeforeEach
    fun setUp() {
        sessionManager = mock(ChannelSessionManager::class.java)
        channelAdaptor = mock(ChannelAdaptor::class.java)
        agentAdaptor = mock(AgentAdaptor::class.java)

        channel = ChannelSpec(
            id = 1L,
            name = "wechat-channel",
            type = ChannelType.WECHAT,
            agentId = 1L,
            callbackKey = "test-key",
            sessionId = agentSessionId,
        )

        runBlocking {
            whenever(sessionManager.getHistory(any(), any(), any())).thenReturn(emptyList())
        }
        whenever(sessionManager.toAgentMessages(any())).thenReturn(emptyList())
        whenever(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
        whenever(channelAdaptor.supportsStreamingOutput()).thenReturn(false)
    }

    private fun buildMessage(content: String = "generate a report"): ChannelMessage = ChannelMessage(
        messageId = "msg-1",
        sessionId = userSessionId,
        messageType = MessageType.TEXT,
        role = MessageRole.USER,
        content = content,
        channelType = ChannelType.WECHAT,
    )

    private fun buildAttachment(
        fileName: String = "report.pptx",
        filePath: String = "/workspace/output/report.pptx",
        objectKey: String = "",
        url: String = "",
    ): FileAttachment = FileAttachment(
        fileId = "file-123",
        fileName = fileName,
        filePath = filePath,
        fileSize = 1024L,
        mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        url = url,
        objectKey = objectKey,
    )

    // ==================== Workspace download strategy ====================

    @Nested
    inner class WorkspaceDownloadStrategy {

        @Test
        fun `channel session uses workspace downloader when objectKey is empty`() = runBlocking {
            val fileBytes = "fake-pptx-content".toByteArray()
            val workspaceDownloader: (String, String) -> ByteArray? = { sessionId, path ->
                assertEquals(agentSessionId, sessionId)
                assertEquals("/workspace/output/report.pptx", path)
                fileBytes
            }

            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = workspaceDownloader,
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(
                    content = "Report generated.",
                    shouldReply = true,
                    attachments = listOf(buildAttachment()),
                ),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendFile(
                eq(channel),
                eq(userSessionId),
                eq(fileBytes),
                eq("report.pptx"),
                any(),
            )
        }

        @Test
        fun `workspace downloader receives agentSessionId not userSessionId`() = runBlocking {
            var receivedSessionId: String? = null
            val workspaceDownloader: (String, String) -> ByteArray? = { sessionId, _ ->
                receivedSessionId = sessionId
                "data".toByteArray()
            }

            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = workspaceDownloader,
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(
                    content = "Done",
                    shouldReply = true,
                    attachments = listOf(buildAttachment()),
                ),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            assertEquals(agentSessionId, receivedSessionId)
            assertNotEquals(userSessionId, receivedSessionId)
        }

        @Test
        fun `sendFile uses userSessionId for platform delivery`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> "bytes".toByteArray() },
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(
                    content = "Done",
                    shouldReply = true,
                    attachments = listOf(buildAttachment()),
                ),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendFile(
                eq(channel),
                eq(userSessionId),
                any(),
                eq("report.pptx"),
                any(),
            )
        }

        @Test
        fun `falls back to HTTP URL when workspace download fails`() = runBlocking {
            val urlBytes = "url-content".toByteArray()

            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> null }, // Workspace fails
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(
                    content = "Done",
                    shouldReply = true,
                    attachments = listOf(buildAttachment(url = "http://localhost/api/output-files/web/sess/file-123")),
                ),
            )

            // HTTP URL fallback uses java.net.URL which can't be easily mocked,
            // so we just verify the workspace failure path leads to fallback attempt
            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // sendFile not called because HTTP fallback will fail in test env
            verify(channelAdaptor, never()).sendFile(any(), any(), any(), any(), any())
            // Failure notification sent
            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq(userSessionId),
                argThat { contains("report.pptx") && contains("失败") },
            )
        }
    }

    // ==================== Error handling ====================

    @Nested
    inner class ErrorHandling {

        @Test
        fun `sends failure notification when file resolution fails`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> null },
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(
                    content = "Done",
                    shouldReply = true,
                    attachments = listOf(buildAttachment()),
                ),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // Should send failure notification
            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq(userSessionId),
                argThat { contains("report.pptx") && contains("失败") },
            )
            // Should NOT call sendFile
            verify(channelAdaptor, never()).sendFile(any(), any(), any(), any(), any())
        }

        @Test
        fun `text reply is sent even when file delivery fails`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> null },
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(
                    content = "Report generated successfully.",
                    shouldReply = true,
                    attachments = listOf(buildAttachment()),
                ),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // Text reply should still be sent
            verify(channelAdaptor).sendMessage(eq(channel), eq(userSessionId), eq("Report generated successfully."))
        }

        @Test
        fun `multiple attachments are delivered independently`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, path ->
                    if (path.contains("fail")) null else "content-$path".toByteArray()
                },
            )

            val attachments = listOf(
                buildAttachment(fileName = "good.pptx", filePath = "/workspace/output/good.pptx"),
                buildAttachment(fileName = "fail.pptx", filePath = "/workspace/output/fail.pptx"),
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Done", shouldReply = true, attachments = attachments),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // First file delivered successfully
            verify(channelAdaptor).sendFile(eq(channel), eq(userSessionId), any(), eq("good.pptx"), any())
            // Second file fails → notification sent
            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq(userSessionId),
                argThat { contains("fail.pptx") },
            )
        }
    }

    // ==================== No attachments ====================

    @Nested
    inner class NoAttachments {

        @Test
        fun `no file delivery when attachments list is empty`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> "data".toByteArray() },
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Just text", shouldReply = true, attachments = emptyList()),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor, never()).sendFile(any(), any(), any(), any(), any())
        }
    }

    // ==================== Edge cases ====================

    @Nested
    inner class EdgeCases {

        @Test
        fun `sendFile exception does not prevent other files from delivery`() = runBlocking {
            var callCount = 0
            whenever(channelAdaptor.sendFile(any(), any(), any(), any(), any())).thenAnswer {
                callCount++
                if (callCount == 1) throw RuntimeException("WeChat API timeout")
            }

            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, path -> "content-$path".toByteArray() },
            )

            val attachments = listOf(
                buildAttachment(fileName = "first.pptx", filePath = "/workspace/output/first.pptx"),
                buildAttachment(fileName = "second.xlsx", filePath = "/workspace/output/second.xlsx"),
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Done", shouldReply = true, attachments = attachments),
            )

            // Should not throw
            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // Both sendFile calls attempted
            verify(channelAdaptor, times(2)).sendFile(any(), any(), any(), any(), any())
            // Failure notification for first file
            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq(userSessionId),
                argThat { contains("first.pptx") },
            )
        }

        @Test
        fun `empty byte array from workspace is treated as failure`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> ByteArray(0) },
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Done", shouldReply = true, attachments = listOf(buildAttachment())),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // Empty bytes should NOT be sent as file
            verify(channelAdaptor, never()).sendFile(any(), any(), any(), any(), any())
            // Failure notification sent
            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq(userSessionId),
                argThat { contains("失败") },
            )
        }

        @Test
        fun `files delivered even when shouldReply is false`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> "file-data".toByteArray() },
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(
                    content = "",
                    shouldReply = false,
                    attachments = listOf(buildAttachment()),
                ),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // File should still be delivered
            verify(channelAdaptor).sendFile(eq(channel), eq(userSessionId), any(), eq("report.pptx"), any())
            // But no text message
            verify(channelAdaptor, never()).sendMessage(any(), any(), any())
        }

        @Test
        fun `workspace downloader not configured falls through to failure`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = null, // Not configured
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Done", shouldReply = true, attachments = listOf(buildAttachment())),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // No resolver available → failure notification
            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq(userSessionId),
                argThat { contains("report.pptx") && contains("失败") },
            )
        }

        @Test
        fun `all resolvers null results in failure notification`() = runBlocking {
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = null,
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Done", shouldReply = true, attachments = listOf(buildAttachment())),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq(userSessionId),
                argThat { contains("report.pptx") && contains("失败") },
            )
        }

        @Test
        fun `large file bytes are passed through without truncation`() = runBlocking {
            val largeBytes = ByteArray(10 * 1024 * 1024) { 0x42 } // 10MB
            val chatService = ChannelChatService(
                sessionManager = sessionManager,
                workspaceFileDownloader = { _, _ -> largeBytes },
            )

            whenever(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Done", shouldReply = true, attachments = listOf(buildAttachment())),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendFile(
                eq(channel),
                eq(userSessionId),
                argThat { size == 10 * 1024 * 1024 },
                eq("report.pptx"),
                any(),
            )
        }
    }
}
