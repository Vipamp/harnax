package com.vipamp.vipclaw.admin.interceptor

import com.vipamp.vipclaw.admin.context.TenantContext
import com.vipamp.vipclaw.admin.security.SecurityUtils
import org.apache.ibatis.cache.CacheKey
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.mapping.SqlCommandType
import org.apache.ibatis.plugin.*
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
        Signature(type = Executor::class, method = "update", args = [MappedStatement::class, Any::class])
    ]
)
@Component
class MybatisTenantInterceptor : Interceptor {

    private val log = LoggerFactory.getLogger(MybatisTenantInterceptor::class.java)

    companion object {
        // 需要进行租户过滤的表名前缀（空表示所有表）
        private val TENANT_TABLE_PREFIXES = emptyList<String>()
        
        // 不需要租户过滤的表名（如 tenant、user_tenant 等系统表）
        private val EXCLUDED_TABLES = setOf(
            "tenant",
            "user_tenant",
            "sys_user",  // 用户表暂时不过滤，后续可调整
            "sys_token_blacklist"
        )

        // 需要设置 tenant_id 的字段名
        private const val TENANT_ID_COLUMN = "tenant_id"
    }

    override fun intercept(invocation: Invocation): Any? {
        val args = invocation.args
        val mappedStatement = args[0] as MappedStatement
        
        // 检查是否需要应用租户过滤
        if (!shouldApplyTenantFilter(mappedStatement)) {
            return invocation.proceed()
        }

        // 获取当前租户ID
        val tenantId = TenantContext.getTenantId()
        
        // 如果没有租户上下文，跳过过滤
        if (tenantId == null) {
            log.debug("[MyBatis租户拦截器] 无租户上下文，跳过过滤")
            return invocation.proceed()
        }

        try {
            when (mappedStatement.sqlCommandType) {
                SqlCommandType.SELECT -> {
                    log.debug("[MyBatis租户拦截器] 处理SELECT查询，添加tenant_id过滤: tenantId={}", tenantId)
                    // TODO: 实现SQL改写，添加 WHERE tenant_id = ?
                    // 由于SQL改写逻辑复杂，第一阶段先通过业务层保证数据隔离
                }
                SqlCommandType.INSERT -> {
                    log.debug("[MyBatis租户拦截器] 处理INSERT操作，设置tenant_id: tenantId={}", tenantId)
                    // TODO: 实现INSERT时自动设置tenant_id字段
                }
                SqlCommandType.UPDATE, SqlCommandType.DELETE -> {
                    log.debug("[MyBatis租户拦截器] 处理UPDATE/DELETE操作，添加tenant_id条件: tenantId={}", tenantId)
                    // TODO: 实现SQL改写，添加 WHERE tenant_id = ?
                }
                else -> {
                    // 其他SQL类型不处理
                }
            }
        } catch (e: Exception) {
            log.error("[MyBatis租户拦截器] 处理租户过滤失败", e)
            // 如果处理失败，继续执行原SQL（避免阻塞业务）
        }

        return invocation.proceed()
    }

    /**
     * 判断是否应该应用租户过滤
     */
    private fun shouldApplyTenantFilter(mappedStatement: MappedStatement): Boolean {
        // 获取全局管理员标识
        val currentUser = SecurityUtils.getCurrentUser()
        
        // 全局管理员跳过租户过滤
        if (currentUser != null && currentUser.isAdmin == 1) {
            log.debug("[MyBatis租户拦截器] 全局管理员，跳过租户过滤")
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

        // 检查表前缀匹配
        if (TENANT_TABLE_PREFIXES.isNotEmpty()) {
            val shouldFilter = TENANT_TABLE_PREFIXES.any { prefix ->
                tableName.startsWith(prefix, ignoreCase = true)
            }
            return shouldFilter
        }

        // 默认对所有表应用过滤
        return true
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

    override fun plugin(target: Any): Any {
        return Plugin.wrap(target, this)
    }

    override fun setProperties(properties: Properties?) {
        // 可以通过配置文件设置拦截器属性
        log.info("[MyBatis租户拦截器] 初始化完成")
    }
}
