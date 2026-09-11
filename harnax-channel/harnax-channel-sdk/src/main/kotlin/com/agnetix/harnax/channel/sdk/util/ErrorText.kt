package com.agnetix.harnax.channel.sdk.util

/**
 * Normalises transport error text before it is stored on a connection state.
 *
 * Error strings reach surfaces that platform exception messages were never meant for:
 * the `/actuator/channels` endpoint and the connection health details, both of which are
 * readable without authentication. Channel platforms pass credentials as query parameters
 * (`app_secret`, `access_token`), and SDK exception messages frequently echo the failing URL,
 * so anything reaching those surfaces is scrubbed first.
 */
object ErrorText {

    private const val MAX_LENGTH = 500

    private val SECRET_PARAM = Regex(
        "(?i)\\b(app_secret|appsecret|app-key|appkey|access_key|accesskey|access_token|accesstoken" +
            "|tenant_access_token|corp_access_token|webhook_token|token|secret|password|pwd|api_key|apikey)\\s*=\\s*[^&\\s\"',;]+",
    )

    /** Replaces credential values with `***` and caps the length so no unbounded text is retained. */
    fun sanitize(error: String): String {
        val scrubbed = SECRET_PARAM.replace(error) { match ->
            match.groupValues[1] + "=***"
        }
        val trimmed = scrubbed.trim()
        return if (trimmed.length <= MAX_LENGTH) trimmed else trimmed.take(MAX_LENGTH) + "..."
    }
}
