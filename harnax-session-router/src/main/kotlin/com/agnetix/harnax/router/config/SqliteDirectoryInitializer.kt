package com.agnetix.harnax.router.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.io.File

/**
 * Ensures the parent directory for the SQLite database file exists
 * before HikariCP / DataSource attempts to open a connection.
 *
 * Runs as a [BeanFactoryPostProcessor] so it executes before any
 * regular bean (including the DataSource) is instantiated.
 */
@Configuration
@ConditionalOnExpression(
    $$"'${spring.datasource.url:}'.contains('sqlite')",
)
class SqliteDirectoryInitializer :
    BeanFactoryPostProcessor,
    EnvironmentAware {

    private val log = LoggerFactory.getLogger(SqliteDirectoryInitializer::class.java)
    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val url = environment.getProperty("spring.datasource.url") ?: return
        if (!url.contains("sqlite")) return

        // Extract file path from JDBC URL — strip all known prefixes
        val filePath = url
            .removePrefix("jdbc:sqlite:")
            .removePrefix("jdbc:sqlite:file:")
            .substringBefore("?")
            .trim()

        if (filePath.isBlank() || filePath == ":memory:") return

        val parentDir = File(filePath).parentFile
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                log.info("[SQLite] Created database directory: {}", parentDir.absolutePath)
            } else {
                log.warn(
                    "[SQLite] Failed to create database directory: {}. " +
                        "Set ROUTER_SQLITE_PATH to a writable location.",
                    parentDir.absolutePath,
                )
            }
        }
    }
}
