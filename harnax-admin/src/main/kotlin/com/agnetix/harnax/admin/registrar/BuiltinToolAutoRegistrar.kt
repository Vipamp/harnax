package com.agnetix.harnax.admin.registrar

import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolEnvParam
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.tools.sdk.ToolEnvParamDescriptor
import com.agnetix.harnax.tools.sdk.ToolMethodDescriptor
import com.agnetix.harnax.tools.sdk.registry.ToolRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * The single lifecycle entry point for tool assets. Tools are platform-scoped and additive: this
 * registrar inserts a declaration the table does not hold and refreshes one it does, and it never
 * deletes. No page or API writes this table.
 *
 * Each @Tool method maps to one agent_tool record, enabling independent needConfirm, envParamDefs
 * and per-method granting at runtime.
 *
 * Convergence strategy (runs on every startup), keyed on the one thing a declaration owns — the
 * `@Tool.name`:
 * - Not in the database: inserted.
 * - In both: the code-owned columns are compared one by one and the row is updated only when
 *   something differs, with the difference logged. The id — and with it every agent binding — is
 *   kept.
 * - In the database, not in the code: left alone. A tool an agent is bound to must not vanish
 *   because a Java method moved or a ToolBox class was removed; [registeredToolNames] is what keeps
 *   such a row out of delivery.
 * - Two declarations sharing one name: refused before anything is written. The name is the identity,
 *   so an arbitrary winner would silently decide which method the tool runs.
 */
