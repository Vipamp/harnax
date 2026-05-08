package com.vipamp.vipclaw.channel.adaptor.dingtalk

import com.vipamp.vipclaw.channel.message.*

/**
 * 钉钉消息构建器
 * 将平台无关的 RichMessage 转换为钉钉 webhook JSON 格式
 */
object DingTalkMessageBuilder {

    /**
     * 构建纯文本消息
     */
    fun buildText(content: String): Map<String, Any> = mapOf(
        "msgtype" to "text",
        "text" to mapOf("content" to content),
    )

    /**
     * 构建 Markdown 消息
     * 钉钉 Markdown 需要 title 和 text 两个字段
     */
    fun buildMarkdown(title: String, content: String): Map<String, Any> = mapOf(
        "msgtype" to "markdown",
        "markdown" to mapOf(
            "title" to title,
            "text" to content,
        ),
    )

    /**
     * 构建图片消息
     * 注意：需要预先上传图片获取 mediaId
     */
    fun buildImage(mediaId: String, imageUrl: String? = null): Map<String, Any> = mapOf(
        "msgtype" to "image",
        "image" to mapOf(
            "photoURL" to (imageUrl ?: ""),
            "mediaId" to mediaId,
        ),
    )

    /**
     * 构建文件消息
     * 注意：钉钉 webhook 不支持直接发送文件，需要转换为文本链接
     */
    fun buildFile(fileName: String, fileUrl: String): Map<String, Any> = buildMarkdown(
        title = "文件分享",
        content = "### [$fileName]($fileUrl)",
    )

    /**
     * 构建整体跳转 ActionCard 消息
     */
    fun buildActionCard(
        title: String,
        content: String,
        singleTitle: String,
        singleURL: String,
    ): Map<String, Any> = mapOf(
        "msgtype" to "actionCard",
        "actionCard" to mapOf(
            "title" to title,
            "text" to content,
            "singleTitle" to singleTitle,
            "singleURL" to singleURL,
        ),
    )

    /**
     * 构建独立跳转 ActionCard 消息（带多个按钮）
     */
    fun buildActionCardWithButtons(
        title: String,
        content: String,
        buttons: List<DingTalkButton>,
    ): Map<String, Any> {
        val btnList = buttons.map { btn ->
            mapOf(
                "title" to btn.title,
                "actionURL" to btn.actionURL,
            )
        }

        return mapOf(
            "msgtype" to "actionCard",
            "actionCard" to mapOf(
                "title" to title,
                "text" to content,
                "btnOrientation" to "0", // 0: 按钮竖直排列，1: 横向排列
                "btns" to btnList,
            ),
        )
    }

    /**
     * 构建 FeedCard 消息（多条图文）
     */
    fun buildFeedCard(links: List<DingTalkFeedLink>): Map<String, Any> {
        val feedLinks = links.map { link ->
            mapOf(
                "title" to link.title,
                "messageURL" to link.url,
                "picURL" to link.picUrl,
            )
        }

        return mapOf(
            "msgtype" to "feedCard",
            "feedCard" to mapOf("links" to feedLinks),
        )
    }

    /**
     * 从 RichMessage 自动构建消息
     */
    fun buildFromRichMessage(richMessage: RichMessage): Map<String, Any> = when (richMessage) {
        is TextRichMessage -> buildText(richMessage.content)
        is MarkdownRichMessage -> buildMarkdown("消息", richMessage.content)
        is ImageRichMessage -> {
            if (richMessage.mediaId.isNullOrBlank()) {
                throw IllegalArgumentException("DingTalk image message requires mediaId")
            }
            buildImage(richMessage.mediaId!!, richMessage.imageUrl)
        }
        is FileRichMessage -> buildFile(richMessage.fileName, richMessage.fileUrl)
        is CardRichMessage -> {
            // 钉钉卡片消息转换为 ActionCard
            val content = richMessage.elements.filterIsInstance<TextCardElement>()
                .joinToString("\n") { it.content }

            val singleAction = richMessage.actions.filterIsInstance<UrlCardAction>().firstOrNull()
            if (singleAction != null && richMessage.actions.size == 1) {
                buildActionCard(
                    title = richMessage.title,
                    content = content,
                    singleTitle = singleAction.label,
                    singleURL = singleAction.url,
                )
            } else {
                val buttons = richMessage.actions.filterIsInstance<UrlCardAction>()
                    .map { DingTalkButton(it.label, it.url) }
                buildActionCardWithButtons(
                    title = richMessage.title,
                    content = content,
                    buttons = buttons,
                )
            }
        }
        is CompositeRichMessage -> {
            if (richMessage.messages.isEmpty()) {
                buildText("")
            } else {
                buildFromRichMessage(richMessage.messages.first())
            }
        }
    }
}

/**
 * 钉钉按钮
 */
data class DingTalkButton(
    val title: String,
    val actionURL: String,
)

/**
 * 钉钉 FeedCard 链接
 */
data class DingTalkFeedLink(
    val title: String,
    val url: String,
    val picUrl: String,
)
