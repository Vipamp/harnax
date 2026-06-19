package com.agnetix.harnax.auth

/**
 * Marks an endpoint or controller as accessible only by internal services
 * (callerType = INTERNAL_SERVICE). External API key callers will be rejected
 * with 403 Forbidden.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class InternalOnly
