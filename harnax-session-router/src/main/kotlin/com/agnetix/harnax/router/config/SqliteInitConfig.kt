package com.agnetix.harnax.router.config

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import javax.annotation.PostConstruct
import javax.sql.DataSource

/**
 * SQLite 初始化配置
 * 
 * 仅在本地模式下激活（数据源 URL 包含 sqlite 且 Flyway 未启用）
 * 由于 Flyway 社区版不支持 SQLite，这里手动执行建表 SQL
 */
@Configuration
@ConditionalOnExpression(
    "'\${spring.datasource.url}'.contains('sqlite') and !'\${spring.flyway.enabled:true}'.equals('true')"
)
class SqliteInitConfig(
    private val dataSource: DataSource,
) {

    private val log = LoggerFactory.getLogger(SqliteInitConfig::class.java)

    @PostConstruct
    fun initDatabase() {
        log.info("[SQLite] Initializing database schema...")
        
        try {
            // 读取建表 SQL
            val sqlResource = ClassPathResource("db/sqlite-init.sql")
            val sql = sqlResource.inputStream.bufferedReader().readText()
            
            // 执行建表
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    // SQLite 支持多条语句用分号分隔
                    sql.split(";")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { statement ->
                            try {
                                stmt.execute(statement)
                            } catch (e: Exception) {
                                // 忽略 "already exists" 错误
                                val msg = e.message ?: ""
                                if (!msg.contains("already exists", ignoreCase = true)) {
                                    throw e
                                }
                            }
                        }
                }
            }
            
            log.info("[SQLite] Database schema initialized successfully")
        } catch (e: Exception) {
            log.error("[SQLite] Failed to initialize database schema", e)
            throw RuntimeException("Failed to initialize SQLite database", e)
        }
    }
}
