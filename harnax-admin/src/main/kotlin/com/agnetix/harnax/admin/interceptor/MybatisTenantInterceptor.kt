package com.agnetix.harnax.admin.interceptor

import com.agnetix.harnax.admin.annotation.SkipTenantFilter
import org.apache.ibatis.cache.CacheKey
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.mapping.SqlSource
import org.apache.ibatis.plugin.*
import org.apache.ibatis.reflection.SystemMetaObject
import org.apache.ibatis.session.ResultHandler
import org.apache.ibatis.session.RowBounds
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Properties

/**
 * MyBatis tenant interceptor
 * Automatically add tenant_id filter condition to SQL to achieve data isolation
 *
 * Features:
 * 1. SELECT query automatically adds WHERE tenant_id = ?
 * 2. INSERT operation automatically sets tenant_id field
 * 3. UPDATE/DELETE operation adds WHERE tenant_id = ? condition
 * 4. Global admin (is_admin=1) skips tenant filtering
 */
@Intercepts(
    value = [
        Signature(type = Executor::class, method = "query", args = [MappedStatement::class, Any::class, RowBounds::class, ResultHandler::class]),
        Signature(type = Executor::class, method = "query", args = [MappedStatement::class, Any::class, RowBounds::class, ResultHandler::class, CacheKey::class, BoundSql::class]),
        Signature(type = Executor::class, method = "update", args = [MappedStatement::class, Any::class]),
    ],
)
@Component
class MybatisTenantInterceptor : Interceptor {

    private val log = LoggerFactory.getLogger(MybatisTenantInterceptor::class.java)

    companion object {
        // Tables that don't need tenant filtering (e.g., tenant, user_tenant and other system tables)
        private val EXCLUDED_TABLES = setOf(
            "tenant",
            "user_tenant",
            "sys_user", // User table temporarily not filtered, can be adjusted later
            "sys_token_blacklist",
            "plan_note", // Plan note table temporarily not filtered, can be adjusted later
            "tool_call_log", // Tool call log table temporarily not filtered, can be adjusted later
        )

        // Field names that need to set tenant_id
        private const val TENANT_ID_COLUMN = "tenant_id"
    }

    override fun intercept(invocation: Invocation): Any? {
//        val args = invocation.args
//        val mappedStatement = args[0] as MappedStatement
//
//        // Check if tenant filter should be applied
//        if (!shouldApplyTenantFilter(mappedStatement)) {
//            return invocation.proceed()
//        }
//
//        // Get current tenant ID
//        val tenantId = TenantContext.getTenantId()
//
//        // If no tenant context, skip filtering
//        if (tenantId == null) {
//            log.debug("[MyBatis Tenant Interceptor] No tenant context, skip filtering")
//            return invocation.proceed()
//        }
//
//        try {
//            when (mappedStatement.sqlCommandType) {
//                SqlCommandType.SELECT -> {
//                    log.debug("[MyBatis Tenant Interceptor] Processing SELECT query, add tenant_id filter: tenantId={}", tenantId)
//                    processSelect(invocation, mappedStatement, tenantId)
//                }
//                SqlCommandType.INSERT -> {
//                    log.debug("[MyBatis Tenant Interceptor] Processing INSERT operation, set tenant_id: tenantId={}", tenantId)
//                    processInsert(invocation, mappedStatement, tenantId)
//                }
//                SqlCommandType.UPDATE, SqlCommandType.DELETE -> {
//                    log.debug("[MyBatis Tenant Interceptor] Processing UPDATE/DELETE operation, add tenant_id condition: tenantId={}", tenantId)
//                    processUpdateOrDelete(invocation, mappedStatement, tenantId)
//                }
//                else -> {
//                    // Other SQL types not handled
//                }
//            }
//        } catch (e: Exception) {
//            log.error("[MyBatis Tenant Interceptor] Failed to process tenant filtering", e)
//            // If processing fails, continue with original SQL (avoid blocking business)
//        }

        return invocation.proceed()
    }

