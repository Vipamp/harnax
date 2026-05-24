package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.sdk.message.*

/**
 * Feishu Message Builder
 * Converts platform-agnostic RichMessage to Feishu webhook JSON format
 */
object FeishuMessageBuilder {

    /**
     * Build plain text message
     */
    fun buildText(content: String): Map<String, Any> = mapOf(
        "msg_type" to "text",
        "content" to mapOf("text" to content),
    )

    /**
     * Build rich text message (Feishu uses post type to simulate Markdown)
     */
    fun buildPost(title: String, content: String): Map<String, Any> {
        val contentElements = content.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                listOf(mapOf("tag" to "text", "text" to line))
            }

        return mapOf(
            "msg_type" to "post",
            "content" to mapOf(
                "post" to mapOf(
                    "zh_cn" to mapOf(
                        "title" to title,
                        "content" to contentElements,
                    ),
                ),
            ),
        )
    }

    /**
     * Build image message
     */
    fun buildImage(imageKey: String): Map<String, Any> = mapOf(
        "msg_type" to "image",
        "content" to mapOf("image_key" to imageKey),
    )

    /**
     * Build file message
     */
    fun buildFile(fileKey: String): Map<String, Any> = mapOf(
        "msg_type" to "file",
        "content" to mapOf("file_key" to fileKey),
    )

    /**
     * Build interactive card message
     */
    fun buildInteractiveCard(
        title: String,
        elements: List<CardElement>,
        actions: List<CardAction>,
    ): Map<String, Any> {
        val cardElements = mutableListOf<Map<String, Any>>()

        cardElements.add(
            mapOf(
                "tag" to "markdown",
                "content" to "**$title**",
            ),
        )

        elements.forEach { element ->
            when (element) {
                is TextCardElement -> {
                    cardElements.add(
                        mapOf("tag" to "markdown", "content" to element.content),
                    )
                }
                is MarkdownCardElement -> {
                    cardElements.add(
                        mapOf("tag" to "markdown", "content" to element.content),
                    )
                }
                is ImageCardElement -> {
                    cardElements.add(
                        mapOf("tag" to "img", "img_key" to element.imageUrl),
                    )
                }
                is DividerCardElement -> {
                    cardElements.add(
                        mapOf("tag" to "hr"),
                    )
                }
                is NoteCardElement -> {
                    cardElements.add(
                        mapOf(
                            "tag" to "note",
                            "elements" to listOf(
                                mapOf("tag" to "text", "text" to element.text),
                            ),
                        ),
                    )
                }
            }
        }

        if (actions.isNotEmpty()) {
            val buttonElements = actions.mapNotNull { action ->
                when (action) {
                    is UrlCardAction -> mapOf(
                        "tag" to "action",
                        "actions" to listOf(
                            mapOf(
                                "tag" to "button",
                                "text" to mapOf("tag" to "plain_text", "content" to action.label),
                                "url" to action.url,
                                "type" to "primary",
                            ),
                        ),
                    )
                    is CallbackCardAction -> mapOf(
                        "tag" to "action",
                        "actions" to listOf(
                            mapOf(
                                "tag" to "button",
                                "text" to mapOf("tag" to "plain_text", "content" to action.label),
                                "value" to mapOf("action" to action.value),
                            ),
                        ),
                    )
                }
            }
            cardElements.addAll(buttonElements)
        }

        return mapOf(
            "msg_type" to "interactive",
            "card" to mapOf(
                "header" to mapOf(
                    "title" to mapOf("tag" to "plain_text", "content" to title),
                ),
                "elements" to cardElements,
            ),
        )
    }

    /**
     * Auto-build message from RichMessage
     */
    fun buildFromRichMessage(richMessage: RichMessage): Map<String, Any> = when (richMessage) {
        is TextRichMessage -> buildText(richMessage.content)
        is MarkdownRichMessage -> buildPost("消息", richMessage.content)
        is ImageRichMessage -> {
            if (richMessage.mediaId.isNullOrBlank()) {
                throw IllegalArgumentException("Feishu image message requires imageKey (in mediaId field)")
            }
            buildImage(richMessage.mediaId!!)
        }
        is FileRichMessage -> {
            if (richMessage.mediaId.isNullOrBlank()) {
                throw IllegalArgumentException("Feishu file message requires fileKey (in mediaId field)")
            }
            buildFile(richMessage.mediaId!!)
        }
        is CardRichMessage -> buildInteractiveCard(
            richMessage.title,
            richMessage.elements,
            richMessage.actions,
        )
        is CompositeRichMessage -> {
            if (richMessage.messages.isEmpty()) {
                buildText("")
            } else {
                buildFromRichMessage(richMessage.messages.first())
            }
        }
    }
}
