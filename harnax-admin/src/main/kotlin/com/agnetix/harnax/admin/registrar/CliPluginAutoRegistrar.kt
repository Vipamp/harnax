package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.entity.CliPlugin
import com.agnetix.harnax.mapper.CliPluginMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Automatically syncs system-integrated CLI plugin metadata to the database on admin startup.
 *
 * Sync strategy (same as BuiltinToolAutoRegistrar):
 * - New plugins: inserted with status=1, active=1
 * - Existing plugins: only metadata fields are updated (displayName, description, paths, etc.)
 * - status field is NEVER overwritten (admin can manually disable plugins)
 * - Removed plugins are NOT auto-deleted
 */
@Component
class CliPluginAutoRegistrar(
    private val cliPluginMapper: CliPluginMapper,
) {
    private val log = LoggerFactory.getLogger(CliPluginAutoRegistrar::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun syncSystemCliPlugins() {
        log.info("[CliPluginAutoRegistrar] Syncing system CLI plugins to database")
        var successCount = 0
        var failCount = 0

        // === harnax-cli: platform management CLI ===
        try {
            upsertPlugin(
                name = "harnax-cli",
                displayName = "Harnax CLI",
                displayNameZh = "Harnax 命令行",
                description = "Platform management CLI for agent, session, model, tool, MCP, channel, env-variable, scheduler, API key, tenant and user operations",
                binaryPath = "/usr/local/bin/harnax",
                initScript = "/opt/plugins/harnax-cli/init.sh",
                skillDocPath = "/opt/plugins/harnax-cli/SKILL.md",
                healthCheck = "harnax --version",
            )
            successCount++
        } catch (e: Exception) {
            log.error("[CliPluginAutoRegistrar] Failed to sync plugin: harnax-cli", e)
            failCount++
        }

        log.info("[CliPluginAutoRegistrar] Sync complete: {} succeeded, {} failed", successCount, failCount)
    }

    private fun upsertPlugin(
        name: String,
        displayName: String,
        displayNameZh: String,
        description: String,
        binaryPath: String,
        initScript: String,
        skillDocPath: String,
        healthCheck: String,
    ) {
        val plugin = CliPlugin().apply {
            this.tenantId = 1
            this.name = name
            this.displayName = displayName
            this.displayNameZh = displayNameZh
            this.description = description
            this.type = "SYSTEM"
            this.binaryPath = binaryPath
            this.initScript = initScript
            this.skillDocPath = skillDocPath
            this.healthCheck = healthCheck
            this.status = 1
            this.creator = "SYSTEM"
            this.active = 1
            this.createTime = LocalDateTime.now()
            this.updateTime = LocalDateTime.now()
        }
        cliPluginMapper.upsertSystemPlugin(plugin)
        log.debug("[CliPluginAutoRegistrar] Upserted plugin: {}", name)
    }
}
