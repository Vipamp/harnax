package com.vipamp.vipclaw.admin.common

import com.vipamp.vipclaw.admin.config.EditionUtil

/**
 * 系统信息类
 * 用于管理当前系统版本、发行版本类型等系统相关信息
 * 
 * @author heqingsong
 * @date 2026/4/25
 */
data class SystemInfo(
    /**
     * 当前系统版本号
     */
    val version: String = "1.0.0-SNAPSHOT",

    /**
     * 构建时间
     */
    val buildTime: String = "",

    /**
     * Git 提交哈希
     */
    val gitCommit: String = "",

    /**
     * 环境信息 (如: dev, test, prod)
     */
    val edition: String = "",
) {
    companion object {
        /**
         * 获取默认的系统信息实例
         */
        fun from(editionUtil: EditionUtil): SystemInfo {
            return SystemInfo(
                edition = editionUtil.getCurrentEdition()
            )
        }
    }
}