    /**
     * Process SELECT query - add WHERE tenant_id = ?
     */
    private fun processSelect(invocation: Invocation, mappedStatement: MappedStatement, tenantId: Long) {
        val args = invocation.args
        val boundSql = if (args.size > 5) {
            args[5] as BoundSql
        } else {
            mappedStatement.getBoundSql(null)
        }

        val originalSql = boundSql.sql
        val newSql = addTenantIdWhereCondition(originalSql, tenantId)

        if (newSql != originalSql) {
            // Don't modify MappedStatement, directly replace BoundSql in invocation
            val newBoundSql = BoundSql(
                mappedStatement.configuration,
                newSql,
                boundSql.parameterMappings,
                boundSql.parameterObject,
            )

            // Replace BoundSql in invocation (if passed via args)
            if (args.size > 5) {
                args[5] = newBoundSql
            }

            log.debug("[MyBatis Tenant Interceptor] SELECT SQL rewritten: {}", newSql)
        }
    }

    /**
     * Process INSERT operation - automatically set tenant_id field
     */
    private fun processInsert(invocation: Invocation, mappedStatement: MappedStatement, tenantId: Long) {
        val parameter = invocation.args[1]
        if (parameter == null) return

        val metaObject = SystemMetaObject.forObject(parameter)

        // Check if parameter object has tenantId field
        if (metaObject.hasSetter(TENANT_ID_COLUMN)) {
            val currentValue = metaObject.getValue(TENANT_ID_COLUMN)
            // Only set tenantId when it's not set or is default value
            if (currentValue == null || currentValue == 0L || currentValue == 0) {
                metaObject.setValue(TENANT_ID_COLUMN, tenantId)
                log.debug("[MyBatis Tenant Interceptor] INSERT auto-set tenant_id: {}", tenantId)
            }
        }
    }

    /**
     * Process UPDATE/DELETE operation - add WHERE tenant_id = ?
     */
    private fun processUpdateOrDelete(invocation: Invocation, mappedStatement: MappedStatement, tenantId: Long) {
        val boundSql = mappedStatement.getBoundSql(null)
        val originalSql = boundSql.sql
        val newSql = addTenantIdWhereCondition(originalSql, tenantId)

        if (newSql != originalSql) {
            // Don't modify MappedStatement, directly modify BoundSql's sql field
            val metaObject = SystemMetaObject.forObject(boundSql)
            metaObject.setValue("sql", newSql)
            log.debug("[MyBatis Tenant Interceptor] UPDATE/DELETE SQL rewritten: {}", newSql)
        }
    }

    /**
     * Add WHERE tenant_id = ? condition to SQL
     */
    private fun addTenantIdWhereCondition(sql: String, tenantId: Long): String {
        val trimmedSql = sql.trim()
        val upperSql = trimmedSql.uppercase()

        // Check if SQL already contains tenant_id condition
        if (trimmedSql.contains("tenant_id", ignoreCase = true)) {
            log.debug("[MyBatis Tenant Interceptor] SQL already contains tenant_id condition, skip rewriting")
            return sql
        }

        return when {
            upperSql.startsWith("SELECT") -> {
                addWhereCondition(trimmedSql, tenantId)
            }
            upperSql.startsWith("UPDATE") -> {
                addWhereCondition(trimmedSql, tenantId)
            }
            upperSql.startsWith("DELETE") -> {
                addWhereCondition(trimmedSql, tenantId)
            }
            else -> sql
        }
    }

