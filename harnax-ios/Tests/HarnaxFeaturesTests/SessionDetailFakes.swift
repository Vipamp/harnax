import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// Rows for the detail sheet's tests.
///
/// This screen reads no catalogue, so it needs no stand-in server: the only fixture it takes is a
/// `SessionSummary`, and every column of that is optional with a default. The builder therefore opens with
/// real-looking values — a row that arrives with all of them is the case the panels were designed for — and
/// each test overrides the one column its rule is about.
extension SessionSummary {
    static func stub(
        id: Int64? = 4471,
        title: String? = "季度估值复核",
        sessionDescription: String? = "按 9 月末报表复核三条产品线",
        sessionId: String? = "web-2f6c-8842",
        agentId: Int64? = 92,
        teamId: Int64? = nil,
        name: String? = "估值助手",
        description: String? = "读公告、算估值、出表格",
        systemPrompt: String? = "你是估值助手。\n只引用给出的报表。\n不猜测未披露的数字。\n输出保持三段。\n最后给出结论。",
        modelName: String? = "gpt-4o",
        modelPrice: Double? = 2.5,
        // The eight columns the 对话配置 panel reads. A capable model with all three switches off is the
        // ordinary row, and each config case below overrides the one column its rule is about.
        modelSupportReasoning: Int? = 1,
        modelThinkingMode: Int? = 1,
        modelSupportInternet: Int? = 1,
        modelSupportVision: Int? = 1,
        enableThink: Int? = 0,
        enableSearch: Int? = 0,
        enablePlan: Int? = 0,
        permissionMode: String? = "DEFAULT",
        mcpList: [SessionMcpItem] = [],
        skillList: [SessionSkillItem] = [],
        owner: String? = "harnax-demo",
        status: Int? = 1,
        isPublic: Int? = nil,
        creator: String? = "simon",
        createTime: String? = "2026-09-12 14:20:00",
        updateTime: String? = "2026-09-14 09:05:00"
    ) -> SessionSummary {
        SessionSummary(
            id: id,
            title: title,
            sessionDescription: sessionDescription,
            sessionId: sessionId,
            agentId: agentId,
            teamId: teamId,
            name: name,
            description: description,
            systemPrompt: systemPrompt,
            modelId: nil,
            modelName: modelName,
            modelPrice: modelPrice,
            modelSupportReasoning: modelSupportReasoning,
            modelThinkingMode: modelThinkingMode,
            modelSupportInternet: modelSupportInternet,
            modelSupportVision: modelSupportVision,
            enableThink: enableThink,
            enableSearch: enableSearch,
            enablePlan: enablePlan,
            permissionMode: permissionMode,
            mcpList: mcpList,
            skillList: skillList,
            owner: owner,
            status: status,
            isPublic: isPublic,
            creator: creator,
            createTime: createTime,
            updateTime: updateTime
        )
    }
}

extension SessionSkillItem {
    static func stub(
        repositoryId: Int64? = 7,
        repositoryName: String? = "qoder-skills",
        skillId: Int64? = 21,
        skillName: String? = "公告解析",
        skillDescription: String? = "从公告里抽估值口径"
    ) -> SessionSkillItem {
        SessionSkillItem(
            repositoryId: repositoryId,
            repositoryName: repositoryName,
            skillId: skillId,
            skillName: skillName,
            skillDescription: skillDescription
        )
    }
}

extension SessionMcpItem {
    static func stub(
        mcpId: Int64? = 12,
        mcpName: String? = "行情源",
        mcpDescription: String? = "提供收盘行情"
    ) -> SessionMcpItem {
        SessionMcpItem(mcpId: mcpId, mcpName: mcpName, mcpDescription: mcpDescription)
    }
}
