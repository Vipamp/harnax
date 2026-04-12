package com.vipamp.vipclaw.common.log

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Description: LoggerExternal
 * @Project: vipclaw
 */
inline fun <reified T> T.logger(): Logger {
    return LoggerFactory.getLogger(T::class.java)
}
