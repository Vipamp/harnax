package com.vipamp.vipclaw.agent.provider.tool

import com.vipamp.vipclaw.agent.adaptor.ToolCallInfo
import com.vipamp.vipclaw.agent.adaptor.ToolCallLogAdaptor
import com.vipamp.vipclaw.common.log.logger

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: ToolBox
 * @Project: vipclaw
 */
abstract class ToolBox {
    protected lateinit var userIdentifier: UserIdentifier
    private lateinit var sessionMetaContext: SessionMetaContext
    private lateinit var toolCallLogAdaptor: ToolCallLogAdaptor
    private val needConfirmedTools: MutableSet<String> = mutableSetOf()
    private lateinit var name: String

    /**
     * 获取工具名称（子类必须实现）
     */
    abstract fun name(): String

    /**
     * 获取危险工具列表（需要用户确认的工具）
     */
    open fun dangerousTools(): Set<String> = emptySet()

    fun init(
        toolCallLogAdaptor: ToolCallLogAdaptor,
        sessionMetaContext: SessionMetaContext,
        userIdentifier: UserIdentifier
    ) {
        this.toolCallLogAdaptor = toolCallLogAdaptor
        this.userIdentifier = userIdentifier
        this.sessionMetaContext = sessionMetaContext
        this::class.java.methods.filter { it.getDeclaredAnnotation(NeedConfirmed::class.java) != null }
            .forEach { needConfirmedTools.add(it.name) }
        this.name = name()
    }

    fun userIdentifier(): UserIdentifier = userIdentifier
    fun needConfirmedTools(): Set<String> = needConfirmedTools

    @Suppress("UNCHECKED_CAST")
    protected fun <T> execute(
        vararg args: Pair<String, Any?>,
        action: () -> T
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
        action: () -> T
    ): T {
        val startTime = System.currentTimeMillis()

        return try {
            // 执行实际的工具方法
            val result = action()
            val endTime = System.currentTimeMillis()

            // 记录成功日志
            logToolCall(methodName, args, result?.toString() ?: "", startTime, endTime)

            result
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            logToolCallError(methodName, args, e, startTime, endTime)
            throw e
        }
    }

    /**
     * 记录工具调用成功日志
     */
    private fun logToolCall(
        toolName: String,
        args: Map<String, Any?>,
        result: String,
        startTime: Long,
        endTime: Long
    ) {
        val duration = endTime - startTime
        val argsMap = args.mapValues { it.value?.toString() ?: "null" }

        val toolCallInfo = ToolCallInfo(
            agentId = sessionMetaContext.agentId,
            sessionId = sessionMetaContext.sessionId,
            toolName = "$name::$toolName",
            args = argsMap,
            result = result,
            success = true,
            startTime = startTime,
            endTime = endTime,
            duration = duration
        )

        try {
            toolCallLogAdaptor.emit(toolCallInfo)
        } catch (e: Exception) {
            logger().error("Failed to log tool call: toolName=$toolName", e)
        }
    }

    /**
     * 记录工具调用异常日志
     */
    private fun logToolCallError(
        toolName: String,
        args: Map<String, Any?>,
        error: Throwable,
        startTime: Long,
        endTime: Long
    ) {
        val duration = endTime - startTime
        val argsMap = args.mapValues { it.value?.toString() ?: "null" }

        val toolCallInfo = ToolCallInfo(
            agentId = sessionMetaContext.agentId,
            sessionId = sessionMetaContext.sessionId,
            toolName = "$name::$toolName",
            args = argsMap,
            result = "ERROR: ${error.message}",
            success = false,
            startTime = startTime,
            endTime = endTime,
            duration = duration
        )

        try {
            toolCallLogAdaptor.emit(toolCallInfo)
        } catch (logEx: Exception) {
            logger().error("Failed to log tool call error: toolName=$toolName", logEx)
        }
    }
}

/**
 * 标记需要用户确认的工具方法
 */
annotation class NeedConfirmed