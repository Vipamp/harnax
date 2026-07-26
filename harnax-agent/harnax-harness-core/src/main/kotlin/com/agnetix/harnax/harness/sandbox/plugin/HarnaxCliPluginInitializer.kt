package com.agnetix.harnax.harness.sandbox.plugin

import io.agentscope.harness.agent.sandbox.Sandbox
import org.slf4j.LoggerFactory

/**
 * Initializes the harnax-cli plugin inside a sandbox container.
 * Executes the init.sh script to inject internal secret credentials.
 */
class HarnaxCliPluginInitializer : SandboxPluginInitializer {

    private val log = LoggerFactory.getLogger(HarnaxCliPluginInitializer::class.java)

    override val pluginName: String = "harnax-cli"

    override fun initialize(sandbox: Sandbox, adminUrl: String, internalSecret: String): Boolean {
        // Sanitize inputs: strip single quotes to prevent shell injection
        val safeUrl = adminUrl.replace("'", "")
        val safeSecret = internalSecret.replace("'", "")
        val cmd = "/opt/plugins/harnax-cli/init.sh '$safeUrl' '$safeSecret'"
        return try {
            val result = sandbox.exec(null, cmd, 10)
            log.info("[CliPlugin] harnax-cli initialized successfully: {}", result.stdout().trim())
            true
        } catch (e: Exception) {
            log.warn("[CliPlugin] harnax-cli init failed: {}", e.message)
            false
        }
    }
}
