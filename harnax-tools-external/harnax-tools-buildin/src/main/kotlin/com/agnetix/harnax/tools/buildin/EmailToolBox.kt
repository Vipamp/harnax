package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.ToolBox
import com.agnetix.harnax.tools.sdk.ToolEnvContext
import com.agnetix.harnax.tools.sdk.ToolEnvParamDef
import com.agnetix.harnax.tools.sdk.ToolMeta
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Properties

/**
 * Built-in email sending tools for agent use.
 *
 * Uses Jakarta Mail API directly (no Spring framework dependency).
 * SMTP configuration is provided via environment parameters bound per-agent in the Admin UI.
 *
 * Required env params:
 * - SMTP_HOST: SMTP server hostname
 * - SMTP_USER: SMTP login username
 * - SMTP_PASSWORD: SMTP login password
 * - SMTP_FROM: Sender email address
 *
 * Optional env params:
 * - SMTP_PORT: SMTP server port (default: 587, TLS)
 *
 * @Author: heqingsong
 * @Date: 2026/7/10
 */
@Component("email-tool-box")
class EmailToolBox : ToolBox() {

    @Tool(name = "sendEmail", description = "Send an email via SMTP. Supports plain text and HTML body.")
    @ToolMeta(
        displayName = "Send Email",
        displayNameZh = "发送邮件",
        envParamDefs = [
            ToolEnvParamDef(key = "SMTP_HOST", description = "SMTP server hostname", required = true),
            ToolEnvParamDef(key = "SMTP_PORT", description = "SMTP server port", required = false, defaultValue = "587"),
            ToolEnvParamDef(key = "SMTP_USER", description = "SMTP login username", required = true),
            ToolEnvParamDef(key = "SMTP_PASSWORD", description = "SMTP login password", required = true, secret = true),
            ToolEnvParamDef(key = "SMTP_FROM", description = "Sender email address", required = true),
        ],
        needConfirm = true,
    )
    fun sendEmail(
        @ToolParam(name = "to", description = "Recipient email address")
        to: String?,
        @ToolParam(name = "subject", description = "Email subject line")
        subject: String?,
        @ToolParam(name = "body", description = "Email body content")
        body: String?,
        @ToolParam(name = "is_html", description = "Whether the body is HTML format (default: true)", required = false)
        isHtml: Boolean? = true,
        envContext: ToolEnvContext,
    ): String = execute("to" to to, "subject" to subject) {
        val log = LoggerFactory.getLogger(EmailToolBox::class.java)
        log.info("[env-debug] sendEmail called, envContext bindings keys: {}", envContext.bindings.keys)

        // Validate required parameters (LLM may pass null for any parameter)
        require(!to.isNullOrBlank()) { "Parameter 'to' (recipient email) is required" }
        require(!subject.isNullOrBlank()) { "Parameter 'subject' is required" }
        require(!body.isNullOrBlank()) { "Parameter 'body' is required" }

        val htmlMode = isHtml ?: false
        val host = envContext.require("SMTP_HOST")
        val port = (envContext.get("SMTP_PORT") ?: "587").toIntOrNull() ?: 587
        val user = envContext.require("SMTP_USER")
        val password = envContext.require("SMTP_PASSWORD")
        val from = envContext.require("SMTP_FROM")

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            when (port) {
                465 -> {
                    // Implicit SSL/TLS
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", "465")
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                }
                25 -> {
                    // Plain SMTP, no encryption
                }
                else -> {
                    // STARTTLS (587 and others)
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
            }
        }

        val session = Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication = PasswordAuthentication(user, password)
            },
        )

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            if (htmlMode) {
                setContent(body, "text/html; charset=UTF-8")
            } else {
                setText(body, "UTF-8")
            }
        }

        Transport.send(message)
        "Email sent successfully to $to"
    }

    override fun name(): String = NAME

    companion object {
        const val NAME = "email-tool-box"
    }
}
