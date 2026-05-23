package com.agnetix.harnax.channel.sdk.adaptor

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.message.AgentMessage
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.RichMessage

/**
 * Agent 消息处理器抽象类
 *
 * 作为 SDK 的核心抽象，定义了消息从渠道接收到处理完成的生命周期：
 * 1. 渠道接收消息 → ChannelAdaptor 解析为 ChannelMessage
 * 2. AgentAdaptor.process() 处理 ChannelMessage，产生 AgentResponse
 * 3. ChannelAdaptor 将 AgentResponse 通过原渠道发送回去
 *
 * 设计理念：
 * - 平台无关：AgentAdaptor 不关心消息来自哪个渠道（微信/飞书/钉钉/HTTP）
 * - 可扩展：通过 AgentContext 提供完整的上下文信息
 * - 可观测：提供 beforeProcess/afterProcess/onError 钩子
 * - 容错：提供默认的 onError 实现，子类可覆盖
 *
 * 使用方式：
 * ```
 * class MyAgentAdaptor : AgentAdaptor() {
 *     override fun getName() = "my-agent"
 *
 *     override suspend fun process(context: AgentContext): AgentResponse {
 *         val reply = callLLM(context.message.content, context.history)
 *         return AgentResponse(content = reply)
 *     }
 * }
 * ```
 */
abstract class AgentAdaptor {

    /**
     * 获取 Agent 名称/标识
     * 用于日志记录和监控
     */
    abstract fun getName(): String

    /**
     * 处理消息 - 核心处理逻辑
     *
     * 接收来自渠道的消息，经过 Agent 处理后返回响应。
     * 这是整个消息处理流程的核心方法，子类必须实现。
     *
     * @param context 消息处理上下文，包含原始消息、历史记录、渠道配置等
     * @return Agent 处理结果
     */
    abstract suspend fun process(context: AgentContext): AgentResponse

    /**
     * 判断是否支持处理该消息
     *
     * 可根据消息类型、渠道类型等条件过滤。
     * 返回 false 时，该消息将被跳过，不会调用 process()。
     *
     * @param message 渠道消息
     * @return 是否支持处理
     */
    open fun supports(message: ChannelMessage): Boolean = true

    /**
     * 处理前的钩子
     *
     * 在 process() 之前调用，可用于：
     * - 预处理消息内容
     * - 记录请求日志
     * - 限流检查
     * - 上下文增强
     *
     * @param context 消息处理上下文
     */
    open suspend fun onBeforeProcess(context: AgentContext) {}

    /**
     * 处理后的钩子
     *
     * 在 process() 成功完成后调用，可用于：
     * - 记录响应日志
     * - 统计指标
     * - 消息持久化
     *
     * @param context 消息处理上下文
     * @param response Agent 处理结果
     */
    open suspend fun onAfterProcess(context: AgentContext, response: AgentResponse) {}

    /**
     * 处理异常的钩子
     *
     * 在 process() 抛出异常时调用，可用于：
     * - 降级响应
     * - 异常上报
     * - 重试逻辑
     *
     * 默认实现返回一条错误提示消息。
     *
     * @param context 消息处理上下文
     * @param error 异常对象
     * @return 降级的 Agent 响应
     */
    open suspend fun onError(context: AgentContext, error: Throwable): AgentResponse {
        return AgentResponse(
            content = "抱歉，处理您的消息时遇到了问题，请稍后重试。",
            shouldReply = true,
            metadata = mapOf("error" to (error.message ?: "unknown error")),
        )
    }
}

/**
 * Agent 消息处理上下文
 *
 * 封装了 AgentAdaptor.process() 所需的全部信息，
 * 包括原始消息、会话历史、渠道配置和自定义元数据。
 */
data class AgentContext(
    /**
     * 原始渠道消息
     */
    val message: ChannelMessage,

    /**
     * 会话历史消息列表
     */
    val history: List<AgentMessage> = emptyList(),

    /**
     * 渠道配置信息
     */
    val channelSpec: ChannelSpec,

    /**
     * 自定义元数据
     * 可用于传递请求级别的附加信息（如 traceId、userId 等）
     */
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Agent 处理结果
 *
 * AgentAdaptor.process() 的返回类型，
 * 包含处理后的回复内容以及可选的富消息和元数据。
 */
data class AgentResponse(
    /**
     * 回复文本内容
     */
    val content: String,

    /**
     * 富消息对象（可选）
     * 如果设置了富消息，渠道适配器应优先使用富消息发送
     */
    val richMessage: RichMessage? = null,

    /**
     * 是否需要回复
     * 某些场景下 Agent 可能不需要回复（如仅记录日志的旁路处理）
     */
    val shouldReply: Boolean = true,

    /**
     * 自定义元数据
     * 可用于传递处理结果的附加信息（如 token 使用量、模型名称等）
     */
    val metadata: Map<String, Any> = emptyMap(),
)
