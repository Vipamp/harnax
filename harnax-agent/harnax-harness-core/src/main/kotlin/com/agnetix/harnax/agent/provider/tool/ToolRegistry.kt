package com.agnetix.harnax.agent.provider.tool

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import jakarta.annotation.PostConstruct

class ToolRegistry {

    private val log = LoggerFactory.getLogger(ToolRegistry::class.java)

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val registry: MutableMap<String, ToolBox> = mutableMapOf()

    @PostConstruct
    fun init() {
        val beans = applicationContext.getBeansOfType(ToolBox::class.java)
        for ((beanName, toolBox) in beans) {
            registry[beanName] = toolBox
            log.info("[ToolRegistry] Registered ToolBox bean: {}", beanName)
        }
        log.info("[ToolRegistry] Total {} ToolBox beans registered", registry.size)
    }

    fun getToolBox(beanName: String): ToolBox? = registry[beanName]

    fun getAllToolBoxes(): List<ToolBox> = registry.values.toList()

    fun getToolBoxNames(): List<String> = registry.keys.toList()

    fun contains(beanName: String): Boolean = registry.containsKey(beanName)
}
