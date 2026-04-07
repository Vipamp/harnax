package com.vipamp.vipclaw.common.log

import java.util.logging.Logger

/**
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Description: LoggerExternal
 * @Project: vipclaw
 */
inline fun <reified T> T.logger(): Logger {
    return Logger.getLogger(T::class.java.name)
}
