package com.agnetix.harnax.harness.sandbox.plugin

import io.agentscope.harness.agent.sandbox.Sandbox

/**
 * Initializes CLI plugins inside a sandbox container.
 * Called after sandbox creation to inject authentication credentials.
 *
 * Implementations must be idempotent (safe to call multiple times).
 */
interface SandboxPluginInitializer {

    /** Plugin name for logging */
    val pluginName: String

    /**
     * Initialize the plugin inside the sandbox.
     *
     * @param sandbox the running sandbox instance
     * @param adminUrl admin service URL accessible from within the container
     * @param internalSecret service-level authentication secret
     * @return true if initialization succeeded
     */
    fun initialize(sandbox: Sandbox, adminUrl: String, internalSecret: String): Boolean
}
