package com.vipamp.vipclaw.admin.annotation

/**
 * 跳过租户过滤注解
 *
 * 在 Mapper 接口方法上添加此注解，该方法的 SQL 查询将不会被 MybatisTenantInterceptor 添加租户过滤条件
 *
 * 使用场景：
 * 1. 系统级查询（如查询所有租户列表）
 * 2. 跨租户统计分析
 * 3. 管理员需要查看全局数据的场景
 *
 * 示例：
 * ```kotlin
 * @SkipTenantFilter
 * fun selectAllTenants(): List<Tenant>
 *
 * @SkipTenantFilter
 * @Select("SELECT COUNT(*) FROM model_provider")
 * fun countAllProviders(): Int
 * ```
 *
 * @author vipamp
 * @since 2026-05-10
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkipTenantFilter
