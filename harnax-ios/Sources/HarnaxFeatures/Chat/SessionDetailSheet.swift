import SwiftUI
import HarnaxCore
import HarnaxKit

/// The conversation's detail sheet — this app's equivalent of the console's `DetailModal`, plus the editor the
/// console has no screen for.
///
/// Seven read-only panels, in the console's order, and only the ones with data: 基本信息, 执行者 (or 团队主管 for
/// a team conversation), 工具, 外部服务, 技能, CLI, 团队成员. Then an eighth of this app's own, 对话配置, which is
/// the only one whose rows are controls: it is `GET`/`PUT /api/admin/sessions/{sessionId}/config`, the admin
/// route the console never calls because it drives the same four columns through the runtime's command channel
/// instead (`SessionConfiguring.swift:184-189`).
///
/// The seven come from two reads, the same two the console splits them across
/// (`harnax-webui/src/pages/session/components/DetailModal.tsx:112-148`). The conversation's own row, handed
/// over by the list, fills 基本信息 and 执行者 and the two lists it denormalises. 工具, CLI and 团队成员 exist
/// nowhere but the executor's row, and a team's row is the only place that says whether a lead skill or a
/// member is still there — so this sheet reads `GET /api/admin/agents/{id}` or `GET /api/admin/teams/{id}`
/// when it opens, whichever the row names (`ExecutorReading.swift`). Those three panels are absent until it
/// answers, and a refusal leaves them absent with a line saying which of the two it is rather than a panel
/// claiming the conversation binds nothing.
///
/// No leg means no read and no line: a host that only lists conversations gets the snapshot panels, which are
/// the ones it can answer for.
///
/// The catalogue is observed because the tool panel picks its name column per language, the way the agent
/// card's drill-down does — a language switched inside the app has to re-resolve the rows already on screen.
public struct SessionDetailSheet: View {
    @StateObject private var vm: SessionDetailViewModel
    @ObservedObject private var catalog = HarnaxCatalog.shared

    public init(
        session: SessionSummary,
        config: (any SessionConfiguring)? = nil,
        executor: (any ExecutorReading)? = nil,
        onWritten: (() -> Void)? = nil
    ) {
        _vm = StateObject(
            wrappedValue: SessionDetailViewModel(
                session: session,
                config: config,
                executor: executor,
                onWritten: onWritten
            )
        )
    }

    public var body: some View {
        HXBindingSheet(title: hx("chat.detail.title")) {
            content
        }
        // Sliding the sheet away mid-write would lose a request the server has already taken.
        .interactiveDismissDisabled(vm.isSavingConfig)
        .task { await vm.loadExecutor() }
    }

