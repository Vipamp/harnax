package com.agnetix.harnax.agent.session

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * Configuration for [io.agentscope.core.state.AgentStateStore] backends.
 *
 * In agentscope 2.0.0, the `Session` interface was replaced by `AgentStateStore`.
 * The key model changed from a single `SessionKey` to a `(userId, sessionId)` pair.
 */
sealed interface SessionConfig

data class MysqlSessionConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val databaseName: String,
    val tableName: String,
    val createIfNotExist: Boolean = true,
) : SessionConfig {

    fun toDataSource(): DataSource {
        val hikariConfig = HikariDataSource().apply {
            jdbcUrl = this@MysqlSessionConfig.jdbcUrl
            username = this@MysqlSessionConfig.username
            password = this@MysqlSessionConfig.password
            driverClassName = "com.mysql.cj.jdbc.Driver"

            // 连接池配置
            maximumPoolSize = 10
            minimumIdle = 5
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000

            // 添加数据库名称到 JDBC URL
            if (!jdbcUrl.contains("/")) {
                jdbcUrl = "$jdbcUrl/$databaseName"
            }

            // MySQL 特定配置
            addDataSourceProperty("useSSL", "false")
            addDataSourceProperty("serverTimezone", "UTC")
            addDataSourceProperty("characterEncoding", "utf8")
            addDataSourceProperty("useUnicode", "true")
        }
        return hikariConfig
    }
}

data class JsonSessionConfig(
    val path: String = "~/tmp",
) : SessionConfig
