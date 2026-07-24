package com.agnetix.harnax.channel.wecom

/**
 * WeChat Work (WeCom) smart-robot WebSocket protocol frames.
 *
 * Unified frame structure used for all WebSocket communication:
 *   { "cmd": "...", "headers": { "req_id": "..." }, "body": { ... } }
 * Response frames may omit `cmd` and carry `errcode`/`errmsg` instead.
 *
 * Reference: cc-connect platform/wecom/websocket.go protocol.
 */
object WecomFrames {

    const val ENDPOINT = "wss://openws.work.weixin.qq.com"

    const val CMD_SUBSCRIBE = "aibot_subscribe"
    const val CMD_PING = "ping"
    const val CMD_MSG_CALLBACK = "aibot_msg_callback"
    const val CMD_EVENT_CALLBACK = "aibot_event_callback"
    const val CMD_RESPOND_MSG = "aibot_respond_msg"
    const val CMD_SEND_MSG = "aibot_send_msg"

    fun subscribe(reqId: String, botId: String, secret: String): Map<String, Any> = mapOf(
        "cmd" to CMD_SUBSCRIBE,
        "headers" to mapOf("req_id" to reqId),
        "body" to mapOf("bot_id" to botId, "secret" to secret),
    )

    fun ping(reqId: String): Map<String, Any> = mapOf(
        "cmd" to CMD_PING,
        "headers" to mapOf("req_id" to reqId),
    )

    /**
     * Reply to a received message via `aibot_respond_msg` using the stream format.
     * The stream content is a full replacement (not incremental), so we send the
     * complete content in one frame with finish=true. Uses the original callback req_id.
     */
    fun respondMsg(reqId: String, streamId: String, content: String): Map<String, Any> = mapOf(
        "cmd" to CMD_RESPOND_MSG,
        "headers" to mapOf("req_id" to reqId),
        "body" to mapOf(
            "msgtype" to "stream",
            "stream" to mapOf(
                "id" to streamId,
                "finish" to true,
                "content" to content,
            ),
        ),
    )

    /**
     * Proactively send a markdown message via `aibot_send_msg` to a chat.
     */
    fun sendMsg(reqId: String, chatId: String, content: String): Map<String, Any> = mapOf(
        "cmd" to CMD_SEND_MSG,
        "headers" to mapOf("req_id" to reqId),
        "body" to mapOf(
            "chatid" to chatId,
            "msgtype" to "markdown",
            "markdown" to mapOf("content" to content),
        ),
    )
}
