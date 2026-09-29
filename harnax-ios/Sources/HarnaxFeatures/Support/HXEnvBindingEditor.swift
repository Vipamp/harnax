import SwiftUI
import HarnaxCore
import HarnaxKit

/// One declared environment parameter, in the state a binding editor keeps for it.
///
/// The three sources of truth here are the *declaration* (`EnvParamEntry`, which says the name, the two
/// flags and the package's own default), the *choice* (`source`, plus either a variable id or typed text),
/// and the *read side* (`AgentEnvBinding`, an edit form's starting point). The declaration is kept whole
/// because the row has to say what it is even when the candidate lists moved on.
///
/// Web equivalent is `EnvBindingRow` (`harnax-webui/src/pages/agent/components/EnvParamTable.tsx:14`-`:19`),
/// which keeps `envValue` and `envVarId` side by side and decides precedence at submit time
/// (`customInput || !envVarId ? {customValue} : {envVarId}`, `CreateForm.tsx:163`-`:166`). iOS decides at
/// *edit* time instead: switching mode clears the other half, and `draft` reads whichever half is live.
/// The outcome on the wire is the same, and this shape is the one that cannot carry a mask into a request.
public struct HXEnvBindingRow: Identifiable, Equatable, Sendable {
    /// Where the value comes from. The web form has the same two states, spelled `customInput` and
    /// `envVarId` (`EnvParamTable.tsx:37`, the `-1` sentinel).
    public enum Source: Int, CaseIterable, Identifiable, Sendable {
        case reference
        case custom

        public var id: Int { rawValue }

        public var titleKey: String {
            switch self {
            case .reference: return "env.binding.reference"
            case .custom: return "env.binding.custom"
            }
        }
    }

    public let entry: EnvParamEntry
    public var source: Source
    /// Set only in `reference` mode. The id, never the value it stands for.
    public var envVarID: Int64?
    /// The variable's key as the read side named it. Display only, and only used when the id no longer
    /// resolves in the candidate list — a reference to another user's variable is legal to keep
    /// (`EnvVariableServiceImpl.kt:245`-`:252` scopes *new* picks to the caller, not stored ones), and
    /// dropping it silently would delete a binding the operator never touched.
    public var referenceName: String?
    /// What the operator typed. The only text this row can put into a request.
    public var customValue: String

    public init(
        entry: EnvParamEntry,
        source: Source = .reference,
        envVarID: Int64? = nil,
        referenceName: String? = nil,
        customValue: String = ""
    ) {
        self.entry = entry
        self.source = source
        self.envVarID = envVarID
        self.referenceName = referenceName
        self.customValue = customValue
    }

    /// The parameter's own name is its identity: the backend matches a binding to a declaration by it
    /// (`findMissingRequiredEnvParam` looks rows up by `envKey`), and a row without one cannot be sent.
    public var id: String { envKey }

    public var envKey: String { entry.envParamName }
    public var isRequired: Bool { entry.required }
    public var isSecret: Bool { entry.secret }
    public var note: String? { hxPresented(entry.description) }
    /// The package's own default. Display only: an empty row that has one says so, and nothing here ever
    /// submits it — the runtime reads the declaration itself.
    public var defaultValue: String? { hxPresented(entry.defaultValue) }

