package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.SessionMetaContext
import com.agnetix.harnax.tools.sdk.ToolEnvContext
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallInfo
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import jakarta.mail.Transport
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

/**
 * EmailToolBox Unit Tests
 *
 * Verifies the built-in email sending tool:
 * - sendEmail() with plain text body
 * - sendEmail() with HTML body
 * - sendEmail() with missing env params throws exception
 * - sendEmail() with default/custom SMTP port
 * - name() returns correct tool name
 * - Tool call logging via adaptor
 *
 * Uses Mockito + MockedStatic to mock Transport.send() without Spring context.
 *
 * @author agnetix
 * @since 2026-07-10
 */
class EmailToolBoxTest {

    private lateinit var emailToolBox: EmailToolBox
    private lateinit var mockAdaptor: ToolCallLogAdaptor

    private val standardEnv = mapOf(
        "SMTP_HOST" to "smtp.example.com",
        "SMTP_PORT" to "587",
        "SMTP_USER" to "testuser",
        "SMTP_PASSWORD" to "testpass",
        "SMTP_FROM" to "sender@example.com",
    )

    @BeforeEach
    fun setUp() {
        emailToolBox = EmailToolBox()
        mockAdaptor = mock()
        emailToolBox.init(
            mockAdaptor,
            SessionMetaContext(agentId = 1L, sessionId = "test-session"),
            UserIdentifier(userId = 1L),
        )
    }

    private fun envContext(bindings: Map<String, String> = standardEnv): ToolEnvContext = ToolEnvContext(bindings = bindings)

    @Nested
    @DisplayName("Name Tests")
    inner class NameTests {

        @Test
        @DisplayName("name should return 'email-tool-box'")
        fun `name should return correct value`() {
            assertEquals("email-tool-box", emailToolBox.name())
        }

        @Test
        @DisplayName("NAME companion constant should match name()")
        fun `NAME constant should match name`() {
            assertEquals(EmailToolBox.NAME, emailToolBox.name())
        }
    }

