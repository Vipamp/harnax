package com.agnetix.harnax.auth

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequireScope(
    vararg val value: String,
    val internalOnly: Boolean = false,
)