    /// Whether the row counts as filled — the same rule as `isEnvBindingFilled`
    /// (`harnax-webui/src/pages/agent/components/envBinding.ts:9`-`:13`): a reference id is a value, and
    /// typed text only counts once it has something in it.
    public var isFilled: Bool {
        if let envVarID, source == .reference, envVarID != 0 { return true }
        return !customValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Nothing chosen and nothing typed. This is the state that shows a package default in grey.
    public var isEmpty: Bool { !isFilled }

    /// The wire row. A live reference sends the id and nothing else; every other state sends the text,
    /// which for an untouched row is the empty string the web sends too
    /// (`CreateForm.tsx:163`-`:166`).
    ///
    /// The shape makes a mask unsendable rather than merely discouraged: `envVarID` is an `Int64`, the only
    /// `String` a row can emit is `customValue`, and the picker that fills `referenceName` never writes
    /// into it (`HXEntityPickerOption.subtitle` is not reachable from here).
    public var draft: AgentEnvBindingDraft {
        if source == .reference, let envVarID, envVarID != 0 {
            return .referenced(envKey: envKey, envVarID: envVarID)
        }
        return .custom(envKey: envKey, value: customValue)
    }

    /// The label the reference row shows. The candidate list wins when the id still resolves there, so the
    /// key on screen is the live one; an id that has fallen out of the list keeps the name it arrived with.
    public func referenceLabel(in candidates: [EnvVarCandidate]) -> String? {
        guard source == .reference, envVarID != nil else { return nil }
        if let candidate = candidates.first(where: { $0.id == envVarID }), let key = candidate.key {
            return key
        }
        return hxPresented(referenceName)
    }

    /// Start a row from what the server already had bound, the way the web edit form refills it: a
    /// reference comes back as a reference with its text cleared, a typed value comes back as text, and a
    /// row that resolved to nothing starts empty (`UpdateForm.tsx:81`-`:88`, `:118`-`:125`).
    public init(entry: EnvParamEntry, binding: AgentEnvBinding) {
        self.entry = entry
        if binding.referencesVariable, let envVarId = binding.envVarId, envVarId != 0 {
            source = .reference
            envVarID = envVarId
            referenceName = hxPresented(binding.envVarName)
            customValue = ""
        } else {
            source = .custom
            envVarID = nil
            referenceName = nil
            // `displayValue` is `customValue ?? envValue` — the text the operator typed. A referenced row
            // never lands here, so the resolved value (which would be a mask for a sensitive variable) is
            // not something this branch can read into `customValue`.
            customValue = binding.displayValue ?? ""
        }
    }
}

/// The environment-parameter table, re-laid out for a narrow screen: one section per declared parameter,
/// its name and flags as the title, and one value row that switches between referencing a variable and
/// typing a value.
///
/// Web: `harnax-webui/src/pages/agent/components/EnvParamTable.tsx`, a three-column table the spec asks to
/// be rebuilt as sections (`specs/01-agent-team.md` 注意点 7). Shared by the agent wizard's tool, MCP and CLI
/// steps, and by the team form whenever it grows a binding dimension — `showHint` is the one dial that
/// differs between them, see below.
public struct HXEnvBindingEditor: View {
    @Binding private var rows: [HXEnvBindingRow]
    private let candidates: [EnvVarCandidate]
    private let showDefaultHint: Bool

    /// The row currently asking for a variable, by its `envKey`.
    @State private var picking: HXEnvBindingKey?

    /// - Parameter showDefaultHint: whether an untouched row shows its package default in grey.
    ///   MCP and CLI say yes, because their declarations are delivered whole into the process and an empty
    ///   binding really does resolve to that default (`EnvParamTable.tsx:30`-`:34`, `McpConfigPanel.tsx:124`,
    ///   `CliConfigPanel.tsx:196`). Built-in tools say no: `agent_tool_env_param.default_value` never reaches
    ///   the runtime (`ToolConfigPanel.tsx:58`-`:64`), so showing it would promise a value that will not
    ///   arrive — and the required-parameter check refuses to count it for exactly the same reason
    ///   (`envBinding.ts:21`-`:35`).
    public init(
        rows: Binding<[HXEnvBindingRow]>,
        candidates: [EnvVarCandidate],
        showDefaultHint: Bool = false
    ) {
        self._rows = rows
        self.candidates = candidates
        self.showDefaultHint = showDefaultHint
    }