    @ViewBuilder
    private var content: some View {
        if vm.hasNothingToShow {
            // Reachable: every column of `SessionSummary` is optional, so a row answered with only an `id`
            // opens a sheet with nothing to say.
            HXStateView(.empty, message: hx("chat.detail.empty"))
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(Array(vm.sections.enumerated()), id: \.offset) { _, section in
                        VStack(alignment: .leading, spacing: 0) {
                            HXSectionHeader(section.titleKey)
                            HXGroupCard {
                                ForEach(Array(section.rows.enumerated()), id: \.offset) { index, row in
                                    rowView(row, divided: index < section.rows.count - 1)
                                }
                            }
                        }
                    }
                    executorStatus
                    configCard
                    if let updated = vm.updatedLine {
                        Text(verbatim: updated)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 20)
            }
        }
    }

    // MARK: - the executor read

    /// The one line this sheet can say about a read it is still waiting on, or one the server refused.
    ///
    /// It sits under the panels rather than inside each of them: the snapshot half of the sheet is already
    /// answered, and a spinner in three empty groups would read as three separate failures where the console,
    /// which has no snapshot panels to keep drawing over, can afford one per group
    /// (`DetailModal.tsx:295-296`, `:518-519`). A refusal keeps its own retry control because the answer is
    /// one tap away and the panels that depend on it are otherwise simply missing.
    @ViewBuilder
    private var executorStatus: some View {
        if vm.isLoadingExecutor {
            HStack(spacing: 8) {
                ProgressView()
                Text(verbatim: hx("chat.detail.executors.loading"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        } else if let notice = vm.executorNotice {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: notice)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    Task { await vm.retryExecutorLoad() }
                } label: {
                    HXText("common.retry")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.hx(.brand))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - chat configuration

    /// The four fields the conversation owns, and the only write this screen can make.
    ///
    /// The card is last, after the panels that describe who runs the conversation: it is the one block whose
    /// rows are controls, and putting it among the reading would make a form out of a drill-down.
    ///
    /// Its values come off the row the list already handed over, which is the same reading the chat tab makes
    /// of the DTO (`SessionChatConfig.init(from:)`), so an absent flag shows as off rather than as unknown. The
    /// `编辑` control, and with it the whole editing state, only appears when the host wired the admin config
    /// leg, the row carries the string key that route addresses, and the conversation is switched on — the
    /// server refuses a disabled one with its own `403` before either read or write gets anywhere.
    private var configCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("chat.detail.section.config")
            HXGroupCard {
                HXRow(text: hx("chat.composer.think"), subtitle: vm.configThinkHint) {
                    configSwitch(
                        "chat.composer.think",
                        isOn: Binding(get: { vm.displayedConfig.enableThink }, set: { vm.setConfigThink($0) }),
                        live: vm.canToggleConfigThink
                    )
                }
                HXRow(text: hx("chat.composer.search"), subtitle: vm.configSearchHint) {
                    configSwitch(
                        "chat.composer.search",
                        isOn: Binding(get: { vm.displayedConfig.enableSearch }, set: { vm.setConfigSearch($0) }),
                        live: vm.canToggleConfigSearch
                    )
                }
                HXRow(text: hx("chat.composer.plan")) {
                    configSwitch(
                        "chat.composer.plan",
                        isOn: Binding(get: { vm.displayedConfig.enablePlan }, set: { vm.setConfigPlan($0) }),
                        live: vm.canToggleConfigPlan
                    )
                }
                permissionRow
            }
            configControls
            if let notice = vm.configNotice {
                Text(verbatim: notice)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// One switch for both states of the panel: the position is the value whether or not the draft is open, so
    /// the reading state shows the same control greyed out rather than a second copy of the sentence.
    private func configSwitch(_ labelKey: String, isOn: Binding<Bool>, live: Bool) -> some View {
        Toggle(hx(labelKey), isOn: isOn)
            .labelsHidden()
            .tint(Color.hx(.brand))
            .disabled(!live)
    }

    /// The mode reads as one of five sentences in both states — the menu while the draft is open, the chosen
    /// sentence on its own otherwise. The five labels are the runtime's own vocabulary
    /// (`ChatPermissionMode`), shared with the chat tab's permission chip.
    private var permissionRow: some View {
        HXRow("chat.detail.field.permissionMode", divider: false) {
            if vm.canChooseConfigPermissionMode {
                Picker(selection: Binding(
                    get: { vm.displayedConfig.permissionMode },
                    set: { vm.setConfigPermissionMode($0) }
                )) {
                    ForEach(ChatPermissionMode.allCases, id: \.rawValue) { mode in
                        Text(verbatim: hx(mode.titleKey)).tag(mode)
                    }
                } label: {
                    HXText("chat.detail.field.permissionMode")
                }
                .pickerStyle(.menu)
                .tint(Color.hx(.brand))
            } else {
                Text(verbatim: hx(vm.displayedConfig.permissionMode.titleKey))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textPrimary))
                    .multilineTextAlignment(.trailing)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private var configControls: some View {
        if vm.isEditingConfig {
            HStack(spacing: 10) {
                Button {
                    vm.cancelConfigEdit()
                } label: {
                    HXText("common.cancel")
                }
                .buttonStyle(HXSecondaryButtonStyle())
                .disabled(vm.isSavingConfig)

                Button {
                    Task { await vm.saveConfig() }
                } label: {
                    if vm.isSavingConfig {
                        ProgressView()
                    } else {
                        HXText("common.save")
                    }
                }
                .buttonStyle(HXPrimaryButtonStyle())
                .disabled(!vm.canSaveConfig)

                Spacer(minLength: 0)
            }
        } else if vm.canEditConfig {
            Button {
                vm.beginConfigEdit()
            } label: {
                HXText("state.action.edit")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.hx(.brand))
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    @ViewBuilder
    private func rowView(_ row: SessionDetailRow, divided: Bool) -> some View {
        switch row.layout {
        case .inlineValue:
            if let labelKey = row.labelKey {
                HXRow(labelKey, divider: divided) {
                    trailing(row)
                }
            } else {
                entry(row, divided: divided)
            }
        case .stackedValue:
            if let labelKey = row.labelKey {
                HXRow(labelKey, subtitle: row.value, divider: divided) {
                    marksView(row.marks)
                }
            } else {
                entry(row, divided: divided)
            }
        case .code:
            codeBlock(row, divided: divided)
        case let .paragraph(expandable):
            promptBlock(row, expandable: expandable, divided: divided)
        }
    }

    /// A skill or an MCP server: the server's own word as the title, its description under it.
    private func entry(_ row: SessionDetailRow, divided: Bool) -> some View {
        HXRow(
            text: row.value ?? "",
            subtitle: row.detail,
            divider: divided
        ) {
            marksView(row.marks)
        }
    }

    @ViewBuilder
    private func trailing(_ row: SessionDetailRow) -> some View {
        if row.marks.isEmpty {
            valueText(row.value)
        } else if let value = row.value {
            HStack(spacing: 8) {
                valueText(value)
                marksView(row.marks)
            }
        } else {
            marksView(row.marks)
        }
    }

    /// A field's own value. Never rendered for a row that has none — the sheet hides the line instead of
    /// printing a placeholder.
    @ViewBuilder
    private func valueText(_ value: String?) -> some View {
        if let value {
            Text(verbatim: value)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textPrimary))
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private func marksView(_ marks: [SessionDetailMark]) -> some View {
        if marks.isEmpty {
            EmptyView()
        } else {
            HXFlow(spacing: 6) {
                ForEach(Array(marks.enumerated()), id: \.offset) { _, mark in
                    switch mark {
                    case let .badge(key, tone):
                        HXBadge(key, tone: tone)
                    case let .chip(text, tone):
                        HXChip(text, tone: tone)
                    }
                }
            }
        }
    }

    /// The business key at full width: it is long, it is monospaced, and it is the one value worth taking
    /// off the screen. `HXValueText` truncates it in the middle; the button hands over the whole string.
    private func codeBlock(_ row: SessionDetailRow, divided: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(row.labelKey)
            HStack(alignment: .center, spacing: 8) {
                HXValueText(row.value ?? "", lines: 2)
                Button {
                    if let value = row.value { HXPasteboard.copy(value) }
                } label: {
                    Image(systemName: "doc.on.doc")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: hx("common.copy")))
            }
            marksView(row.marks)
        }
        .blockPadding(divided: divided)
    }

    /// The system prompt. Collapsed to four lines with the console's expand control
    /// (`DetailModal.tsx:266-278`).
    private func promptBlock(_ row: SessionDetailRow, expandable: Bool, divided: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(row.labelKey)
            Text(verbatim: row.value ?? "")
                .font(.caption.monospaced())
                .foregroundStyle(Color.hx(.textPrimary))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            if expandable {
                Button {
                    vm.togglePrompt()
                } label: {
                    HXText(vm.isPromptExpanded ? "chat.detail.prompt.collapse" : "chat.detail.prompt.expand")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.hx(.brand))
                }
                .buttonStyle(.plain)
            }
        }
        .blockPadding(divided: divided)
    }

    @ViewBuilder
    private func fieldLabel(_ key: String?) -> some View {
        if let key {
            HXText(key)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }
}

/// The padding and hairline `HXRow` gives its own rows, so a block that is not an `HXRow` still lines up
/// with the ones that are.
private extension View {
    func blockPadding(divided: Bool) -> some View {
        padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .bottom) {
                if divided {
                    Color.hx(.separator).frame(height: 1)
                }
            }
    }
}
