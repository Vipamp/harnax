package com.agnetix.harnax.tools.sdk.registry

import com.agnetix.harnax.tools.sdk.NeedConfirmed
import com.agnetix.harnax.tools.sdk.ToolBox
import com.agnetix.harnax.tools.sdk.ToolEnvParamDescriptor
import com.agnetix.harnax.tools.sdk.ToolMeta
import com.agnetix.harnax.tools.sdk.ToolMetaDescriptor
import com.agnetix.harnax.tools.sdk.ToolMethodDescriptor
import io.agentscope.core.tool.Tool
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component

@Component
class ToolRegistry {

    private val log = LoggerFactory.getLogger(ToolRegistry::class.java)

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val registry: MutableMap<String, ToolBox> = mutableMapOf()
    private val metaRegistry: MutableMap<String, ToolMetaDescriptor> = mutableMapOf()

    @PostConstruct
    fun init() {
        val beans = applicationContext.getBeansOfType(ToolBox::class.java)
        for ((beanName, toolBox) in beans) {
            registry[beanName] = toolBox
            log.info("[ToolRegistry] Registered ToolBox bean: {}", beanName)

            // Extract metadata from @Tool + @ToolMeta annotations on methods
            val descriptor = extractToolMeta(beanName, toolBox)
            if (descriptor != null) {
                metaRegistry[beanName] = descriptor
                log.info(
                    "[ToolRegistry] Extracted tool meta for '{}': {} methods",
                    beanName,
                    descriptor.methods.size,
                )
            }
        }
        log.info(
            "[ToolRegistry] Total {} ToolBox beans registered, {} with @Tool methods",
            registry.size,
            metaRegistry.size,
        )
    }

    fun getToolBox(beanName: String): ToolBox? = registry[beanName]

    fun getAllToolBoxes(): List<ToolBox> = registry.values.toList()

    fun getToolBoxNames(): List<String> = registry.keys.toList()

    fun contains(beanName: String): Boolean = registry.containsKey(beanName)

    /** Get the metadata descriptor for a specific ToolBox bean */
    fun getToolMeta(beanName: String): ToolMetaDescriptor? = metaRegistry[beanName]

    /** Get all metadata descriptors for registered ToolBoxes with @Tool methods */
    fun getAllToolMeta(): Map<String, ToolMetaDescriptor> = metaRegistry.toMap()

    /**
     * Extract metadata from a ToolBox instance by reading @Tool (agentscope) + @ToolMeta
     * annotations on each method. A ToolBox must have at least one @Tool method to be registered.
     */
    private fun extractToolMeta(beanName: String, toolBox: ToolBox): ToolMetaDescriptor? {
        val clazz = toolBox::class.java
        val methods = mutableListOf<ToolMethodDescriptor>()

        for (method in clazz.methods) {
            val toolAnnotation = method.getAnnotation(Tool::class.java) ?: continue
            val toolMeta = method.getAnnotation(ToolMeta::class.java)
            val hasNeedConfirm = method.getAnnotation(NeedConfirmed::class.java) != null

            val envParamDescriptors = toolMeta?.envParamDefs?.map { envDef ->
                ToolEnvParamDescriptor(
                    key = envDef.key,
                    description = envDef.description,
                    required = envDef.required,
                    secret = envDef.secret,
                    defaultValue = envDef.defaultValue,
                )
            } ?: emptyList()

            // needConfirm: @ToolMeta.needConfirm OR @NeedConfirmed
            val needConfirm = toolMeta?.needConfirm == true || hasNeedConfirm

            methods.add(
                ToolMethodDescriptor(
                    methodName = method.name,
                    toolName = toolAnnotation.name.ifBlank { method.name },
                    displayName = toolMeta?.displayName ?: "",
                    displayNameZh = toolMeta?.displayNameZh ?: "",
                    description = toolAnnotation.description,
                    readOnly = toolAnnotation.readOnly,
                    needConfirm = needConfirm,
                    envParamDescriptors = envParamDescriptors,
                    timeoutSeconds = toolMeta?.timeoutSeconds ?: 0,
                    isPublic = toolMeta?.isPublic ?: true,
                    isRequired = toolMeta?.isRequired ?: false,
                ),
            )
        }

        if (methods.isEmpty()) {
            log.info("[ToolRegistry] ToolBox '{}' has no @Tool methods, skipping", beanName)
            return null
        }

        return ToolMetaDescriptor(
            beanName = beanName,
            toolName = toolBox.name(),
            methods = methods,
        )
    }
}
