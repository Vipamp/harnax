package com.vipamp.vipclaw.admin.common

import com.vipamp.vipclaw.admin.config.EditionUtil

/**
 * System information class
 * Used to manage system version, edition type and other system-related information
 */
data class SystemInfo(
    /**
     * Current system version number
     */
    val version: String = "1.0.0-SNAPSHOT",

    /**
     * Build time
     */
    val buildTime: String = "",

    /**
     * Git commit hash
     */
    val gitCommit: String = "",

    /**
     * Environment info (e.g., dev, test, prod)
     */
    val edition: String = "",
) {
    companion object {
        /**
         * Get default system info instance
         */
        fun from(editionUtil: EditionUtil): SystemInfo = SystemInfo(
            edition = editionUtil.getCurrentEdition(),
        )
    }
}