@Component
class BuiltinToolAutoRegistrar(
    private val toolRegistry: ToolRegistry,
    private val agentToolMapper: AgentToolMapper,
    private val agentToolEnvParamMapper: AgentToolEnvParamMapper,
) {
    private val log = LoggerFactory.getLogger(BuiltinToolAutoRegistrar::class.java)

    /**
     * The names the last sync found declared. Delivery reads this to hold back a row whose
     * declaration left the classpath: the row is kept (see the class comment) but its method may no
     * longer exist, so handing it to the runtime would fail at assembly.
     *
     * Empty means "the registry knows nothing" — a skipped or failed scan, not a catalogue of zero
     * tools — so a reader must treat it as no filter rather than as "nothing is deliverable".
     */
    @Volatile
    private var declaredNames: Set<String> = emptySet()

    fun registeredToolNames(): Set<String> = declaredNames

    @EventListener(ApplicationReadyEvent::class)
    fun syncBuiltinTools() {
        val allMeta = toolRegistry.getAllToolMeta()
        if (allMeta.isEmpty()) {
            log.info("[BuiltinToolAutoRegistrar] No @Tool annotated methods found, skipping sync")
            return
        }

        val declared = allMeta.flatMap { (beanName, meta) -> meta.methods.map { beanName to it } }
        requireUniqueNames(declared)

        log.info(
            "[BuiltinToolAutoRegistrar] Syncing {} builtin tool group(s), {} declared tool(s)",
            allMeta.size,
            declared.size,
        )

        var successCount = 0
        var failCount = 0

        for ((beanName, meta) in allMeta) {
            try {
                val recordsByName = meta.methods.associate { method -> method.toolName to saveTool(beanName, method) }

                // Env param defs are synced from the rows just resolved, so a tool that failed to save
                // does not leave its parameters pointing at a stale id.
                for (method in meta.methods) {
                    syncToolEnvParams(beanName, method.toolName, method.envParamDescriptors, recordsByName)
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

        // The declared set, not the written set: a group that failed to save is still declared, and
        // holding its tools back would turn a write failure into a missing tool in every agent.
        declaredNames = declared.map { it.second.toolName }.toSet()

        log.info(
            "[BuiltinToolAutoRegistrar] Sync complete: {} succeeded, {} failed; {} tool name(s) declared",
            successCount,
            failCount,
            declaredNames.size,
        )
    }

    /**
     * A name identifies a tool, so a classpath where two methods claim one name has no correct
     * resolution: whichever won would be an accident of bean order. Refused before any write, and
     * before any of the running rows is touched.
     */
    private fun requireUniqueNames(declared: List<Pair<String, ToolMethodDescriptor>>) {
        val collisions = declared.groupBy { it.second.toolName }.filterValues { it.size > 1 }
        if (collisions.isEmpty()) {
            return
        }
        val detail = collisions.entries.joinToString("; ") { (name, holders) ->
            "'$name' declared by " + holders.joinToString(", ") { (bean, method) -> "$bean::${method.methodName}" }
        }
        throw IllegalStateException(
            "[BuiltinToolAutoRegistrar] Duplicate @Tool name(s) on the classpath: $detail. " +
                "A tool name identifies one tool, so each @Tool method needs its own name.",
        )
    }

    /**
     * Resolve one declaration to a row: insert when the name is new, update in place when any
     * code-owned column differs.
     */
    private fun saveTool(beanName: String, method: ToolMethodDescriptor): AgentTool {
        val requiredKeysJson = method.envParamDescriptors.filter { it.required }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",", prefix = "[", postfix = "]") { "\"${it.key}\"" }

        if (method.isRequired && requiredKeysJson != null) {
            log.warn(
                "[BuiltinToolAutoRegistrar] Required tool '{}::{}' declares required env params {}, " +
                    "but required tools carry no agent binding so these values can never be resolved. " +
                    "Remove isRequired or the required env params.",
                beanName,
                method.toolName,
                method.envParamDescriptors.filter { it.required }.map { it.key },
            )
        }

        val declaredRow = declarativeRow(beanName, method, requiredKeysJson)
        val existing = agentToolMapper.selectByName(method.toolName) ?: run {
            agentToolMapper.insert(declaredRow)
            log.info(
                "[BuiltinToolAutoRegistrar] Registered new tool '{}' ({}::{})",
                method.toolName,
                beanName,
                method.methodName,
            )
            return declaredRow
        }

        val differences = differences(existing, declaredRow)
        if (differences.isEmpty()) {
            return existing
        }

        // Keep the identity columns the declaration does not own.
        declaredRow.id = existing.id
        declaredRow.createTime = existing.createTime
        declaredRow.creator = existing.creator
        agentToolMapper.updateById(declaredRow)
        log.info(
            "[BuiltinToolAutoRegistrar] Updated tool '{}' (id={}): {}",
            method.toolName,
            existing.id,
            differences.joinToString(", "),
        )
        return declaredRow
    }

    /**
     * The columns a declaration owns. `name` is deliberately absent from the comparison: it is how
     * the row was found, and a renamed declaration is a different tool with its own row.
     */
    private fun declarativeRow(
        beanName: String,
        method: ToolMethodDescriptor,
        requiredKeysJson: String?,
    ): AgentTool = AgentTool().apply {
        name = method.toolName
        displayName = method.displayName.ifBlank { method.toolName }
        displayNameZh = method.displayNameZh.ifBlank { null }
        description = method.description
        this.beanName = beanName
        methodName = method.methodName
        readOnly = if (method.readOnly) ENABLED else DISABLED
        needConfirm = if (method.needConfirm) ENABLED else DISABLED
        isRequired = if (method.isRequired) ENABLED else DISABLED
        requiredEnvParamKeys = requiredKeysJson
        status = ENABLED
        active = ENABLED
        creator = SYSTEM_CREATOR
    }

    /** Which of the code-owned columns differ, by column name, for the update log. */
    private fun differences(existing: AgentTool, declared: AgentTool): List<String> = buildList {
        if (existing.displayName != declared.displayName) add("display_name")
        if (existing.displayNameZh != declared.displayNameZh) add("display_name_zh")
        if (existing.description != declared.description) add("description")
        if (existing.beanName != declared.beanName) add("bean_name")
        if (existing.methodName != declared.methodName) add("method_name")
        if (existing.readOnly != declared.readOnly) add("read_only")
        if (existing.needConfirm != declared.needConfirm) add("need_confirm")
        if (existing.isRequired != declared.isRequired) add("is_required")
        if (existing.requiredEnvParamKeys != declared.requiredEnvParamKeys) add("required_env_param_keys")
        if (existing.status != declared.status) add("status")
        if (existing.active != declared.active) add("active")
    }

    /**
     * Sync @ToolEnvParamDef declarations to agent_tool_env_param table for a specific method record.
     * Strategy: in-place update — existing params are updated, new ones inserted, removed ones deleted.
     * This preserves record IDs and avoids breaking any external references.
     *
     * Deleting a parameter definition is part of updating the tool it belongs to, not of deleting a
     * tool: the definition has no reader of its own once the annotation stops declaring it.
     */
    private fun syncToolEnvParams(
        beanName: String,
        toolName: String,
        envParamDefs: List<ToolEnvParamDescriptor>,
        toolRecordsByName: Map<String, AgentTool>,
    ) {
        val targetRecord = toolRecordsByName[toolName]

        if (targetRecord == null) {
            log.warn("[BuiltinToolAutoRegistrar] Tool '{}::{}' was not saved, skipping env param sync", beanName, toolName)
            return
        }

        val now = LocalDateTime.now()

        val existingParams = agentToolEnvParamMapper.selectByToolId(targetRecord.id)
        val existingByName = existingParams.associateBy { it.envParamName }.toMutableMap()

        for (envDef in envParamDefs) {
            val existing = existingByName.remove(envDef.key)
            if (existing != null) {
                val newRequired = if (envDef.required) ENABLED else DISABLED
                val newSecret = if (envDef.secret) ENABLED else DISABLED
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
                agentToolEnvParamMapper.insert(
                    AgentToolEnvParam().apply {
                        toolId = targetRecord.id
                        envParamName = envDef.key
                        description = envDef.description.ifBlank { null }
                        required = if (envDef.required) ENABLED else DISABLED
                        secret = if (envDef.secret) ENABLED else DISABLED
                        defaultValue = envDef.defaultValue.ifBlank { null }
                        createTime = now
                        updateTime = now
                    },
                )
            }
        }

        val staleCount = existingByName.size
        for (stale in existingByName.values) {
            agentToolEnvParamMapper.deleteById(stale.id)
            log.info(
                "[BuiltinToolAutoRegistrar] Removed stale env param '{}' from tool '{}::{}'",
                stale.envParamName,
                beanName,
                toolName,
            )
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
        private const val SYSTEM_CREATOR = "SYSTEM"
        private const val ENABLED = 1
        private const val DISABLED = 0
    }
}