    /**
     * Add WHERE condition
     */
    private fun addWhereCondition(sql: String, tenantId: Long): String {
        // Find WHERE keyword position
        val whereIndex = findWhereKeywordIndex(sql)

        return if (whereIndex != -1) {
            // Already has WHERE clause, add tenant_id condition after WHERE
            val beforeWhere = sql.substring(0, whereIndex + 5) // "WHERE".length = 5
            val afterWhere = sql.substring(whereIndex + 5).trim()

            // Directly insert tenant_id = ? condition after WHERE, keep original AND connector
            // This won't break MyBatis parameter mapping
            "$beforeWhere tenant_id = $tenantId AND $afterWhere"
        } else {
            // No WHERE clause, need to add
            when {
                sql.uppercase().startsWith("SELECT") -> {
                    addWhereToSelect(sql, tenantId)
                }
                sql.uppercase().startsWith("UPDATE") -> {
                    // UPDATE table SET ... WHERE tenant_id = ?
                    val setIndex = sql.uppercase().indexOf("SET")
                    if (setIndex != -1) {
                        val beforeSet = sql.substring(0, setIndex + 3)
                        val afterSet = sql.substring(setIndex + 3)
                        // Check if afterSet contains WHERE
                        val whereInAfterSet = findWhereKeywordIndex(afterSet)
                        if (whereInAfterSet != -1) {
                            val beforeWhere = afterSet.substring(0, whereInAfterSet + 5)
                            val afterWhere = afterSet.substring(whereInAfterSet + 5)
                            "$beforeSet $beforeWhere tenant_id = $tenantId AND $afterWhere"
                        } else {
                            "$beforeSet $afterSet WHERE tenant_id = $tenantId"
                        }
                    } else {
                        sql
                    }
                }
                sql.uppercase().startsWith("DELETE") -> {
                    // DELETE FROM table WHERE tenant_id = ?
                    val fromIndex = sql.uppercase().indexOf("FROM")
                    if (fromIndex != -1) {
                        val afterFrom = sql.substring(fromIndex + 4)
                        val whereIndexInAfter = findWhereKeywordIndex(afterFrom)
                        if (whereIndexInAfter != -1) {
                            val beforeWhere = afterFrom.substring(0, whereIndexInAfter + 5)
                            val afterWhere = afterFrom.substring(whereIndexInAfter + 5)
                            "${sql.substring(0, fromIndex + 4)} $beforeWhere tenant_id = $tenantId AND $afterWhere"
                        } else {
                            "$sql WHERE tenant_id = $tenantId"
                        }
                    } else {
                        sql
                    }
                }
                else -> sql
            }
        }
    }

    /**
     * Add WHERE condition for SELECT statement
     */
    private fun addWhereToSelect(sql: String, tenantId: Long): String {
        val upperSql = sql.uppercase()

        // Find possible clause keywords (ORDER BY, GROUP BY, LIMIT, HAVING)
        val orderIndex = upperSql.indexOf("ORDER BY")
        val groupIndex = upperSql.indexOf("GROUP BY")
        val limitIndex = upperSql.indexOf("LIMIT")
        val havingIndex = upperSql.indexOf("HAVING")

        // Find the first appearing keyword
        val indices = listOfNotNull(
            if (orderIndex != -1) orderIndex else null,
            if (groupIndex != -1) groupIndex else null,
            if (limitIndex != -1) limitIndex else null,
            if (havingIndex != -1) havingIndex else null,
        )

        val insertIndex = if (indices.isNotEmpty()) indices.min() else -1

        return if (insertIndex != -1) {
            val beforeClause = sql.substring(0, insertIndex)
            val afterClause = sql.substring(insertIndex)
            "$beforeClause WHERE tenant_id = $tenantId $afterClause"
        } else {
            "$sql WHERE tenant_id = $tenantId"
        }
    }

    /**
     * Find WHERE keyword position (need to handle WHERE appearing in subqueries)
     */
    private fun findWhereKeywordIndex(sql: String): Int {
        val upperSql = sql.uppercase()
        var index = 0

        while (index < upperSql.length) {
            val wherePos = upperSql.indexOf("WHERE", index)
            if (wherePos == -1) return -1

            // Check if WHERE is preceded by space or other delimiter to ensure it's a complete word
            val isWordBoundary = wherePos == 0 || !upperSql[wherePos - 1].isLetterOrDigit()

            if (isWordBoundary) {
                return wherePos
            }

            index = wherePos + 1
        }

        return -1
    }

