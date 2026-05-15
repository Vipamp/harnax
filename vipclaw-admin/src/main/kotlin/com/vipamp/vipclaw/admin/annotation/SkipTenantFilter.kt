package com.vipamp.vipclaw.admin.annotation

/**
 * Skip tenant filter annotation
 *
 * Add this annotation on Mapper interface methods to prevent MybatisTenantInterceptor from adding tenant filter conditions to SQL queries
 *
 * Use cases:
 * 1. System-level queries (e.g., querying all tenant list)
 * 2. Cross-tenant statistical analysis
 * 3. Administrator scenarios requiring global data access
 *
 * Example:
 * ```kotlin
 * @SkipTenantFilter
 * fun selectAllTenants(): List<Tenant>
 *
 * @SkipTenantFilter
 * @Select("SELECT COUNT(*) FROM model_provider")
 * fun countAllProviders(): Int
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkipTenantFilter
