package com.agnetix.harnax.ascopagent

import com.agnetix.harnax.agent.AscopeAgentLauncher
import com.agnetix.harnax.agent.adaptor.*
import com.agnetix.harnax.agent.session.MysqlSessionConfig
import com.agnetix.harnax.agent.session.SessionConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import kotlin.io.path.Path

/**
 * @Description: LauncherBean
 */
@Component
class LauncherConfig {

    @Bean
    fun createSessionConfig(
        @Value($$"${session.jdbc-url}") jdbcUrl: String,
        @Value($$"${session.username}") username: String,
        @Value($$"${session.password}") password: String,
        @Value($$"${session.database-name}") databaseName: String,
    ): MysqlSessionConfig = MysqlSessionConfig(
        jdbcUrl = jdbcUrl,
        username = username,
        password = password,
        databaseName = databaseName,
        tableName = "session_record",
        createIfNotExist = true,
    )

    @Bean
    fun createLauncher(
        @Autowired chatModelConfigAdaptor: ChatModelConfigAdaptor,
        @Autowired mcpConfigAdaptor: McpConfigAdaptor,
        @Autowired skillAdaptor: SkillAdaptor,
        @Autowired tokenStatAdaptor: TokenStatAdaptor,
        @Autowired sessionConfig: SessionConfig,
        @Autowired processLogAdaptor: ProcessLogAdaptor,
        @Autowired(required = false) toolCallLogAdaptor: ToolCallLogAdaptor,
        @Autowired planNoteAdaptor: PlanNoteAdaptor,
        @Value($$"${local.tmp-dir}") tmpDir: String,
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
            planNoteAdaptor,
            Path(tmpDir),
        )
    }
}
