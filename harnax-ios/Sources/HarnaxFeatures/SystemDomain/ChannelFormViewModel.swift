import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// E2 — the channel form, create and edit in one type.
///
/// The three things that make this form bigger than the API Key and variable forms:
///
/// - the credential fields are a matrix, not a list. Which fields show, and which of them are required,
///   depends on the *pair* of type and mode (`ChannelType.fields(mode:)`), because Feishu's callback pair
///   only means something in webhook mode and WeChat has no fields at all.
/// - `configJson` is merged, not rewritten. The scan path puts `botToken`, `userId`, `botId` and `baseUrl` in
///   the same blob, so a save that only knows about the five managed keys has to leave the rest exactly as it
///   arrived (`UpdateForm.tsx:110-121`, `WechatLoginService.kt:162-186`).
/// - a credential field that was never touched is sent back as the mask the server handed out, and
///   `keepStoredSecrets` recognises its own display form and keeps the real value
///   (`ChannelServiceImpl.kt:304-309`). There is no client-side "unchanged" flag because the wire carries it.
@MainActor
public final class ChannelFormViewModel: ObservableObject {
    /// One agent option of the picker, as `GET /api/admin/agents/page` gives it.
    public struct AgentOption: Identifiable, Equatable, Sendable {
        public let id: Int64
        public let name: String
    }

    /// The thinking switch on create has a third position the other two do not: omitting `enableThink` is how
    /// the form says "whatever the bound model wants" (`ChannelServiceImpl.kt:83`). On edit the stored column
    /// already holds a decision, so only the two real positions exist.
    public enum ThinkingChoice: Hashable, Sendable {
        case followModel
        case on
        case off

        var queryValue: Int? {
            switch self {
            case .followModel: nil
            case .on: 1
            case .off: 0
            }
        }
    }

    @Published public var name = ""
    @Published public var typeChoice: ChannelType?
    /// The mode actually in force. Hidden from the UI on a type with one mode, but always sent, because the
    /// server's `resolveCommunicationMode` is the thing that keeps a stale pair from claiming it runs.
    @Published public var modeChoice: ChannelMode?
    @Published public var agentId: Int64?
    @Published public var autoStart = true
    @Published public var description = ""
    @Published public var searchEnabled = false
    @Published public var planEnabled = false
    @Published public var thinkingChoice: ThinkingChoice = .followModel
    /// The managed credential fields, keyed by `configJson` name. Missing key means "not filled in".
    @Published public var fieldValues: [String: String] = [:]

    @Published public private(set) var isSaving = false
    @Published public private(set) var saved = false
    @Published public private(set) var errorText: String?
    @Published public private(set) var agents: [AgentOption] = []
    @Published public private(set) var isLoadingAgents = false
    /// The picker's own failure line, kept apart from `errorText`: a form that cannot list agents is not a
    /// form that failed to save, and the retry belongs on the field rather than above the sheet.
    @Published public private(set) var agentErrorText: String?

    /// The row as it arrived, for the read-only blocks and the merge base. `nil` on create.
    public let row: ChannelSummary?
    private let storedConfig: ChannelConfig
    private let catalog: any ChannelCataloging
    private let agentCatalog: any AgentCataloging

    public init(row: ChannelSummary?, catalog: any ChannelCataloging, agents: any AgentCataloging) {
        self.row = row
        self.catalog = catalog
        self.agentCatalog = agents
        storedConfig = row?.config ?? ChannelConfig()
        if let row {
            name = row.name ?? ""
            typeChoice = row.channelType
            // A channel created before the modes were narrowed can still hold one its type cannot run;
            // show the runnable one instead of the dead stored one (`channelModes.ts:40-46`).
            modeChoice = row.channelType.map { resolvedMode(type: $0, stored: row.mode) }
            agentId = row.agentId
            autoStart = row.autoStarts
            description = row.description ?? ""
            searchEnabled = row.searchEnabled
            planEnabled = row.planEnabled
            thinkingChoice = row.thinkEnabled ? .on : .off
            fieldValues = ChannelConfigKey.allCases.reduce(into: [:]) { partial, key in
                if let text = storedConfig.text(for: key) { partial[key.rawValue] = text }
            }
        }
    }

    /// The fields this type/mode pair owns, in matrix order.
    public var visibleFields: [ChannelField] {
        guard let typeChoice else { return [] }
        return typeChoice.fields(mode: modeChoice)
    }

    public var showsModePicker: Bool {
        guard let typeChoice else { return false }
        return !typeChoice.hidesModePicker && typeChoice.modes.count > 1
    }

    /// WeChat binds by scan, so the form shows the instruction instead of fields
    /// (`CreateForm.tsx:127-138`).
    public var showsScanHint: Bool { typeChoice?.showsScanHint ?? false }

    public var agentTitle: String? {
        guard let agentId else { return nil }
        return agents.first(where: { $0.id == agentId })?.name
    }

    /// The two read-only blocks the console attaches to an existing channel: the session id, which never
    /// changes, and the callback URL, which the server only derives for a webhook row.
    public var immutableSessionId: String? { hxPresented(row?.sessionId) }
    public var callbackUrl: String? { hxPresented(row?.callbackUrl) }

    public var canSubmit: Bool {
        guard !isSaving else { return false }
        guard let typeChoice else { return false }
        guard hxPresented(name) != nil else { return false }
        guard name.trimmingCharacters(in: .whitespacesAndNewlines).count <= 100 else { return false }
        guard agentId != nil else { return false }
        if modeChoice == nil, typeChoice.modes.isEmpty { return false }
        return visibleFields.allSatisfy { field in
            !isRequired(field) || hxPresented(fieldValues[field.key.rawValue]) != nil
        }
    }

