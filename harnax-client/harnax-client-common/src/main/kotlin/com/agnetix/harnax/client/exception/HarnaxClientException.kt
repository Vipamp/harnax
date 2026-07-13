package com.agnetix.harnax.client.exception

/**
 * Exception thrown by the Harnax client SDK.
 *
 * Base class for all client-side errors including network failures,
 * serialization issues, and configuration problems.
 */
open class HarnaxClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception for connection-level failures (timeout, DNS resolution, refused connection).
 */
class HarnaxConnectionException(
    message: String,
    cause: Throwable? = null,
) : HarnaxClientException(message, cause)

/**
 * Exception for request serialization or preparation errors.
 */
class HarnaxRequestException(
    message: String,
    cause: Throwable? = null,
) : HarnaxClientException(message, cause)
