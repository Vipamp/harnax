package com.agnetix.harnax.channel.service.bootstrap

import com.agnetix.harnax.channel.feishu.FeishuAdaptor
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.service.adaptor.RouterAgentAdaptor
import com.agnetix.harnax.channel.service.client.RouterClient
import com.agnetix.harnax.channel.service.mapper.ChannelEntityConverter
import com.agnetix.harnax.channel.wechat.WechatAdaptor
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.mapper.ChannelMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Channel 启动引导组件。
 *
 * 监听 [ApplicationReadyEvent]，从数据库 channel 表加载所有 enabled=1 AND status=1
 * AND active=1 的渠道，按 [Channel.communicationMode] 选择性建立监听：
 *
 *  - websocket    -> 飞书 WebSocket 长连接（[FeishuAdaptor.startChannelWithAgent]）
 *  - long_polling -> 微信 iLink 长轮询（[WechatAdaptor.startChannelWithAgent]）
 *  - webhook      -> 不主动连接（由 HTTP 回调 Controller 处理）
 *
 * 每个监听到的客户端消息都会通过 [RouterAgentAdaptor] → session-router → agent-service
 * 完成 Agent 推理，再回送到对应渠道。
 */
@Component
class ChannelBootstrapRunner(
    private val channelMapper: ChannelMapper,
    private val wechatAdaptor: WechatAdaptor,
    private val feishuAdaptor: FeishuAdaptor,
    private val routerClient: RouterClient,
    private val sessionManager: ChannelSessionManager,
) {

    private val log = LoggerFactory.getLogger(ChannelBootstrapRunner::class.java)

    private val routerAgentAdaptor by lazy { RouterAgentAdaptor(routerClient) }

    @EventListener(ApplicationReadyEvent::class)
    fun startAutoListenChannels() {
        val channels: List<Channel> = runCatching { channelMapper.selectAutoStartChannels() }
            .getOrElse {
                log.error("加载 channel 配置失败，跳过自动监听: {}", it.message, it)
                return
            }

        log.info("加载到 {} 条需要自动监听的 channel 配置", channels.size)

        var started = 0
        var skipped = 0
        channels.forEach { entity ->
            try {
                if (startSingle(entity)) started++ else skipped++
            } catch (e: Exception) {
                log.error("启动 channel 失败 id={}, name={}, type={}: {}", entity.id, entity.name, entity.type, e.message, e)
            }
        }
        log.info("Channel 自动监听完成: started={}, skipped={}, total={}", started, skipped, channels.size)
    }

    /**
     * @return true 表示已启动监听，false 表示无需主动连接（如 webhook 模式）。
     */
    private fun startSingle(entity: Channel): Boolean {
        val spec = ChannelEntityConverter.toSpec(entity)
        return when (entity.communicationMode.lowercase()) {
            "websocket" -> {
                log.info("启动 channel(WebSocket) id={}, name={}, type={}", entity.id, entity.name, entity.type)
                feishuAdaptor.startChannelWithAgent(spec, routerAgentAdaptor, sessionManager)
                true
            }
            "long_polling" -> {
                log.info("启动 channel(LongPolling) id={}, name={}, type={}", entity.id, entity.name, entity.type)
                wechatAdaptor.startChannelWithAgent(spec, routerAgentAdaptor, sessionManager)
                true
            }
            "webhook" -> {
                log.info("channel id={} 使用 webhook 回调模式，跳过主动连接", entity.id)
                false
            }
            else -> {
                log.warn(
                    "channel id={} 通信模式不受支持: {}, 跳过",
                    entity.id,
                    entity.communicationMode,
                )
                false
            }
        }
    }
}
