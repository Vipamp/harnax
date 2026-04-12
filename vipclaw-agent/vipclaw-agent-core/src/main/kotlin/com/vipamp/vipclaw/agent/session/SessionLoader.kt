package com.vipamp.vipclaw.agent.session

import io.agentscope.core.session.InMemorySession
import io.agentscope.core.session.JsonSession
import io.agentscope.core.session.Session
import io.agentscope.core.session.mysql.MysqlSession
import kotlin.io.path.Path

/**
 * @Author: heqingsong
 * @Date: 2026/3/31
 * @Description: SessionLoader
 * @Project: vipclaw
 */
object SessionLoader {

    fun load(sessionConfig: SessionConfig?): Session {
        return if (sessionConfig == null) {
            InMemorySession()
        } else {
            when (sessionConfig) {
                is JsonSessionConfig -> JsonSession(Path(sessionConfig.path))
                is MysqlSessionConfig -> MysqlSession(sessionConfig.toDataSource(), sessionConfig.createIfNotExist)
            }
        }
    }
}
