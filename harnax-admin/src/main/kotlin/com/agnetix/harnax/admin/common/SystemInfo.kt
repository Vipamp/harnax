package com.agnetix.harnax.admin.common

/**
 * System information class
 * Used to expose system version and build information
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
    val environment: String = "",
)