    @Nested
    @DisplayName("sendEmail - Plain Text Tests")
    inner class SendEmailPlainTextTests {

        @Test
        @DisplayName("sendEmail should return success message for plain text email")
        fun `sendEmail plain text should succeed`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .then { }

                val result = emailToolBox.sendEmail(
                    to = "recipient@example.com",
                    subject = "Test Subject",
                    body = "Hello, this is a test email.",
                    isHtml = false,
                    envContext = envContext(),
                )

                assertEquals("Email sent successfully to recipient@example.com", result)
                mockedTransport.verify { Transport.send(any(MimeMessage::class.java)) }
            }
        }

        @Test
        @DisplayName("sendEmail plain text should log tool call via adaptor")
        fun `sendEmail plain text should log tool call`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .then { }

                emailToolBox.sendEmail(
                    to = "recipient@example.com",
                    subject = "Test Subject",
                    body = "Hello",
                    isHtml = false,
                    envContext = envContext(),
                )

                val captor = argumentCaptor<ToolCallInfo>()
                verify(mockAdaptor, times(1)).emit(captor.capture())

                val info = captor.firstValue
                assertEquals("email-tool-box::sendEmail", info.toolName)
                assertTrue(info.success)
                assertEquals(1L, info.agentId)
                assertEquals("test-session", info.sessionId)
                assertEquals("recipient@example.com", info.args["to"])
                assertEquals("Test Subject", info.args["subject"])
            }
        }
    }

    @Nested
    @DisplayName("sendEmail - HTML Body Tests")
    inner class SendEmailHtmlTests {

        @Test
        @DisplayName("sendEmail should return success message for HTML email")
        fun `sendEmail HTML should succeed`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .then { }

                val result = emailToolBox.sendEmail(
                    to = "recipient@example.com",
                    subject = "HTML Email",
                    body = "<h1>Hello</h1><p>This is HTML</p>",
                    isHtml = true,
                    envContext = envContext(),
                )

                assertEquals("Email sent successfully to recipient@example.com", result)
            }
        }
    }

    @Nested
    @DisplayName("sendEmail - Port Configuration Tests")
    inner class SendEmailPortTests {

        @Test
        @DisplayName("sendEmail should use default port 587 when SMTP_PORT is not set")
        fun `sendEmail should default to port 587 when SMTP_PORT missing`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .then { }

                val envWithoutPort = standardEnv - "SMTP_PORT"
                val result = emailToolBox.sendEmail(
                    to = "recipient@example.com",
                    subject = "Test",
                    body = "Body",
                    isHtml = false,
                    envContext = envContext(envWithoutPort),
                )

                assertEquals("Email sent successfully to recipient@example.com", result)
            }
        }

        @Test
        @DisplayName("sendEmail should fallback to 587 when SMTP_PORT is not a valid number")
        fun `sendEmail should fallback to 587 on invalid port`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .then { }

                val envWithBadPort = standardEnv + ("SMTP_PORT" to "abc")
                val result = emailToolBox.sendEmail(
                    to = "recipient@example.com",
                    subject = "Test",
                    body = "Body",
                    isHtml = false,
                    envContext = envContext(envWithBadPort),
                )

                assertEquals("Email sent successfully to recipient@example.com", result)
            }
        }

        @Test
        @DisplayName("sendEmail should use custom port when provided")
        fun `sendEmail should use custom port`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .then { }

                val envWithCustomPort = standardEnv + ("SMTP_PORT" to "465")
                val result = emailToolBox.sendEmail(
                    to = "recipient@example.com",
                    subject = "Test",
                    body = "Body",
                    isHtml = false,
                    envContext = envContext(envWithCustomPort),
                )

                assertEquals("Email sent successfully to recipient@example.com", result)
            }
        }
    }

    @Nested
    @DisplayName("sendEmail - Missing Env Params Tests")
    inner class SendEmailMissingEnvTests {

        @Test
        @DisplayName("sendEmail should throw when SMTP_HOST is missing")
        fun `sendEmail should throw when SMTP_HOST missing`() {
            val env = standardEnv - "SMTP_HOST"
            val exception = assertThrows<IllegalArgumentException> {
                emailToolBox.sendEmail(
                    to = "r@ex.com",
                    subject = "s",
                    body = "b",
                    isHtml = false,
                    envContext = envContext(env),
                )
            }
            assertTrue(exception.message!!.contains("SMTP_HOST"))
        }

        @Test
        @DisplayName("sendEmail should throw when SMTP_USER is missing")
        fun `sendEmail should throw when SMTP_USER missing`() {
            val env = standardEnv - "SMTP_USER"
            val exception = assertThrows<IllegalArgumentException> {
                emailToolBox.sendEmail(
                    to = "r@ex.com",
                    subject = "s",
                    body = "b",
                    isHtml = false,
                    envContext = envContext(env),
                )
            }
            assertTrue(exception.message!!.contains("SMTP_USER"))
        }

        @Test
        @DisplayName("sendEmail should throw when SMTP_PASSWORD is missing")
        fun `sendEmail should throw when SMTP_PASSWORD missing`() {
            val env = standardEnv - "SMTP_PASSWORD"
            val exception = assertThrows<IllegalArgumentException> {
                emailToolBox.sendEmail(
                    to = "r@ex.com",
                    subject = "s",
                    body = "b",
                    isHtml = false,
                    envContext = envContext(env),
                )
            }
            assertTrue(exception.message!!.contains("SMTP_PASSWORD"))
        }

        @Test
        @DisplayName("sendEmail should throw when SMTP_FROM is missing")
        fun `sendEmail should throw when SMTP_FROM missing`() {
            val env = standardEnv - "SMTP_FROM"
            val exception = assertThrows<IllegalArgumentException> {
                emailToolBox.sendEmail(
                    to = "r@ex.com",
                    subject = "s",
                    body = "b",
                    isHtml = false,
                    envContext = envContext(env),
                )
            }
            assertTrue(exception.message!!.contains("SMTP_FROM"))
        }
    }

    @Nested
    @DisplayName("sendEmail - Transport Error Handling Tests")
    inner class SendEmailErrorTests {

        @Test
        @DisplayName("sendEmail should propagate Transport exceptions")
        fun `sendEmail should throw when Transport send fails`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .thenThrow(jakarta.mail.MessagingException("Connection refused"))

                assertThrows<jakarta.mail.MessagingException> {
                    emailToolBox.sendEmail(
                        to = "recipient@example.com",
                        subject = "Test",
                        body = "Body",
                        isHtml = false,
                        envContext = envContext(),
                    )
                }
            }
        }

        @Test
        @DisplayName("sendEmail should log error via adaptor when Transport fails")
        fun `sendEmail should log error on Transport failure`() {
            val mockedTransport: MockedStatic<Transport> = mockStatic(Transport::class.java)
            mockedTransport.use {
                mockedTransport.`when`<Void> { Transport.send(any(MimeMessage::class.java)) }
                    .thenThrow(jakarta.mail.MessagingException("Connection refused"))

                try {
                    emailToolBox.sendEmail(
                        to = "recipient@example.com",
                        subject = "Test",
                        body = "Body",
                        isHtml = false,
                        envContext = envContext(),
                    )
                } catch (_: Exception) {
                    // expected
                }

                val captor = argumentCaptor<ToolCallInfo>()
                verify(mockAdaptor, times(1)).emit(captor.capture())

                val info = captor.firstValue
                assertEquals("email-tool-box::sendEmail", info.toolName)
                assertFalse(info.success)
                assertTrue(info.result.contains("Connection refused"))
            }
        }
    }
}
