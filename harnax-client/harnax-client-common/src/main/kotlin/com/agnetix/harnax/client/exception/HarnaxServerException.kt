package com.agnetix.harnax.client.exception

/**
 * Exception thrown when the Router returns an HTTP error response (4xx/5xx).
 *
 * @property statusCode HTTP status code from the server
 * @property responseBody Raw response body (may be empty)
 */
class HarnaxServerException(
    val statusCode: Int,
    val responseBody: String = "",
    message: String = "Router returned HTTP $statusCode",
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        /**
         * Create from an HTTP status code and response body.
         */
        fun from(statusCode: Int, responseBody: String): HarnaxServerException {
            val msg = "Router returned HTTP $statusCode: ${responseBody.take(500)}"
            return HarnaxServerException(statusCode, responseBody, msg)
        }
    }

    /**
     * Whether this is a client error (4xx).
     */
    val isClientError: Boolean get() = statusCode in 400..499

    /**
     * Whether this is a server error (5xx).
     */
    val isServerError: Boolean get() = statusCode in 500..599
}
