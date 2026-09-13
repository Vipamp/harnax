package com.agnetix.harnax.channel.sdk.service

/**
 * Replies that the pipeline generated itself, rather than the agent.
 *
 * They are shown to the user (a failure must be visible), but they must never enter
 * the session history: the next turn would feed the error text back to the model as if
 * the assistant had really said it, and the model tends to repeat or apologise for it.
 */
object ReplyMarkers {

    const val ROUTER_ERROR_PREFIX = "[Router Error]"

    const val EMPTY_REPLY_PREFIX = "⚠️ AI processing completed but returned no content"

    const val FAILED_PREFIX = "⚠️ AI processing failed"

    const val FILE_UNDELIVERABLE_PREFIX = "📎 Files generated"

    const val FILE_SEND_FAILED_PREFIX = "📎 File not sent"

    const val CONFIRM_HEADER = "⚠️ AI needs to execute the following tools, please confirm:"

    const val CONFIRM_FOOTER = "Reply /approve to confirm, or /deny to reject."

    const val UNSUPPORTED_MESSAGE_PREFIX = "⚠️ Unsupported message type"

    /**
     * Notice for an inbound message this bot cannot read — a file, audio or sticker on a transport
     * that has no download wired up for it.
     *
     * Silence is the wrong default: a user who sent something and got nothing back cannot tell a
     * capability gap from a dead connection, and the operator sees only a debug line. Naming the
     * platform type makes the two sides tell the same story.
     */
    fun unsupportedMessageType(platformType: String?): String {
        val type = platformType?.takeIf { it.isNotBlank() } ?: "unknown"
        return "$UNSUPPORTED_MESSAGE_PREFIX: $type. Only text messages can be handled here."
    }

    /**
     * Notice for a channel that has no file-upload API: the files exist in the agent workspace,
     * so the only honest thing left is to name them and point at the Web UI. One notice per turn
     * rather than one per file, because the user's action is the same for all of them.
     */
    fun fileUndeliverable(
        fileNames: List<String>,
        where: String = "the Web UI",
    ): String = if (fileNames.size == 1) {
        "$FILE_UNDELIVERABLE_PREFIX: ${fileNames[0]} — this channel cannot receive files, please download it from $where."
    } else {
        "$FILE_UNDELIVERABLE_PREFIX: ${fileNames.joinToString(", ")} — this channel cannot receive files, please download them from $where."
    }

    /** Notice for a file this channel can deliver in principle but failed to deliver now. */
    fun fileSendFailed(fileName: String): String = "$FILE_SEND_FAILED_PREFIX: $fileName, please download it from the Web UI."

    /** True when [content] is an error/fallback notice produced by the channel pipeline. */
    fun isSyntheticReply(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith(ROUTER_ERROR_PREFIX) ||
            trimmed.startsWith(EMPTY_REPLY_PREFIX) ||
            trimmed.startsWith(FAILED_PREFIX) ||
            trimmed.startsWith(FILE_UNDELIVERABLE_PREFIX) ||
            trimmed.startsWith(FILE_SEND_FAILED_PREFIX)
    }
}
