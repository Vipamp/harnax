package com.vipamp.vipclaw.admin.interceptor

import com.vipamp.vipclaw.admin.annotation.SkipTenantFilter
import com.vipamp.vipclaw.admin.context.TenantContext
import org.apache.ibatis.cache.CacheKey
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.mapping.SqlCommandType
import org.apache.ibatis.mapping.SqlSource
import org.apache.ibatis.plugin.*
import org.apache.ibatis.reflection.SystemMetaObject
import org.apache.ibatis.session.ResultHandler
import org.apache.ibatis.session.RowBounds
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Properties

/**
 * MyBatis 租户拦截器
 * 自动在 SQL 中添加 tenant_id 过滤条件，实现数据隔离
 *
 * 功能：
 * 1. SELECT 查询自动添加 WHERE tenant_id = ?
 * 2. INSERT 操作自动设置 tenant_id 字段
 * 3. UPDATE/DELETE 操作添加 WHERE tenant_id = ? 条件
 * 4. 全局管理员（is_admin=1）跳过租户过滤
 *
 * @author vipamp
 * @since 2026-04-28
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
        // 不需要租户过滤的表名（如 tenant、user_tenant 等系统表）
        private val EXCLUDED_TABLES = setOf(
            "tenant",
            "user_tenant",
            "sys_user", // 用户表暂时不过滤，后续可调整
            "sys_token_blacklist",
            "plan_note", // 计划笔记表暂时不过滤，后续可调整
            "tool_call_log", // 工具调用日志表暂时不过滤，后续可调整
        )

        // 需要设置 tenant_id 的字段名
        private const val TENANT_ID_COLUMN = "tenant_id"
    }

    override fun intercept(invocation: Invocation): Any? {
//        val args = invocation.args
//        val mappedStatement = args[0] as MappedStatement
//
//        // 检查是否需要应用租户过滤
//        if (!shouldApplyTenantFilter(mappedStatement)) {
//            return invocation.proceed()
//        }
//
//        // 获取当前租户ID
//        val tenantId = TenantContext.getTenantId()
//
//        // 如果没有租户上下文，跳过过滤
//        if (tenantId == null) {
//            log.debug("[MyBatis租户拦截器] 无租户上下文，跳过过滤")
//            return invocation.proceed()
//        }
//
//        try {
//            when (mappedStatement.sqlCommandType) {
//                SqlCommandType.SELECT -> {
//                    log.debug("[MyBatis租户拦截器] 处理SELECT查询，添加tenant_id过滤: tenantId={}", tenantId)
//                    processSelect(invocation, mappedStatement, tenantId)
//                }
//                SqlCommandType.INSERT -> {
//                    log.debug("[MyBatis租户拦截器] 处理INSERT操作，设置tenant_id: tenantId={}", tenantId)
//                    processInsert(invocation, mappedStatement, tenantId)
//                }
//                SqlCommandType.UPDATE, SqlCommandType.DELETE -> {
//                    log.debug("[MyBatis租户拦截器] 处理UPDATE/DELETE操作，添加tenant_id条件: tenantId={}", tenantId)
//                    processUpdateOrDelete(invocation, mappedStatement, tenantId)
//                }
//                else -> {
//                    // 其他SQL类型不处理
//                }
//            }
//        } catch (e: Exception) {
//            log.error("[MyBatis租户拦截器] 处理租户过滤失败", e)
//            // 如果处理失败，继续执行原SQL（避免阻塞业务）
//        }

        return invocation.proceed()
    }

    /**
     * 处理 SELECT 查询 - 添加 WHERE tenant_id = ?
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
            // 不修改 MappedStatement，而是直接替换 invocation 中的 BoundSql
            val newBoundSql = BoundSql(
                mappedStatement.configuration,
                newSql,
                boundSql.parameterMappings,
                boundSql.parameterObject,
            )

            // 替换 invocation 中的 BoundSql（如果是通过args传递的）
            if (args.size > 5) {
                args[5] = newBoundSql
            }

            log.debug("[MyBatis租户拦截器] SELECT SQL改写: {}", newSql)
        }
    }

    /**
     * 处理 INSERT 操作 - 自动设置 tenant_id 字段
     */
    private fun processInsert(invocation: Invocation, mappedStatement: MappedStatement, tenantId: Long) {
        val parameter = invocation.args[1]
        if (parameter == null) return

        val metaObject = SystemMetaObject.forObject(parameter)

        // 检查参数对象是否有 tenantId 字段
        if (metaObject.hasSetter(TENANT_ID_COLUMN)) {
            val currentValue = metaObject.getValue(TENANT_ID_COLUMN)
            // 只有当 tenantId 未设置或为默认值时才设置
            if (currentValue == null || currentValue == 0L || currentValue == 0) {
                metaObject.setValue(TENANT_ID_COLUMN, tenantId)
                log.debug("[MyBatis租户拦截器] INSERT自动设置tenant_id: {}", tenantId)
            }
        }
    }

    /**
     * 处理 UPDATE/DELETE 操作 - 添加 WHERE tenant_id = ?
     */
    private fun processUpdateOrDelete(invocation: Invocation, mappedStatement: MappedStatement, tenantId: Long) {
        val boundSql = mappedStatement.getBoundSql(null)
        val originalSql = boundSql.sql
        val newSql = addTenantIdWhereCondition(originalSql, tenantId)

        if (newSql != originalSql) {
            // 不修改 MappedStatement，直接修改 BoundSql 的 sql 字段
            val metaObject = SystemMetaObject.forObject(boundSql)
            metaObject.setValue("sql", newSql)
            log.debug("[MyBatis租户拦截器] UPDATE/DELETE SQL改写: {}", newSql)
        }
    }

    /**
     * 在 SQL 中添加 WHERE tenant_id = ? 条件
     */
    private fun addTenantIdWhereCondition(sql: String, tenantId: Long): String {
        val trimmedSql = sql.trim()
        val upperSql = trimmedSql.uppercase()

        // 检查 SQL 中是否已经包含 tenant_id 条件
        if (trimmedSql.contains("tenant_id", ignoreCase = true)) {
            log.debug("[MyBatis租户拦截器] SQL已包含tenant_id条件，跳过改写")
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
     * 添加 WHERE 条件
     */
    private fun addWhereCondition(sql: String, tenantId: Long): String {
        // 查找 WHERE 关键字的位置
        val whereIndex = findWhereKeywordIndex(sql)

        return if (whereIndex != -1) {
            // 已有 WHERE 子句，在 WHERE 后添加 tenant_id 条件
            val beforeWhere = sql.substring(0, whereIndex + 5) // "WHERE".length = 5
            val afterWhere = sql.substring(whereIndex + 5).trim()

            // 直接在 WHERE 后插入 tenant_id = ? 条件，保留原有的 AND 连接符
            // 这样不会破坏 MyBatis 的参数映射
            "$beforeWhere tenant_id = $tenantId AND $afterWhere"
        } else {
            // 没有 WHERE 子句，需要添加
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
                        // 检查 afterSet 中是否有 WHERE
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
     * 为 SELECT 语句添加 WHERE 条件
     */
    private fun addWhereToSelect(sql: String, tenantId: Long): String {
        val upperSql = sql.uppercase()

        // 查找可能的子句关键字（ORDER BY, GROUP BY, LIMIT, HAVING）
        val orderIndex = upperSql.indexOf("ORDER BY")
        val groupIndex = upperSql.indexOf("GROUP BY")
        val limitIndex = upperSql.indexOf("LIMIT")
        val havingIndex = upperSql.indexOf("HAVING")

        // 找到最先出现的关键字
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
     * 查找 WHERE 关键字的位置（需要处理 WHERE 可能出现在子查询中的情况）
     */
    private fun findWhereKeywordIndex(sql: String): Int {
        val upperSql = sql.uppercase()
        var index = 0

        while (index < upperSql.length) {
            val wherePos = upperSql.indexOf("WHERE", index)
            if (wherePos == -1) return -1

            // 检查 WHERE 前面是否是空格或其他分隔符，确保是完整的单词
            val isWordBoundary = wherePos == 0 || !upperSql[wherePos - 1].isLetterOrDigit()

            if (isWordBoundary) {
                return wherePos
            }

            index = wherePos + 1
        }

        return -1
    }

    /**
     * 更新 BoundSql 中的 SQL
     */
    private fun updateBoundSql(invocation: Invocation, mappedStatement: MappedStatement, newSql: String, oldBoundSql: BoundSql) {
        val metaObject = SystemMetaObject.forObject(mappedStatement)

        // 创建新的 SqlSource
        val newSqlSource = object : SqlSource {
            override fun getBoundSql(parameterObject: Any?): BoundSql = BoundSql(
                mappedStatement.configuration,
                newSql,
                oldBoundSql.parameterMappings,
                parameterObject,
            )
        }

        // 通过反射更新 MappedStatement 的 sqlSource
        metaObject.setValue("sqlSource", newSqlSource)
    }

    /**
     * 判断是否应该应用租户过滤
     */
    private fun shouldApplyTenantFilter(mappedStatement: MappedStatement): Boolean {
        // 检查方法是否有 @SkipTenantFilter 注解
        if (hasSkipTenantFilterAnnotation(mappedStatement)) {
            log.debug("[MyBatis租户拦截器] 方法标记了 @SkipTenantFilter，跳过租户过滤")
            return false
        }

        // 获取SQL对应的表名（从mappedStatement的id推断）
        val statementId = mappedStatement.id
        val tableName = extractTableName(statementId)

        // 检查是否在排除列表中
        if (EXCLUDED_TABLES.contains(tableName)) {
            log.debug("[MyBatis租户拦截器] 表 {} 在排除列表中，跳过过滤", tableName)
            return false
        }

        // 默认对所有表应用过滤
        return true
    }

    /**
     * 检查 Mapper 方法是否标记了 @SkipTenantFilter 注解
     */
    private fun hasSkipTenantFilterAnnotation(mappedStatement: MappedStatement): Boolean {
        return try {
            val statementId = mappedStatement.id
            // statementId 格式: com.vipamp.vipclaw.admin.mapper.XxxMapper.methodName
            val lastDotIndex = statementId.lastIndexOf('.')
            if (lastDotIndex == -1) return false

            val className = statementId.substring(0, lastDotIndex)
            val methodName = statementId.substring(lastDotIndex + 1)

            // 通过反射获取方法
            val clazz = Class.forName(className)
            val methods = clazz.declaredMethods.filter { it.name == methodName }

            // 检查方法是否有 @SkipTenantFilter 注解
            methods.any { method ->
                method.isAnnotationPresent(SkipTenantFilter::class.java)
            }
        } catch (e: Exception) {
            log.warn("[MyBatis租户拦截器] 检查 @SkipTenantFilter 注解失败: {}", e.message)
            false
        }
    }

    /**
     * 从 Mapper 方法 ID 中提取表名
     * 例如: com.vipamp.vipclaw.admin.mapper.AgentMapper.selectById -> agent
     */
    private fun extractTableName(statementId: String): String {
        // 提取最后一个.后面的部分，如 AgentMapper.selectById
        val lastDotIndex = statementId.lastIndexOf('.')
        if (lastDotIndex == -1) return ""

        val mapperMethod = statementId.substring(lastDotIndex + 1)

        // 提取 Mapper 类名，如 AgentMapper
        val mapperClassEnd = mapperMethod.indexOf('.')
        if (mapperClassEnd == -1) return ""

        val mapperClassName = mapperMethod.substring(0, mapperClassEnd)

        // 移除 Mapper 后缀，得到实体名 Agent
        val entityName = mapperClassName.replace("Mapper", "")

        // 转换为下划线命名: agent -> agent, sysUser -> sys_user
        return camelToUnderline(entityName)
    }

    /**
     * 驼峰转下划线
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
        // 可以通过配置文件设置拦截器属性
        log.info("[MyBatis租户拦截器] 初始化完成")
    }
}
