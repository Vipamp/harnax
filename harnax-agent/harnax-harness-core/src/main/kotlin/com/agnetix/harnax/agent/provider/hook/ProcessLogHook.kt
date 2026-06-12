package com.agnetix.harnax.agent.provider.hook

import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogBuilder
import io.agentscope.core.hook.*
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import reactor.core.publisher.Mono

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: ProcessLogHook
 * @Project: harnax
 */
class ProcessLogHook : Hook {
    private lateinit var adaptor: ProcessLogAdaptor
    private lateinit var builder: ProcessLogBuilder

    fun initial(
        adaptor: ProcessLogAdaptor,
        agentId: Long,
        agentName: String,
        sessionId: String,
    ) {
        this.adaptor = adaptor
        this.builder = ProcessLogBuilder(agentId, agentName, sessionId)
    }

    override fun <T : HookEvent> onEvent(event: T): Mono<T> {
        if (event is PreCallEvent) {
            adaptor.emitLog(builder.info("[Processing] Agent'${event.agent.name}'calling."))
        } else if (event is PreActingEvent) {
            adaptor.emitLog(builder.info("[Processing] Call tool: '${event.toolUse.name}'with input '${event.toolUse.input}."))
        } else if (event is ActingChunkEvent) {
            val chunk = event.chunk
            val output: String = if (chunk.output.isEmpty()) "" else chunk.output[0].toString()
            adaptor.emitLog(builder.info("[Processing] Tool progress:'$output'."))
        } else if (event is PostActingEvent) {
            val toolResult = event.toolResult
            val output: String = extractOutput(toolResult)
            if (output.contains(other = "fake")) {
                val toolResult = event.toolResult
                val output: String = extractOutput(toolResult)
                if (output.contains(other = "fake")) {
                    adaptor.emitLog(builder.info("[Processing] Faktool result detected:'$output."))
                } else {
                    adaptor.emitLog(builder.info("[Processing] Call tool: ${event.toolUse.name} result is '$output"))
                }
            }
        } else if (event is PostCallEvent) {
            adaptor.emitLog(builder.info("[Processig] Agent execution completed"))
        } else if (event is ErrorEvent) {
            adaptor.emitLog(builder.error(message = "[Processing] Error.", throwable = event.error))
        }
        return Mono.just<T>(event)
    }

    private fun extractOutput(toolResult: ToolResultBlock): String {
        val outputs = toolResult.output
        if (outputs.isEmpty()) return ""
        val first = outputs[0]
        if (first is TextBlock) {
            return first.text
        }

        val sb = StringBuilder()
        for (block in outputs) {
            if (block is TextBlock) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(block.text)
            }
        }

        return if (sb.isNotEmpty()) sb.toString() else outputs.toString()
    }

    override fun priority(): Int = 500
}
