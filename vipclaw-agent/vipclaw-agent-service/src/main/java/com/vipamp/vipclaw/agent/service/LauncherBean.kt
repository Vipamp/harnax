package com.vipamp.vipclaw.agent.service

import com.vipamp.vipclaw.agent.AscopeAgentLauncher
import com.vipamp.vipclaw.agent.adaptor.*
import com.vipamp.vipclaw.agent.session.MysqlSessionConfig
import com.vipamp.vipclaw.agent.session.SessionConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import kotlin.io.path.Path

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: LauncherBean
 * @Project: vipclaw
 */
@Component
class LauncherConfig {

    @Bean
    fun createSessionConfig(
        @Value($$"${session.jdbc-url}") jdbcUrl: String,
        @Value($$"${session.username}") username: String,
        @Value($$"${session.password}") password: String,
        @Value($$"${session.database-name}") databaseName: String,
    ): MysqlSessionConfig {
        return MysqlSessionConfig(
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            databaseName = databaseName,
            tableName = "session_record",
            createIfNotExist = true
        )
    }

    @Bean
    fun createLauncher(
        @Autowired chatModelConfigAdaptor: ChatModelConfigAdaptor,
        @Autowired mcpConfigAdaptor: McpConfigAdaptor,
        @Autowired skillAdaptor: SkillAdaptor,
        @Autowired tokenStatAdaptor: TokenStatAdaptor,
        @Autowired sessionConfig: SessionConfig,
        @Autowired processLogAdaptor: ProcessLogAdaptor,
        @Autowired(required = false) toolCallLogAdaptor: ToolCallLogAdaptor,
        @Value($$"${local.tmp-dir}") tmpDir: String
    ): AscopeAgentLauncher {
        System.setProperty("local.tmp-dir", tmpDir)
        return AscopeAgentLauncher.initLauncher(
            chatModelConfigAdaptor,
            mcpConfigAdaptor,
            skillAdaptor,
            tokenStatAdaptor,
            sessionConfig,
            processLogAdaptor,
            toolCallLogAdaptor,
            Path(tmpDir)
        )
    }
}
