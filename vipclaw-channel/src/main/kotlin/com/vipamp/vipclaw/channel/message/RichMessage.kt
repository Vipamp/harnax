package com.vipamp.vipclaw.channel.message

/**
 * 富消息抽象基类
 * 平台无关的富消息表示，各平台适配器负责转换为平台特定格式
 */
sealed class RichMessage

/**
 * 纯文本消息
 */
data class TextRichMessage(
    val content: String
) : RichMessage()

/**
 * Markdown 格式消息
 */
data class MarkdownRichMessage(
    val content: String
) : RichMessage()

/**
 * 图片消息
 * @param imageUrl 图片 URL
 * @param mediaId 平台媒体 ID（如果已上传）
 */
data class ImageRichMessage(
    val imageUrl: String,
    val mediaId: String? = null
) : RichMessage()

/**
 * 文件消息
 * @param fileUrl 文件 URL
 * @param fileName 文件名
 * @param mediaId 平台媒体 ID（如果已上传）
 */
data class FileRichMessage(
    val fileUrl: String,
    val fileName: String,
    val mediaId: String? = null
) : RichMessage()

/**
 * 卡片消息（交互式卡片）
 * @param title 卡片标题
 * @param elements 卡片内容元素列表
 * @param actions 卡片操作按钮列表
 */
data class CardRichMessage(
    val title: String,
    val elements: List<CardElement> = emptyList(),
    val actions: List<CardAction> = emptyList()
) : RichMessage()

/**
 * 卡片元素
 */
sealed class CardElement

/**
 * 文本元素
 */
data class TextCardElement(
    val content: String
) : CardElement()

/**
 * 图片元素
 */
data class ImageCardElement(
    val imageUrl: String
) : CardElement()

/**
 * Markdown 元素
 */
data class MarkdownCardElement(
    val content: String
) : CardElement()

/**
 * 分割线元素
 */
object DividerCardElement : CardElement()

/**
 * 备注元素（小字说明）
 */
data class NoteCardElement(
    val text: String
) : CardElement()

/**
 * 卡片操作按钮
 */
sealed class CardAction

/**
 * URL 跳转按钮
 */
data class UrlCardAction(
    val label: String,
    val url: String
) : CardAction()

/**
 * 回调按钮（触发回调事件）
 */
data class CallbackCardAction(
    val label: String,
    val value: String
) : CardAction()

/**
 * 复合富消息（包含多个消息）
 */
data class CompositeRichMessage(
    val messages: List<RichMessage>
) : RichMessage()
