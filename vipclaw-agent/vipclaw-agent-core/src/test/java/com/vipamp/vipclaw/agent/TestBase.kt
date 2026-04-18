//package com.vipamp.vipclaw.agent
//
//import com.vipamp.vipclaw.agent.adaptor.*
//import com.vipamp.vipclaw.agent.adaptor.model.DashScopeChatModelConfig
//import com.vipamp.vipclaw.agent.session.JsonSessionConfig
//import com.vipamp.vipclaw.agent.session.SessionLoader
//import kotlin.io.path.Path
//
///**
// * @Author: heqingsong
// * @Date: 2026/4/12
// * @Description: TestBase
// * @Project: vipclaw
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
