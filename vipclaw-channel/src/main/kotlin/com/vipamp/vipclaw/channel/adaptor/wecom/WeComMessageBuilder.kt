package com.vipamp.vipclaw.channel.adaptor.wecom

import com.vipamp.vipclaw.channel.message.*

/**
 * 企业微信消息构建器
 * 将平台无关的 RichMessage 转换为企业微信 webhook JSON 格式
 */
object WeComMessageBuilder {

    /**
     * 构建纯文本消息
     */
    fun buildText(content: String): Map<String, Any> {
        return mapOf(
            "msgtype" to "text",
            "text" to mapOf("content" to content)
        )
    }

    /**
     * 构建 Markdown 消息
     */
    fun buildMarkdown(content: String): Map<String, Any> {
        return mapOf(
            "msgtype" to "markdown",
            "markdown" to mapOf("content" to content)
        )
    }

    /**
     * 构建图片消息
     * 注意：需要预先上传图片获取 media_id
     */
    fun buildImage(mediaId: String): Map<String, Any> {
        return mapOf(
            "msgtype" to "image",
            "image" to mapOf("media_id" to mediaId)
        )
    }

    /**
     * 构建文件消息
     * 注意：需要预先上传文件获取 media_id
     */
    fun buildFile(mediaId: String): Map<String, Any> {
        return mapOf(
            "msgtype" to "file",
            "file" to mapOf("media_id" to mediaId)
        )
    }

    /**
     * 构建模板卡片消息
     */
    fun buildTemplateCard(
        title: String,
        elements: List<CardElement>,
        actions: List<CardAction>
    ): Map<String, Any> {
        val cardData = mutableMapOf<String, Any>(
            "main_title" to mapOf("title" to title)
        )

        // 构建内容描述
        val descriptions = elements.filterIsInstance<TextCardElement>()
            .joinToString("\n") { it.content }
        if (descriptions.isNotBlank()) {
            cardData["sub_title_text"] = descriptions
        }

        // 构建跳转 URL
        val urlAction = actions.filterIsInstance<UrlCardAction>().firstOrNull()
        if (urlAction != null) {
            cardData["jump_url"] = urlAction.url
        }

        return mapOf(
            "msgtype" to "template_card",
            "template_card" to cardData
        )
    }

    /**
     * 从 RichMessage 自动构建消息
     */
    fun buildFromRichMessage(richMessage: RichMessage): Map<String, Any> {
        return when (richMessage) {
            is TextRichMessage -> buildText(richMessage.content)
            is MarkdownRichMessage -> buildMarkdown(richMessage.content)
            is ImageRichMessage -> {
                if (richMessage.mediaId.isNullOrBlank()) {
                    throw IllegalArgumentException("WeCom image message requires mediaId")
                }
                buildImage(richMessage.mediaId!!)
            }
            is FileRichMessage -> {
                if (richMessage.mediaId.isNullOrBlank()) {
                    throw IllegalArgumentException("WeCom file message requires mediaId")
                }
                buildFile(richMessage.mediaId!!)
            }
            is CardRichMessage -> buildTemplateCard(
                richMessage.title,
                richMessage.elements,
                richMessage.actions
            )
            is CompositeRichMessage -> {
                // 复合消息只取第一个消息
                if (richMessage.messages.isEmpty()) {
                    buildText("")
                } else {
                    buildFromRichMessage(richMessage.messages.first())
                }
            }
        }
    }
}
