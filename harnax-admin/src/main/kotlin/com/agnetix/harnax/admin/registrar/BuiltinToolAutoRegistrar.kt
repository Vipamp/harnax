package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolEnvParam
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.tools.sdk.ToolEnvParamDescriptor
import com.agnetix.harnax.tools.sdk.registry.ToolRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Automatically syncs built-in ToolBox metadata to the database when the admin service starts up.
 *
 * Each @Tool method in a ToolBox becomes a separate agent_tool record, enabling
 * independent enable/disable, needConfirm, and envParamDefs per method.
 *
 * Sync strategy:
 * - New tools: inserted with status=1, active=1
 * - Existing tools: only metadata fields are updated (displayName, description, readOnly, etc.)
 * - status field is NEVER overwritten (admin can manually disable tools)
 * - Removed ToolBox classes are NOT auto-deleted (admin must manually delete in UI)
 * - Env param defs from @ToolMeta.envParamDefs are synced per method to agent_tool_env_param
 */
@Component
class BuiltinToolAutoRegistrar(
    private val toolRegistry: ToolRegistry,
    private val agentToolMapper: AgentToolMapper,
    private val agentToolEnvParamMapper: AgentToolEnvParamMapper,
) {
    private val log = LoggerFactory.getLogger(BuiltinToolAutoRegistrar::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun syncBuiltinTools() {
        val allMeta = toolRegistry.getAllToolMeta()
        if (allMeta.isEmpty()) {
            log.info("[BuiltinToolAutoRegistrar] No @Tool annotated methods found, skipping sync")
            return
        }

        log.info("[BuiltinToolAutoRegistrar] Syncing {} builtin tool groups to database", allMeta.size)
        var successCount = 0
        var failCount = 0

        for ((beanName, meta) in allMeta) {
            try {
                // Phase 1: Upsert one agent_tool record per @Tool method
                for (method in meta.methods) {
                    // Build requiredEnvParamKeys JSON from this method's envParamDefs
                    val requiredKeys = method.envParamDescriptors.filter { it.required }.map { it.key }
                    val requiredKeysJson = if (requiredKeys.isNotEmpty()) {
                        requiredKeys.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
                    } else {
                        null
                    }

                    val agentTool = AgentTool().apply {
                        tenantId = DEFAULT_TENANT_ID
                        name = method.toolName
                        displayName = method.displayName.ifBlank { method.toolName }
                        displayNameZh = method.displayNameZh.ifBlank { null }
                        description = method.description
                        type = TOOL_TYPE_BUILTIN
                        this.beanName = beanName
                        methodName = method.methodName
                        readOnly = if (method.readOnly) 1 else 0
                        needConfirm = if (method.needConfirm) 1 else 0
                        isRequired = if (method.isRequired) 1 else 0
                        requiredEnvParamKeys = requiredKeysJson
                        timeoutSeconds = if (method.timeoutSeconds > 0) method.timeoutSeconds else DEFAULT_TIMEOUT
                        isPublic = if (method.isPublic) 1 else 0
                        creator = SYSTEM_CREATOR
                        status = 1
                        active = 1
                    }

                    agentToolMapper.upsertBuiltinTool(agentTool)
                }

                // Phase 2: Load all tool records for this bean ONCE after upsert (avoids N+1 query)
                val toolRecordsByName = agentToolMapper.selectByBeanName(beanName).associateBy { it.name }

                // Phase 3: Sync env param defs for each method (always, even when empty, to clean stale records)
                for (method in meta.methods) {
                    syncToolEnvParams(beanName, method.toolName, method.envParamDescriptors, toolRecordsByName)
                }

                successCount++
                log.info(
                    "[BuiltinToolAutoRegistrar] Synced tool group: {} [{} methods]",
                    beanName,
                    meta.methods.size,
                )
            } catch (e: Exception) {
                failCount++
                log.error("[BuiltinToolAutoRegistrar] Failed to sync tool group: {}", beanName, e)
            }
        }

        log.info(
            "[BuiltinToolAutoRegistrar] Sync complete: {} succeeded, {} failed",
            successCount,
            failCount,
        )
    }

    /**
     * Sync @ToolEnvParamDef declarations to agent_tool_env_param table for a specific method record.
     * Strategy: in-place update — existing params are updated, new ones inserted, removed ones deleted.
     * This preserves record IDs and avoids breaking any external references.
     */
    private fun syncToolEnvParams(
        beanName: String,
        toolName: String,
        envParamDefs: List<ToolEnvParamDescriptor>,
        toolRecordsByName: Map<String, AgentTool>,
    ) {
        // Look up the tool record from pre-loaded map (no extra query)
        val targetRecord = toolRecordsByName[toolName]

        if (targetRecord == null) {
            log.warn("[BuiltinToolAutoRegistrar] Tool '{}::{}' not found after upsert, skipping env param sync", beanName, toolName)
            return
        }

        val now = LocalDateTime.now()

        // Load existing env params from DB, indexed by envParamName
        val existingParams = agentToolEnvParamMapper.selectByToolId(targetRecord.id)
        val existingByName = existingParams.associateBy { it.envParamName }.toMutableMap()

        // 1. Update existing or insert new
        for (envDef in envParamDefs) {
            val existing = existingByName.remove(envDef.key)
            if (existing != null) {
                // Update in place — preserve id and create_time
                val newRequired = if (envDef.required) 1 else 0
                val newSecret = if (envDef.secret) 1 else 0
                val newDescription = envDef.description.ifBlank { null }
                val newDefaultValue = envDef.defaultValue.ifBlank { null }

                val changed = existing.description != newDescription ||
                    existing.required != newRequired ||
                    existing.secret != newSecret ||
                    existing.defaultValue != newDefaultValue

                if (changed) {
                    existing.description = newDescription
                    existing.required = newRequired
                    existing.secret = newSecret
                    existing.defaultValue = newDefaultValue
                    existing.updateTime = now
                    agentToolEnvParamMapper.updateById(existing)
                }
            } else {
                // Insert new param
                agentToolEnvParamMapper.insert(
                    AgentToolEnvParam().apply {
                        toolId = targetRecord.id
                        envParamName = envDef.key
                        description = envDef.description.ifBlank { null }
                        required = if (envDef.required) 1 else 0
                        secret = if (envDef.secret) 1 else 0
                        defaultValue = envDef.defaultValue.ifBlank { null }
                        createTime = now
                        updateTime = now
                    },
                )
            }
        }

        // 2. Delete params that no longer exist in annotations
        val staleCount = existingByName.size
        for (stale in existingByName.values) {
            agentToolEnvParamMapper.deleteById(stale.id)
            log.info("[BuiltinToolAutoRegistrar] Removed stale env param '{}' from tool '{}::{}'", stale.envParamName, beanName, toolName)
        }

        log.info(
            "[BuiltinToolAutoRegistrar] Synced env params for tool '{}::{}': {} total, {} stale removed",
            beanName,
            toolName,
            envParamDefs.size,
            staleCount,
        )
    }

    companion object {
        private const val DEFAULT_TENANT_ID = 1L
        private const val TOOL_TYPE_BUILTIN = "BUILTIN"
        private const val SYSTEM_CREATOR = "SYSTEM"
        private const val DEFAULT_TIMEOUT = 30
    }
}
