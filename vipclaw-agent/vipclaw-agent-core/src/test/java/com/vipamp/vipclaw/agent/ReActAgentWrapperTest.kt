package com.vipamp.vipclaw.agent

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description:
 * @Project: vipclaw
 */
object ReActAgentWrapperTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val agent = AscopeAgentLauncher.createFactory().createSingleAgent(
            AgentSpec.builder()
                .id(1)
                .chatModelId(1)
                .build()
        )
        val streamTextAll = agent.streamTextAll("你好")
        streamTextAll.subscribe(
            { text -> println(text) },
            { error -> println(error) },
            { println("done") }
        )
        Thread.sleep(100000)
    }
}
