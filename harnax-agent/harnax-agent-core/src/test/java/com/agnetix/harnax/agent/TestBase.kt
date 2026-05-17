//package com.agnetix.harnax.agent
//
//import com.agnetix.harnax.agent.adaptor.*
//import com.agnetix.harnax.agent.adaptor.model.DashScopeChatModelConfig
//import com.agnetix.harnax.agent.session.JsonSessionConfig
//import com.agnetix.harnax.agent.session.SessionLoader
//import kotlin.io.path.Path
//
///**
// * @Author: heqingsong
// * @Date: 2026/4/12
// * @Description: TestBase
// * @Project: harnax
// */
//fun AscopeAgentLauncher.Companion.createFactory(
//): AscopeAgentLauncher {
//    return AscopeAgentLauncher(
//        ChatModelConfigAdaptor,
//        McpConfigAdaptor { null },
//        SessionLoader.load(JsonSessionConfig("~/tmp")),
//        SkillAdaptor { null },
//        TokenStatAdaptor {},
//        ProcessLogAdaptor { null },
//        Path("/Users/heqingsong/software/agent_workspace")
//    )
//}
//
//val ChatModelConfigAdaptor = ChatModelConfigAdaptor {
//    DashScopeChatModelConfig(
//        modelName = "MiniMax-M2.1",
//        apiKey = "sk-5404e4ddac8645a1bd3555c00376a1f5"
//    )
//}
