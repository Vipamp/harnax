package com.agnetix.harnax.admin.config

/**
 * Edition control annotation
 * Used to declare the list of editions supported by an API or Controller
 *
 * Usage example:
 * ```kotlin
 * @RequiresEdition("enterprise", "public")
 * @RestController
 * class SysUserController { ... }
 * ```
 *
 * @property value List of supported editions, e.g., "personal", "enterprise", "public"
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresEdition(vararg val value: String)
