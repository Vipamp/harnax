//package com.agnetix.harnax.agent
//
//import com.agnetix.harnax.agent.provider.tool.UserIdentifier
//
///**
// * @Author: heqingsong
// * @Date: 2026/4/12
// * @Description:
// * @Project: harnax
// */
//object ReActAgentWrapperTest {
//
//    @JvmStatic
//    fun main(args: Array<String>) {
//        val agent = AscopeAgentLauncher.createFactory().createSingleAgent(
//            AgentSpec.builder()
//                .id(1)
//                .chatModelId(1)
//                .build(),
//            userIdentifier = UserIdentifier(1)
//        )
//        val streamTextAll = agent.callStream("你好")
//        streamTextAll.subscribe(
//            { text -> println(text) },
//            { error -> println(error) },
//            { println("done") }
//        )
//        Thread.sleep(100000)
//    }
//}
