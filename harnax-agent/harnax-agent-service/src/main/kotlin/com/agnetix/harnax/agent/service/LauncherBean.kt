package com.agnetix.harnax.agent.service

import com.agnetix.harnax.agent.adaptor.*
import com.agnetix.harnax.agent.session.MysqlSessionConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * @Description: LauncherBean
 */
@Component
class LauncherConfig {

    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()

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
        createIfNotExist = false,
    )

    // AscopeAgentLauncher bean disabled — HarnessAgentLauncher is now provided by
    // HarnessAutoConfiguration in harnax-harness-core module.
    //
    // @Bean
    // fun createLauncher(
    //     @Autowired chatModelConfigAdaptor: ChatModelConfigAdaptor,
    //     @Autowired mcpConfigAdaptor: McpConfigAdaptor,
    //     @Autowired skillAdaptor: SkillAdaptor,
    //     @Autowired tokenStatAdaptor: TokenStatAdaptor,
    //     @Autowired sessionConfig: SessionConfig,
    //     @Autowired processLogAdaptor: ProcessLogAdaptor,
    //     @Autowired(required = false) toolCallLogAdaptor: ToolCallLogAdaptor,
    //     @Autowired planNoteAdaptor: PlanNoteAdaptor,
    //     @Value("\${local.tmp-dir}") tmpDir: String,
    // ): AscopeAgentLauncher {
    //     System.setProperty("local.tmp-dir", tmpDir)
    //     return AscopeAgentLauncher.initLauncher(
    //         chatModelConfigAdaptor,
    //         mcpConfigAdaptor,
    //         skillAdaptor,
    //         tokenStatAdaptor,
    //         sessionConfig,
    //         processLogAdaptor,
    //         toolCallLogAdaptor,
    //         planNoteAdaptor,
    //         Path(tmpDir),
    //     )
    // }
}