    /**
     * Update SQL in BoundSql
     */
    private fun updateBoundSql(invocation: Invocation, mappedStatement: MappedStatement, newSql: String, oldBoundSql: BoundSql) {
        val metaObject = SystemMetaObject.forObject(mappedStatement)

        // Create new SqlSource
        val newSqlSource = object : SqlSource {
            override fun getBoundSql(parameterObject: Any?): BoundSql = BoundSql(
                mappedStatement.configuration,
                newSql,
                oldBoundSql.parameterMappings,
                parameterObject,
            )
        }

        // Update MappedStatement's sqlSource via reflection
        metaObject.setValue("sqlSource", newSqlSource)
    }

    /**
     * Determine if tenant filter should be applied
     */
    private fun shouldApplyTenantFilter(mappedStatement: MappedStatement): Boolean {
        // Check if method has @SkipTenantFilter annotation
        if (hasSkipTenantFilterAnnotation(mappedStatement)) {
            log.debug("[MyBatis Tenant Interceptor] Method marked with @SkipTenantFilter, skipping tenant filter")
            return false
        }

        // Get table name for SQL (inferred from mappedStatement id)
        val statementId = mappedStatement.id
        val tableName = extractTableName(statementId)

        // Check if in exclusion list
        if (EXCLUDED_TABLES.contains(tableName)) {
            log.debug("[MyBatis Tenant Interceptor] Table {} in exclusion list, skipping filter", tableName)
            return false
        }

        // Apply filter to all tables by default
        return true
    }

    /**
     * Check if Mapper method is marked with @SkipTenantFilter annotation
     */
    private fun hasSkipTenantFilterAnnotation(mappedStatement: MappedStatement): Boolean {
        return try {
            val statementId = mappedStatement.id
            // statementId format: com.agnetix.harnax.admin.mapper.XxxMapper.methodName
            val lastDotIndex = statementId.lastIndexOf('.')
            if (lastDotIndex == -1) return false

            val className = statementId.substring(0, lastDotIndex)
            val methodName = statementId.substring(lastDotIndex + 1)

            // Get method via reflection
            val clazz = Class.forName(className)
            val methods = clazz.declaredMethods.filter { it.name == methodName }

            // Check if method has @SkipTenantFilter annotation
            methods.any { method ->
                method.isAnnotationPresent(SkipTenantFilter::class.java)
            }
        } catch (e: Exception) {
            log.warn("[MyBatis Tenant Interceptor] Failed to check @SkipTenantFilter annotation: {}", e.message)
            false
        }
    }

    /**
     * Extract table name from Mapper method ID
     * Example: com.agnetix.harnax.admin.mapper.AgentMapper.selectById -> agent
     */
    private fun extractTableName(statementId: String): String {
        // Extract part after last dot, e.g., AgentMapper.selectById
        val lastDotIndex = statementId.lastIndexOf('.')
        if (lastDotIndex == -1) return ""

        val mapperMethod = statementId.substring(lastDotIndex + 1)

        // Extract Mapper class name, e.g., AgentMapper
        val mapperClassEnd = mapperMethod.indexOf('.')
        if (mapperClassEnd == -1) return ""

        val mapperClassName = mapperMethod.substring(0, mapperClassEnd)

        // Remove Mapper suffix to get entity name Agent
        val entityName = mapperClassName.replace("Mapper", "")

        // Convert to snake_case: agent -> agent, sysUser -> sys_user
        return camelToUnderline(entityName)
    }

    /**
     * Convert camelCase to snake_case
     */
    private fun camelToUnderline(name: String): String {
        if (name.isBlank()) return name

        val result = StringBuilder()
        result.append(name[0].lowercaseChar())

        for (i in 1 until name.length) {
            val c = name[i]
            if (c.isUpperCase()) {
                result.append('_')
                result.append(c.lowercaseChar())
            } else {
                result.append(c)
            }
        }

        return result.toString()
    }

    override fun plugin(target: Any): Any = Plugin.wrap(target, this)

    override fun setProperties(properties: Properties?) {
        // Can configure interceptor properties via configuration file
        log.info("[MyBatis Tenant Interceptor] Initialization complete")
    }
}
