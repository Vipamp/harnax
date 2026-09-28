import Foundation

/// One row of `GET /api/admin/tools/page`, and the same shape `GET /api/admin/tools/{id}` answers.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt:7-55` declares
/// every field `val x: T? = null`, and the service copies the entity straight across
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:37-56`).
/// With `default-property-inclusion: non_null` that means any key may be absent rather than `null` — so all
/// sixteen are optional here, including the four 0/1 columns whose "unset" state has to stay readable as
/// unset (`hxFlag` on `nil` is `false`, and `status == nil` is not a stopped row).
///
/// Nothing in this contract is writable: tools are registered by the running code, and the controller
/// exposes no write path at all (`AgentToolController.kt:13-15`).
public struct ToolSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let displayName: String?
    public let displayNameZh: String?
    public let description: String?
    public let beanName: String?
    public let methodName: String?
    public let envParams: [EnvParamEntry]?
    public let readOnly: Int?
    public let needConfirm: Int?
    public let isRequired: Int?
    public let requiredEnvParamKeys: [String]?
    public let status: Int?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?

    /// Every row the code sync writes is enabled and undeleted; a stopped row only appears once an operator
    /// disables it in the database, which is why the filter offers the column at all
    /// (`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:76-90`).
    public var isEnabled: Bool { status != 0 }

    /// A human has to approve the call before it runs (`needConfirm == 1`).
    public var requiresConfirmation: Bool { needConfirm.hxFlag }

    /// Injected into every agent spec at delivery time, so it is never offered in the binding wizard
    /// (`AgentToolMapper.xml:59-74`).
    public var isMandatory: Bool { isRequired.hxFlag }

    /// `readOnly == 1` says "code-owned, no form will ever change it" — the reason this whole screen has no
    /// write affordance.
    public var isCodeOwned: Bool { readOnly.hxFlag }

    /// The declared parameters. Absent and empty are the same thing to the UI: nothing to list.
    public var entries: [EnvParamEntry] { envParams ?? [] }

    /// `requiredEnvParamKeys` ships the key names even when the entry table was never read, so it is both
    /// the count fallback of the web popover and the last word on what a parameter is called
    /// (`harnax-webui/src/components/EnvParamsPopover/index.tsx:24`).
    public var declaredRequiredKeys: [String] { requiredEnvParamKeys ?? [] }

    /// Count for the chip when the entries themselves are missing.
    public var entryCountFallback: Int { declaredRequiredKeys.count }

    /// `中文名 → 显示名 → 代码名`, and English skips the Chinese column
    /// (`harnax-webui/src/pages/tool/index.tsx:22-28`).
    public func title(chinese: Bool) -> String? {
        let candidates = chinese ? [displayNameZh, displayName, name] : [displayName, name]
        return candidates.lazy.compactMap(hxPresented).first
    }

    /// The technical identifier, shown under the display name whenever the two differ.
    public var codeName: String? { hxPresented(name) }
    public var details: String? { hxPresented(description) }
    public var bean: String? { hxPresented(beanName) }
    public var method: String? { hxPresented(methodName) }

    /// Where the call actually lands. A row with neither column has no implementation to name — the code
    /// sync stopped writing those two columns for a tool that was registered by hand.
    public var implementation: String? {
        switch (bean, method) {
        case let (.some(bean), .some(method)): return "\(bean)#\(method)"
        case let (.some(bean), _): return bean
        case let (_, .some(method)): return "#\(method)"
        default: return nil
        }
    }
}
