package com.agnetix.harnax.tools.sdk

/**
 * Descriptor for a single environment parameter declared via @ToolEnvParamDef.
 */
data class ToolEnvParamDescriptor(
    /** Environment parameter key name */
    val key: String,
    /** Human-readable description */
    val description: String,
    /** Whether this env parameter is required */
    val required: Boolean,
    /** Whether this env parameter is a secret */
    val secret: Boolean,
    /** Default value (non-secret only) */
    val defaultValue: String,
)

/**
 * Descriptor for a single tool method within a ToolBox.
 * Each @Tool method corresponds to one agent_tool database record.
 *
 * Extracted at runtime from @Tool (agentscope) + @ToolMeta + @NeedConfirmed annotations.
 */
data class ToolMethodDescriptor(
    /** Java method name */
    val methodName: String,
    /** Tool name from @Tool.name (falls back to methodName if blank) */
    val toolName: String,
    /** Display name from @ToolMeta.displayName */
    val displayName: String,
    /** Display name for zh-CN locale from @ToolMeta.displayNameZh */
    val displayNameZh: String,
    /** Description from @Tool.description */
    val description: String,
    /** Whether this method is read-only (from @Tool.readOnly) */
    val readOnly: Boolean,
    /** Whether this method requires user confirmation (from @ToolMeta.needConfirm or @NeedConfirmed) */
    val needConfirm: Boolean,
    /** Environment parameter definitions from @ToolMeta.envParamDefs */
    val envParamDescriptors: List<ToolEnvParamDescriptor>,
    /** Execution timeout in seconds from @ToolMeta.timeoutSeconds (0 = use system default) */
    val timeoutSeconds: Int,
    /** Whether this tool is publicly available from @ToolMeta.isPublic */
    val isPublic: Boolean,
    /** Whether this tool is mandatory (always included, hidden from UI selection) from @ToolMeta.isRequired */
    val isRequired: Boolean,
)

/**
 * Metadata descriptor for a ToolBox, containing all @Tool method descriptors.
 *
 * Used by BuiltinToolAutoRegistrar to sync tool metadata to the database.
 * Each @Tool method in the ToolBox becomes a separate agent_tool record.
 */
data class ToolMetaDescriptor(
    /** Spring bean name (from @Component value) */
    val beanName: String,
    /** Logical tool group name (from ToolBox.name()) */
    val toolName: String,
    /** Descriptors for all @Tool-annotated methods (each = one agent_tool record) */
    val methods: List<ToolMethodDescriptor>,
)