    /// Required on create per the matrix; on edit only Feishu's callback pair, because every other field has
    /// a stored value behind it (`UpdateForm.tsx:46-70`).
    public func isRequired(_ field: ChannelField) -> Bool {
        guard let typeChoice else { return false }
        if row != nil {
            return typeChoice == .feishu && modeChoice == .webhook
        }
        return typeChoice.isRequiredOnCreate(field, mode: modeChoice)
    }

    public func loadAgents() async {
        guard agents.isEmpty, !isLoadingAgents else { return }
        isLoadingAgents = true
        agentErrorText = nil
        // 100 is what the console asks for (`channel.ts:100-107`); the server would take up to 1000.
        switch await agentCatalog.page(name: nil, status: nil, num: 1, size: 100) {
        case let .success(page):
            agents = page.records.compactMap { record in
                guard let id = record.id, let name = record.title else { return nil }
                return AgentOption(id: id, name: name)
            }
            // An edit of a channel whose agent is not on the first page still has to name it.
            if let row, let id = row.agentId, !agents.contains(where: { $0.id == id }) {
                agents.append(AgentOption(id: id, name: hxPresented(row.agentName) ?? "#\(id)"))
            }
        case let .failure(error):
            agentErrorText = ErrorMessage.text(for: error)
        }
        isLoadingAgents = false
    }

    /// Picking a type re-resolves the mode. Field text is deliberately kept: the underlying `configJson` keys
    /// are the same keys, and dropping the values here would silently undo a credential the server still has.
    public func setType(_ type: ChannelType) {
        typeChoice = type
        modeChoice = resolvedMode(type: type, stored: visibleModes(for: type).contains(modeChoice ?? .websocket) ? modeChoice : nil)
    }

    public func setMode(_ mode: ChannelMode) {
        guard let typeChoice else { return }
        if typeChoice.modes.contains(mode) { modeChoice = mode }
    }

    public func setFieldValue(_ text: String, for field: ChannelField) {
        fieldValues[field.key.rawValue] = text
    }

    public func fieldValue(for field: ChannelField) -> String {
        fieldValues[field.key.rawValue] ?? ""
    }

    public func save() async {
        isSaving = true
        errorText = nil
        defer { isSaving = false }
        let mode = modeChoice ?? typeChoice.map { resolvedMode(type: $0, stored: nil) } ?? .websocket
        let config = mergedConfig()
        let result: Result<EmptyResponse, APIError>
        if let row, let id = row.id {
            let change = ChannelChange(
                name: hxPresented(name),
                type: typeChoice?.rawValue,
                agentId: agentId,
                communicationMode: mode.rawValue,
                enabled: autoStart.hxInt,
                configJson: config.json(forWrite: true),
                enableThink: thinkingChoice.queryValue,
                enableSearch: searchEnabled.hxInt,
                enablePlan: planEnabled.hxInt,
                // The raw text, not `hxPresented`: an erased remark has to go out as `""`, because the
                // service keeps what is stored when the key is absent (`ChannelServiceImpl.kt:130`) and the
                // deleted text would come back on the next read. `EnvVarFormViewModel` clears the same way.
                description: description
            )
            result = await catalog.updateChannel(id: id, change)
        } else if row != nil {
            // A row the wire gave no id for cannot be written: the id is this route's only address. Falling
            // through to the create branch instead would POST a second channel.
            saved = false
            errorText = ErrorMessage.text(for: .unpackable)
            return
        } else if let typeChoice, let agentId, let draft = buildDraft(type: typeChoice, agentId: agentId, mode: mode, config: config) {
            result = await catalog.createChannel(draft)
        } else {
            // No type or no agent means there is no create body to encode. `canSubmit` keeps this unreachable
            // through the UI; the guard is what stops a direct call sending a half draft.
            saved = false
            errorText = ErrorMessage.text(for: .decoding)
            return
        }
        switch result {
        case .success:
            saved = true
            errorText = nil
        case let .failure(error):
            // A required-thinking model refuses an explicit off, and a blob over 20 000 characters refuses
            // the save (`ChannelServiceImpl.kt:83`, `:272-288`); both come back as the server's own sentence.
            saved = false
            errorText = ErrorMessage.text(for: error)
        }
    }

    private func buildDraft(type: ChannelType, agentId: Int64, mode: ChannelMode, config: ChannelConfig) -> ChannelDraft? {
        guard let name = hxPresented(name) else { return nil }
        return ChannelDraft(
            name: name,
            type: type.rawValue,
            agentId: agentId,
            communicationMode: mode.rawValue,
            enabled: autoStart.hxInt,
            configJson: config.json(forWrite: false),
            enableThink: thinkingChoice.queryValue,
            enableSearch: searchEnabled.hxInt,
            enablePlan: planEnabled.hxInt,
            description: hxPresented(description)
        )
    }

    /// Merge over the stored blob: a managed key with text replaces, a managed key with none removes, and
    /// every other key in the blob is carried through untouched.
    private func mergedConfig() -> ChannelConfig {
        var config = storedConfig
        for key in ChannelConfigKey.allCases {
            if let text = hxPresented(fieldValues[key.rawValue]) {
                config.setValue(text, for: key)
            } else {
                config.setValue(nil, for: key)
            }
        }
        return config
    }

    private func visibleModes(for type: ChannelType) -> [ChannelMode] { type.modes }

    private func resolvedMode(type: ChannelType, stored: ChannelMode?) -> ChannelMode {
        guard let stored, type.modes.contains(stored) else { return type.defaultMode }
        return stored
    }
}