    public var body: some View {
        VStack(spacing: 10) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HXGroupCard {
                    header(row)
                    HXSegmented(
                        HXEnvBindingRow.Source.allCases.map {
                            HXSegmentOption(id: $0.rawValue, $0.titleKey)
                        },
                        selection: source(at: index)
                    )
                    .padding(.horizontal, 14)
                    .padding(.bottom, 12)
                    valueRow(row)
                }
            }
        }
        .sheet(item: $picking) { key in
            HXEntityPicker(
                titleKey: "env.picker.title",
                searchKey: "env.picker.search",
                emptyKey: "env.picker.empty",
                options: candidates.compactMap(option),
                selectedID: rows.first(where: { $0.id == key.rawValue })?.envVarID,
                onPick: { pick($0, into: key.rawValue) }
            )
        }
    }

    private func header(_ row: HXEnvBindingRow) -> some View {
        HXRow(
            text: row.envKey,
            subtitle: row.note,
            systemImage: "textformat.abc",
            divider: false
        ) {
            HStack(spacing: 6) {
                if row.isRequired { HXBadge("env.required", tone: .danger) }
                if row.isSecret { HXBadge("env.sensitive", tone: .warning) }
            }
        }
    }

    @ViewBuilder
    private func valueRow(_ row: HXEnvBindingRow) -> some View {
        switch row.source {
        case .reference:
            referenceControl(row)
        case .custom:
            HXField(
                "env.var.value.placeholder",
                text: Binding(
                    get: { row.customValue },
                    set: { newValue in setRow(row.id) { $0.customValue = newValue } }
                ),
                systemImage: "textcursor.text",
                // A secret parameter's value is a口令 meant for nobody else; the web field switches to
                // `type="password"` for the same reason (`EnvParamTable.tsx:162`).
                secure: row.isSecret
            )
            .padding(.horizontal, 14)
            .padding(.bottom, 12)
            hint(row, behind: true)
        }
    }

    /// The reference half: a button that opens the picker, the chosen variable's own display value under it,
    /// and a clear affordance that returns the row to empty.
    private func referenceControl(_ row: HXEnvBindingRow) -> some View {
        let candidate = candidates.first { $0.id == row.envVarID }
        return VStack(alignment: .leading, spacing: 8) {
            Button {
                picking = HXEnvBindingKey(row.id)
            } label: {
                HXRow(
                    text: row.referenceLabel(in: candidates) ?? hx("env.binding.choose"),
                    subtitle: nil,
                    systemImage: "link",
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 14)

            if row.envVarID != nil {
                HStack(spacing: 10) {
                    // The value the reference stands for, exactly as the server printed it: masked and locked
                    // for a sensitive variable (`EnvParamTable.tsx:168`-`:177`). Display only.
                    if let candidate {
                        if candidate.isSensitive {
                            Image(systemName: "lock.fill")
                                .font(.caption)
                                .foregroundStyle(Color.hx(.textTertiary))
                        }
                        HXValueText(candidate.displayValue ?? "")
                    } else {
                        // The id resolved to nothing in the candidate list. The binding stays; the screen says
                        // it cannot show the value rather than pretending there is none.
                        HXText("env.binding.unresolved")
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                    Spacer(minLength: 0)
                    Button {
                        clear(row.id)
                    } label: {
                        HXText("env.binding.clear")
                    }
                    .buttonStyle(.hxInline)
                }
                .padding(.horizontal, 14)
            } else {
                hint(row, behind: false)
            }
        }
        .padding(.bottom, 12)
    }

    /// The grey line an untouched row shows when its owner's default really does reach the runtime.
    @ViewBuilder
    private func hint(_ row: HXEnvBindingRow, behind: Bool) -> some View {
        if showDefaultHint, row.isEmpty, let defaultValue = row.defaultValue {
            HXText("env.binding.default", defaultValue)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .padding(.horizontal, 14)
                .padding(.bottom, behind ? 0 : 12)
        }
    }

    private func source(at index: Int) -> Binding<Int> {
        Binding(
            get: { index < rows.count ? rows[index].source.rawValue : 0 },
            set: { raw in
                guard let source = HXEnvBindingRow.Source(rawValue: raw), index < rows.count else { return }
                setRow(rows[index].id) { target in
                    // Switching halves clears the other one, so `draft` can never read a stale value the
                    // operator had already replaced with a reference (`EnvParamTable.tsx:64`-`:72`).
                    target.source = source
                    switch source {
                    case .reference: target.customValue = ""
                    case .custom:
                        target.envVarID = nil
                        target.referenceName = nil
                    }
                }
            }
        )
    }

    private func option(_ candidate: EnvVarCandidate) -> HXEntityPickerOption? {
        guard let key = candidate.key, let id = candidate.id else { return nil }
        return HXEntityPickerOption(
            id: id,
            title: key,
            // A sensitive variable's display value is the mask; the option says so with `isSecret` so the
            // row shows a lock instead of inviting the text to be read as a value.
            subtitle: candidate.displayValue,
            isSecret: candidate.isSensitive
        )
    }

    private func pick(_ candidate: HXEntityPickerOption, into key: String) {
        setRow(key) { row in
            row.source = .reference
            row.envVarID = candidate.id
            row.referenceName = candidate.title
            // Never the subtitle: that is the mask on a sensitive variable, and copying it into the value
            // half is the one mistake the spec names as unforgivable (注意点 8).
            row.customValue = ""
        }
    }

    private func clear(_ key: String) {
        setRow(key) { row in
            row.envVarID = nil
            row.referenceName = nil
            row.customValue = ""
        }
    }

    /// The rows rendered by the `ForEach` are values, so every edit goes back through the source array by
    /// `envKey` — the parameter's own name, which is what the backend matches a binding to.
    private func setRow(_ key: String, _ change: (inout HXEnvBindingRow) -> Void) {
        guard let index = rows.firstIndex(where: { $0.id == key }) else { return }
        change(&rows[index])
    }
}

/// The `sheet(item:)` payload: which row is asking for a variable.
private struct HXEnvBindingKey: Identifiable, Equatable {
    let rawValue: String
    var id: String { rawValue }

    init(_ rawValue: String) {
        self.rawValue = rawValue
    }
}
