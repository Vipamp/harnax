package com.vipamp.vipclaw.admin.config

/**
 * 版本控制注解
 * 用于声明 API 或 Controller 支持的版本列表
 * 
 * 使用示例:
 * ```kotlin
 * @RequiresEdition("enterprise", "public")
 * @RestController
 * class SysUserController { ... }
 * ```
 * 
 * @property value 支持的版本列表,如 "personal", "enterprise", "public"
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresEdition(vararg val value: String)
