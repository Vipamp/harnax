package com.agnetix.harnax.channel.sdk.message

/**
 * Rich Message Abstract Base Class
 * Platform-agnostic rich message representation, each platform adaptor is responsible for converting to platform-specific format
 */
sealed class RichMessage

/**
 * Plain Text Message
 */
data class TextRichMessage(
    val content: String,
) : RichMessage()

/**
 * Markdown Format Message
 */
data class MarkdownRichMessage(
    val content: String,
) : RichMessage()

/**
 * Image Message
 * @param imageUrl Image URL
 * @param mediaId Platform media ID (if already uploaded)
 */
data class ImageRichMessage(
    val imageUrl: String,
    val mediaId: String? = null,
) : RichMessage()

/**
 * File Message
 * @param fileUrl File URL
 * @param fileName File name
 * @param mediaId Platform media ID (if already uploaded)
 */
data class FileRichMessage(
    val fileUrl: String,
    val fileName: String,
    val mediaId: String? = null,
) : RichMessage()

/**
 * Card Message (interactive card)
 * @param title Card title
 * @param elements Card content element list
 * @param actions Card action button list
 */
data class CardRichMessage(
    val title: String,
    val elements: List<CardElement> = emptyList(),
    val actions: List<CardAction> = emptyList(),
) : RichMessage()

/**
 * Card Element
 */
sealed class CardElement

/**
 * Text Element
 */
data class TextCardElement(
    val content: String,
) : CardElement()

/**
 * Image Element
 */
data class ImageCardElement(
    val imageUrl: String,
) : CardElement()

/**
 * Markdown Element
 */
data class MarkdownCardElement(
    val content: String,
) : CardElement()

/**
 * Divider Element
 */
object DividerCardElement : CardElement()

/**
 * Note Element (small text description)
 */
data class NoteCardElement(
    val text: String,
) : CardElement()

/**
 * Card Action Button
 */
sealed class CardAction

/**
 * URL Navigation Button
 */
data class UrlCardAction(
    val label: String,
    val url: String,
) : CardAction()

/**
 * Callback Button (triggers callback event)
 */
data class CallbackCardAction(
    val label: String,
    val value: String,
) : CardAction()

/**
 * Composite Rich Message (contains multiple messages)
 */
data class CompositeRichMessage(
    val messages: List<RichMessage>,
) : RichMessage()
