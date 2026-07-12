package com.agnetix.harnax.tools.sdk

import com.agnetix.harnax.tools.sdk.adaptor.ToolCallInfo
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import org.slf4j.LoggerFactory

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: ToolBox
 * @Project: harnax
 */
abstract class ToolBox {
    @Volatile
    private var userIdentifierValue: UserIdentifier? = null

    @Volatile
    private var sessionMetaContextValue: SessionMetaContext? = null

    @Volatile
    private var toolCallLogAdaptorValue: ToolCallLogAdaptor? = null
    private val needConfirmedTools: MutableSet<String> = mutableSetOf()
    private lateinit var name: String
    private val log = LoggerFactory.getLogger(ToolBox::class.java)

    /**
     * 获取工具名称（子类必须实现）
     */
    abstract fun name(): String

    fun init(
        toolCallLogAdaptor: ToolCallLogAdaptor,
        sessionMetaContext: SessionMetaContext,
        userIdentifier: UserIdentifier,
    ) {
        this.toolCallLogAdaptorValue = toolCallLogAdaptor
        this.userIdentifierValue = userIdentifier
        this.sessionMetaContextValue = sessionMetaContext
        this::class.java.methods.filter { it.getDeclaredAnnotation(NeedConfirmed::class.java) != null }
            .forEach { needConfirmedTools.add(it.name) }
        this.name = name()
    }

    fun userIdentifier(): UserIdentifier = userIdentifierValue
        ?: throw IllegalStateException("ToolBox not initialized: userIdentifier is null")
    fun needConfirmedTools(): Set<String> = needConfirmedTools.map { "$name::$it" }.toSet()

    @Suppress("UNCHECKED_CAST")
    protected fun <T> execute(
        vararg args: Pair<String, Any?>,
        action: () -> T,
    ): T {
        val methodName = Throwable().stackTrace[1].methodName
        return executeInternal(methodName, args.toMap(), action)
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> execute(action: () -> T): T {
        val methodName = Throwable().stackTrace[1].methodName
        return executeInternal(methodName, emptyMap(), action)
    }

    private fun <T> executeInternal(
        methodName: String,
        args: Map<String, Any?>,
        action: () -> T,
    ): T {
        val startTime = System.currentTimeMillis()

        return try {
            val result = action()
            val endTime = System.currentTimeMillis()
            logToolCall(methodName, args, result?.toString() ?: "", startTime, endTime)
            result
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            logToolCallError(methodName, args, e, startTime, endTime)
            throw e
        }
    }

    private fun logToolCall(
        toolName: String,
        args: Map<String, Any?>,
        result: String,
        startTime: Long,
        endTime: Long,
    ) {
        val duration = endTime - startTime
        val argsMap = args.mapValues { it.value?.toString() ?: "null" }
        val meta = sessionMetaContextValue
        val adaptor = toolCallLogAdaptorValue

        if (meta == null || adaptor == null) {
            log.warn("ToolBox not initialized (sessionMeta or adaptor is null). Skipping tool call log for {}::{}", name, toolName)
            return
        }

        val toolCallInfo = ToolCallInfo(
            agentId = meta.agentId,
            sessionId = meta.sessionId,
            toolName = "$name::$toolName",
            args = argsMap,
            result = result,
            success = true,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
        )

        try {
            adaptor.emit(toolCallInfo)
        } catch (e: Exception) {
            log.error("Failed to log tool call: toolName=$toolName", e)
        }
    }

    private fun logToolCallError(
        toolName: String,
        args: Map<String, Any?>,
        error: Throwable,
        startTime: Long,
        endTime: Long,
    ) {
        val duration = endTime - startTime
        val argsMap = args.mapValues { it.value?.toString() ?: "null" }
        val meta = sessionMetaContextValue
        val adaptor = toolCallLogAdaptorValue

        if (meta == null || adaptor == null) {
            log.warn("ToolBox not initialized (sessionMeta or adaptor is null). Skipping tool call error log for {}::{}", name, toolName)
            return
        }

        val toolCallInfo = ToolCallInfo(
            agentId = meta.agentId,
            sessionId = meta.sessionId,
            toolName = "$name::$toolName",
            args = argsMap,
            result = "ERROR: ${error.message}",
            success = false,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
        )

        try {
            adaptor.emit(toolCallInfo)
        } catch (logEx: Exception) {
            log.error("Failed to log tool call error: toolName=$toolName", logEx)
        }
    }
}

/**
 * 标记需要用户确认的工具方法
 */
annotation class NeedConfirmed
