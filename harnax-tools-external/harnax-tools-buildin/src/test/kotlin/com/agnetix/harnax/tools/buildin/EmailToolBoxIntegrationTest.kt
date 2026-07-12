package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.SessionMetaContext
import com.agnetix.harnax.tools.sdk.ToolEnvContext
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * EmailToolBox Integration Test — Real SMTP Send
 *
 * Usage:
 *   1. Fill in SMTP config below (or use env vars)
 *   2. Remove @Disabled annotation
 *   3. Run: mvn test -pl harnax-tools-external/harnax-tools-buildin \
 *              -Dtest=EmailToolBoxIntegrationTest -Dsurefire.useFile=false
 *
 * Tip: set env vars SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASSWORD / SMTP_FROM
 *      to avoid hardcoding credentials in source code.
 *
 * @author agnetix
 * @since 2026-07-10
 */
@Disabled("Integration test: requires real SMTP server. Remove @Disabled to run.")
class EmailToolBoxIntegrationTest {

    private lateinit var emailToolBox: EmailToolBox
    private lateinit var mockAdaptor: ToolCallLogAdaptor

    // ====== SMTP Configuration — modify these or set env vars ======
    private val smtpHost: String = System.getenv("SMTP_HOST") ?: "smtp.126.com"
    private val smtpPort: String = System.getenv("SMTP_PORT") ?: "25"
    private val smtpUser: String = System.getenv("SMTP_USER") ?: "ahheqingsong@126.com"
    private val smtpPassword: String = System.getenv("SMTP_PASSWORD") ?: "PWkLPMsqYqdpWUV4"
    private val smtpFrom: String = System.getenv("SMTP_FROM") ?: "ahheqingsong@126.com"

    // ====== Recipient & Content ======
    private val toAddress: String = System.getenv("SMTP_TO") ?: "ahheqingsong@126.com"
    private val subject: String = "Harnax EmailToolBox Integration Test"
    private val plainBody: String = "This is a plain text test email sent from EmailToolBox integration test."
    private val htmlBody: String = """
        <h2>Harnax EmailToolBox Integration Test</h2>
        <p>This is an <strong>HTML</strong> test email.</p>
        <ul>
            <li>SMTP Host: $smtpHost</li>
            <li>SMTP Port: $smtpPort</li>
            <li>Sent at: ${java.time.LocalDateTime.now()}</li>
        </ul>
        <p style="color:gray">If you received this, the integration test passed.</p>
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        emailToolBox = EmailToolBox()
        mockAdaptor = mock()
        emailToolBox.init(
            mockAdaptor,
            SessionMetaContext(agentId = 999L, sessionId = "integration-test-session"),
            UserIdentifier(userId = 1L),
        )
    }

    private fun envContext(): ToolEnvContext = ToolEnvContext(
        bindings =
        mapOf(
            "SMTP_HOST" to smtpHost,
            "SMTP_PORT" to smtpPort,
            "SMTP_USER" to smtpUser,
            "SMTP_PASSWORD" to smtpPassword,
            "SMTP_FROM" to smtpFrom,
        ),
    )

    @Test
    @DisplayName("Send plain text email to real SMTP server")
    fun `send plain text email`() {
        val result =
            emailToolBox.sendEmail(
                to = toAddress,
                subject = subject,
                body = plainBody,
                isHtml = false,
                envContext = envContext(),
            )
        println("Plain text result: $result")
    }

    @Test
    @DisplayName("Send HTML email to real SMTP server")
    fun `send HTML email`() {
        val result =
            emailToolBox.sendEmail(
                to = toAddress,
                subject = "$subject (HTML)",
                body = htmlBody,
                isHtml = true,
                envContext = envContext(),
            )
        println("HTML result: $result")
    }
}
