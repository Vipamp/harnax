package com.agnetix.harnax.channel.dingtalk

import com.agnetix.harnax.channel.sdk.message.MarkdownRichMessage
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.agnetix.harnax.channel.sdk.message.TextRichMessage

/**
 * DingTalk Message Builder
 *
 * Builds the JSON payload posted to a bot session webhook.
 * DingTalk bot session webhooks accept these message types:
 * - text:     {"msgtype":"text","text":{"content":"..."}}
 * - markdown: {"msgtype":"markdown","markdown":{"title":"...","text":"..."}}
 */
object DingtalkMessageBuilder {

    private const val DEFAULT_MARKDOWN_TITLE = "Assistant"

    fun buildText(content: String): Map<String, Any> = mapOf(
        "msgtype" to "text",
        "text" to mapOf("content" to content),
    )

    fun buildMarkdown(content: String, title: String = DEFAULT_MARKDOWN_TITLE): Map<String, Any> = mapOf(
        "msgtype" to "markdown",
        "markdown" to mapOf(
            "title" to title,
            "text" to content,
        ),
    )

    fun buildFromRichMessage(richMessage: RichMessage): Map<String, Any> = when (richMessage) {
        is MarkdownRichMessage -> buildMarkdown(richMessage.content)
        is TextRichMessage -> buildText(richMessage.content)
        else -> buildText(richMessage.toString())
    }
}
