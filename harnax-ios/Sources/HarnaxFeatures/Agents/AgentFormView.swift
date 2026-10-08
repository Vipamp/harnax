import SwiftUI
import HarnaxCore
import HarnaxKit

/// C2/C3 — the five-step agent wizard, in both modes.
///
/// One view for create and edit because the two web components are the same form with a different seed and a
/// different submit call (`CreateForm.tsx`/`UpdateForm.tsx` — step for step identical,
/// `harnax-webui/src/pages/agent/components/UpdateForm.tsx:330`-`:336`). Everything that decides — which step
/// may close, which candidates a row may offer, what goes on the wire — lives in `AgentFormViewModel`; this
/// type only lays it out.
///
/// Three shapes change from the console rather than being copied: the four `Select`s become one searchable sheet
/// (`HXEntityPicker`), because a 100-row dropdown is unreadable on a handset and the spec asks for the search
/// (`specs/01-agent-team.md` 注意点 4); the parameter tables become stacked sections
/// (`HXEnvBindingEditor`) instead of the web's three-column table (注意点 7); and the web's non-clickable
/// `Steps` become chips that may jump anywhere (`stepChips`). What is carried over unchanged: the two capability
/// steps stay reachable when the model cannot do them, and a row keeps the exclusion set the web applies to every
/// dropdown.
public struct AgentFormView: View {
    @StateObject private var vm: AgentFormViewModel
    /// The panel an update offers posts through; the wizard owns no refresh endpoint of its own.
    private let refresher: any SessionRefreshing
    private let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss

    /// Which control is asking for an entity. One sheet for all six pickers, because six separate flags could
    /// leave two of them open at once.
    @State private var picking: Pick?
    /// The server's own sentence, shown verbatim (`AgentWizardResult.failed`).
    @State private var failure: String?
    /// The re-entrant save refusal: a tap that landed while a save is already running. It carries no issue,
    /// and rendering nothing for it would leave the button looking dead.
    @State private var saveBlocked = false
    /// What a step refused is *not* held here. `AgentFormViewModel.issues(for:)` owns it, because the state
    /// has to outlive a tap on any step chip: `save()` jumps to the step that held the save open, and the
    /// sentence explaining that jump used to be wiped by the very next tap.
    /// Set by an update: the wizard stays on screen while the panel is up and closes once it goes.
    @State private var refreshTarget: SessionRefreshTarget?
    @State private var showingRefresh = false
    @State private var pendingDismiss = false

    /// - Parameter vm: the wizard's own view model, in either mode. It seeds an edit off the list row, so the
    ///   caller hands in the row it was standing on (`AgentFormViewModel.Mode.edit`).
    public init(
        vm: AgentFormViewModel,
        sessionRefresher: any SessionRefreshing,
        onSaved: @escaping () -> Void = {}
    ) {
        _vm = StateObject(wrappedValue: vm)
        self.refresher = sessionRefresher
        self.onSaved = onSaved
    }

    /// Assembly from the app's dependency bag, so the host that pushes the wizard does not have to reach past
    /// the bag for the seven catalogs it reads and the refresh panel writes.
    public init(
        dependencies: HarnaxDependencies,
        mode: AgentFormViewModel.Mode,
        account: AccountSnapshot?,
        onSaved: @escaping () -> Void = {}
    ) {
        self.init(
            vm: AgentFormViewModel(dependencies: dependencies, mode: mode, account: account),
            sessionRefresher: dependencies.sessionRefresher,
            onSaved: onSaved
        )
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    stepChips
                    notices
                    stepContent
                }
                .padding(16)
            }
            // The picker rides on the page, the refresh panel on the stack: two sheets on one view would let
            // SwiftUI keep only the first.
            .sheet(item: $picking) { pick in pickerSheet(pick) }
            .safeAreaInset(edge: .bottom) { footer }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx(vm.titleKey)))
            #if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { HXText("common.cancel") }
                }
            }
        }
        .sheet(isPresented: $showingRefresh, onDismiss: finishOncePanelGone) {
            if let refreshTarget {
                HXSessionRefreshSheet(target: refreshTarget, refresher: refresher)
            }
        }
        .task { await vm.load(vm.step) }
        .onChange(of: vm.step) { _, step in
            // A step's candidates are read once: `load` is a no-op for a step already read, so walking back and
            // forth costs nothing and a jump from a chip still gets its own list.
            Task { await vm.load(step) }
        }
    }

    // MARK: - Step indicator

    /// The five web `Steps` as a chip row (`CreateForm.tsx:226`-`:232`). The web's own `Steps` are *not*
    /// clickable — `CreateForm.tsx:226` gives them no `onChange`, so an operator there can only walk the
    /// sequence. Jumping is our deliberate addition: on a handset the five-step walk is a long way to send
    /// someone back when all they want is to change one MCP row.
    private var stepChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(AgentWizardStep.allCases) { step in
                    Button {
                        move(to: step)
                    } label: {
                        HXChip(hx(step.titleKey), tone: chipTone(for: step))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 2)
        }
    }

    /// Current is brand, a step the wizard has refused is refused, and only a step behind the current one
    /// that nothing was found in reads as finished. Painting everything before the current step green — as
    /// this did — told the operator that the step which had just refused the save was behind them.
    private func chipTone(for step: AgentWizardStep) -> PaletteSlot? {
        if step == vm.step { return .brand }
        if !vm.issues(for: step).isEmpty { return .danger }
        return step.rawValue < vm.step.rawValue ? .success : nil
    }

    // MARK: - Notices

    /// The things that can be wrong on a step, in the order an operator reads them: what this step refused,
    /// what a save that was already running turned into, what the server said, and what a failed candidate
    /// list left behind.
    @ViewBuilder
    private var notices: some View {
        if !currentIssues.isEmpty {
            HXBanner(
                "state.error.title",
                message: currentIssues.map(\.message).joined(separator: "\n"),
                systemImage: "exclamationmark.triangle",
                tone: .danger
            )
        }
        if saveBlocked {
            HXBanner("agent.wizard.save.inFlight", systemImage: "hourglass", tone: .warning)
        }
        if let failure {
            HXBanner("state.error.title", message: failure, systemImage: "exclamationmark.triangle", tone: .danger)
        }
        if let error = vm.candidateError {
            // `reload(_:)` is the escape hatch behind a failed list: `load` would skip a step already read.
            HXStateView(.error, message: error, retry: { Task { await vm.reload(vm.step) } })
        } else if vm.isPreparing {
            HXStateView(.loading)
        }
    }

    /// The step on screen shows the issues that belong to it, whenever they were found: `next()` refusing it
    /// directly, or `save()` finding them from the last step and jumping back. They stay until that step is
    /// validated again, which is the model's rule rather than this view's.
    private var currentIssues: [AgentWizardIssue] {
        vm.issues(for: vm.step)
    }

    // MARK: - Steps

    @ViewBuilder
    private var stepContent: some View {
        switch vm.step {
        case .basic: basicStep
        case .tool: toolStep
        case .mcp: mcpStep
        case .skill: skillStep
        case .cli: cliStep
        }
    }

    // MARK: - Step 1

    /// Name, description, prompt, model, owner, visibility (`CreateForm.tsx:236`-`:266`). The owner line is
    /// display only and the visibility switch belongs to the account, not to the row.
    ///
    /// Every field carries a label, the way the web's `Form.Item` does (`CreateForm.tsx:238`-`:246`;
    /// `specs/01-agent-team.md:69`-`:71`). `HXField` renders only a placeholder, so with the placeholders
    /// alone the two multiline boxes below are indistinguishable from each other the moment the operator
    /// types — a description and a system prompt are both prose.
    private var basicStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            HXGroupCard {
                labeled("agent.wizard.name") {
                    HXField("agent.wizard.name.placeholder", text: $vm.name, systemImage: "textformat")
                }
                labeled("agent.wizard.description") {
                    multilineField("agent.wizard.description.placeholder", text: $vm.detail, lines: 1...4)
                }
                labeled("agent.wizard.prompt") {
                    multilineField("agent.wizard.prompt.placeholder", text: $vm.systemPrompt, lines: 1...9)
                }
                .padding(.bottom, 14)
            }
            modelRow
            ownerRow
            visibilityRow
            selfWriteRow
        }
    }

    /// A labelled block inside a group card: the label is the field's name, not another placeholder.
    private func labeled<Content: View>(
        _ labelKey: String,
        @ViewBuilder _ content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HXText(labelKey)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.hx(.textSecondary))
            content()
        }
        .padding(.horizontal, 14)
        .padding(.top, 14)
    }

    /// The one line the web prints per model option stays whole — name, provider and price
    /// (`CreateForm.tsx:252`); `AgentFormViewModel.modelOptions` renders it and the row only shows the result.
    ///
    /// Two things the web's `Select` gives this field and a row cannot: `allowClear`
    /// (`CreateForm.tsx:249`, `UpdateForm.tsx:354`) and a degraded look for a reference that no longer
    /// resolves (`specs/01-agent-team.md:338` 注意点 12). The clear sits on the row that opens the picker
    /// rather than inside `HXEntityPicker` itself — the sheet has no clear of its own, and the skill card
    /// below already uses this inline-button shape for the same act.
    private var modelRow: some View {
        HXGroupCard {
            Button {
                picking = .model
            } label: {
                HXRow(
                    "agent.wizard.model",
                    subtitle: nil,
                    systemImage: "cpu",
                    divider: false
                ) {
                    HStack(spacing: 6) {
                        Text(verbatim: vm.modelID == nil ? hx("agent.wizard.model.placeholder") : vm.modelDisplayName)
                            .font(.subheadline)
                            .foregroundStyle(modelValueColor)
                            .lineLimit(2)
                            .multilineTextAlignment(.trailing)
                        HXChevron()
                    }
                }
            }
            .buttonStyle(.plain)
            if vm.modelID != nil {
                HStack(spacing: 10) {
                    Spacer(minLength: 0)
                    Button {
                        vm.clearModel()
                    } label: {
                        HXText("env.binding.clear")
                    }
                    .buttonStyle(.hxInline)
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 10)
            }
        }
    }

    /// A stale reference reads degraded, not chosen: the name carries the web's 「该模型已停用、已删除或不再是
    /// 对话模型」 suffix and the warning tone is what tells the eye before the sentence is read.
    private var modelValueColor: Color {
        if vm.modelID == nil { return Color.hx(.textTertiary) }
        return vm.isModelStale ? Color.hx(.warning) : Color.hx(.textPrimary)
    }

    /// Read-only, and not by accident: the backend writes the current account itself
    /// (`AgentServiceImpl.kt:124`), so `owner` never leaves this screen (`buildDraft` has no owner field).
    private var ownerRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HXGroupCard {
                HXRow(
                    "agent.wizard.owner",
                    subtitle: vm.ownerName.isEmpty ? hx("agent.wizard.owner.auto") : vm.ownerName,
                    systemImage: "person",
                    divider: false
                ) {
                    Image(systemName: "lock.fill")
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
            }
        }
    }

    /// `canChangeVisibility` is the console's `isPublicSwitchDisabled`
    /// (`harnax-webui/src/utils/permissionUtil.ts:57`-`:75`): a switch an account may not touch is dimmed,
    /// locked and told in the console's own words rather than left to look broken.
    private var visibilityRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HXGroupCard {
                HXRow(text: hx("agent.wizard.isPublic"), subtitle: nil, divider: false) {
                    Toggle(isOn: Binding(get: { vm.isPublic }, set: { vm.setIsPublic($0) })) {
                        HXText("agent.wizard.isPublic")
                    }
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .tint(Color.hx(.brand))
                    .disabled(!vm.canChangeVisibility)
                }
                .opacity(vm.canChangeVisibility ? 1 : 0.5)
            }
            Text(verbatim: hx(vm.canChangeVisibility ? "agent.wizard.public.hint" : "agent.wizard.noPermission"))
                .font(.footnote)
                .foregroundStyle(Color.hx(vm.canChangeVisibility ? .textTertiary : .warning))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The 技能自我进化 switch: whether this agent may write skill drafts of its own
    /// (`AgentResponse.kt:57-58`, `specs/07-skill-draft-review.md` §3).
    ///
    /// The switch is a *switch*, not a stored 0/1: an operator who opens the edit form and saves without
    /// touching it must leave `skillSelfWrite` absent, because the update route keeps whatever the row already
    /// holds when the key is missing (`AgentServiceImpl.kt:180`) and sending the seeded value back would turn
    /// a read into a write the operator never asked for. `AgentFormViewModel.buildDraft` is what keeps that
    /// promise; the view only ever calls `setSkillSelfWrite`.
    private var selfWriteRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HXGroupCard {
                HXRow(text: hx("agent.wizard.selfWrite"), subtitle: nil, divider: false) {
                    Toggle(isOn: Binding(get: { vm.skillSelfWrite }, set: { vm.setSkillSelfWrite($0) })) {
                        HXText("agent.wizard.selfWrite")
                    }
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .tint(Color.hx(.brand))
                }
            }
            Text(verbatim: hx("agent.wizard.selfWrite.hint"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Step 2

    private var toolStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !vm.supportsTools {
                HXBanner("agent.wizard.tool.unsupported", systemImage: "exclamationmark.triangle", tone: .warning)
            }
            toolPanel
        }
    }

    private var toolPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("agent.wizard.tool.hint", actionKey: "agent.wizard.tool.add", action: vm.addToolRow)
            if vm.toolRows.isEmpty {
                emptyLine("agent.wizard.tool.empty")
            }
            ForEach(Array(vm.toolRows.enumerated()), id: \.element.id) { index, row in
                toolCard(index, row)
            }
        }
        // The web dims the panel and swallows its taps instead of hiding the step (`CreateForm.tsx:262`-`:268`),
        // so what is already configured stays readable.
        .opacity(vm.supportsTools ? 1 : 0.4)
        .allowsHitTesting(vm.supportsTools)
    }

    private func toolCard(_ index: Int, _ row: AgentToolRow) -> some View {
        HXGroupCard {
            Button {
                picking = .tool(row.id)
            } label: {
                HXRow(
                    text: row.shownName ?? hx("agent.wizard.tool.select"),
                    subtitle: nil,
                    systemImage: "wrench.and.screwdriver",
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            .hxOrderActions(index: index, count: vm.toolRows.count, move: vm.moveToolRow)
            HStack(spacing: 10) {
                HXText("agent.wizard.tool.needConfirm")
                    .font(.subheadline)
                    .foregroundStyle(Color.hx(.textPrimary))
                Spacer(minLength: 8)
                if vm.confirmIsLocked(for: row.id) {
                    // A tool that declares confirmation keeps it: the switch may be tightened, never loosened
                    // (`AgentServiceImpl.kt:425`-`:427`).
                    Image(systemName: "lock.fill")
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                Toggle(isOn: Binding(get: { row.needConfirm }, set: { vm.setToolConfirm($0, of: row.id) })) {
                    HXText("agent.wizard.tool.needConfirm")
                }
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(Color.hx(.brand))
                .disabled(vm.confirmIsLocked(for: row.id))
                // The row's place in the tool list is what the runtime walks, so the handles ride on the card's
                // own action row rather than a header the card does not have.
                HXOrderHandles(index: index, count: vm.toolRows.count, move: vm.moveToolRow)
                // Tools are the one dimension whose rows can go all the way down to none
                // (`specs/01-agent-team.md:175`).
                trash { vm.removeToolRow(row.id) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            if !row.env.isEmpty {
                // Built-in tools take no default hint: their declared default never reaches the runtime
                // (`ToolConfigPanel.tsx:58`-`:64`).
                HXEnvBindingEditor(rows: vm.toolEnvBinding(for: row.id), candidates: vm.envCandidates)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
            }
        }
    }

    // MARK: - Step 3

    private var mcpStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !vm.supportsMCP {
                HXBanner("agent.wizard.mcp.unsupported", systemImage: "exclamationmark.triangle", tone: .warning)
            }
            mcpPanel
        }
    }

    private var mcpPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("agent.wizard.mcp.hint", actionKey: "agent.wizard.mcp.add", action: vm.addMcpRow)
            if vm.mcpRows.isEmpty {
                emptyLine("agent.wizard.mcp.empty")
            }
            ForEach(Array(vm.mcpRows.enumerated()), id: \.element.id) { index, row in
                mcpCard(index, row)
            }
        }
        .opacity(vm.supportsMCP ? 1 : 0.4)
        .allowsHitTesting(vm.supportsMCP)
    }

    /// - Parameter index: the card's place in the panel, which is what the web titles it by
    /// (`McpConfigPanel.tsx:96`-`:98` 「MCP #n」) and the only handle an operator has when several rows say
    /// the same thing.
    private func mcpCard(_ index: Int, _ row: AgentMcpRow) -> some View {
        HXGroupCard {
            Text(verbatim: hx("agent.wizard.mcp.row", index + 1))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.hx(.textSecondary))
                .padding(.horizontal, 14)
                .padding(.top, 12)
            Button {
                picking = .mcp(row.id)
            } label: {
                HXRow(
                    text: row.shownName ?? hx("agent.wizard.mcp.select"),
                    subtitle: nil,
                    systemImage: "link",
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            .hxOrderActions(index: index, count: vm.mcpRows.count, move: vm.moveMcpRow)
            HStack(spacing: 10) {
                Spacer(minLength: 0)
                // The way out of a row the trash will not take. The web's `Select` is `allowClear`
                // (`McpConfigPanel.tsx:107`), and clearing zeroes the whole row with its parameter table —
                // without it the refusal 「请选择 MCP 服务，或删除这一空行」 promises a delete that a lone row
                // cannot get (`:98`-`:102`).
                if row.mcpID != nil {
                    Button {
                        vm.clearMCP(into: row.id)
                    } label: {
                        HXText("env.binding.clear")
                    }
                    .buttonStyle(.hxInline)
                }
                HXOrderHandles(index: index, count: vm.mcpRows.count, move: vm.moveMcpRow)
                // The last MCP row cannot be deleted (`McpConfigPanel.tsx:98`-`:102`); `removeMcpRow` refuses
                // it too, so the affordance goes rather than promising a tap that does nothing.
                if vm.canRemoveMcpRow {
                    trash { vm.removeMcpRow(row.id) }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            if !row.env.isEmpty {
                HXEnvBindingEditor(
                    rows: vm.mcpEnvBinding(for: row.id),
                    candidates: vm.envCandidates,
                    showDefaultHint: true
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
            }
        }
    }

    // MARK: - Step 4

    private var skillStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("agent.wizard.skill.hint", actionKey: "agent.wizard.skill.add", action: vm.addSkillRow)
            if vm.skillRows.isEmpty {
                emptyLine("agent.wizard.skill.empty")
            }
            ForEach(Array(vm.skillRows.enumerated()), id: \.element.id) { index, row in
                skillCard(index, row)
            }
        }
    }

    private func skillCard(_ index: Int, _ row: AgentSkillRow) -> some View {
        HXGroupCard {
            Button {
                picking = .repository(row.id)
            } label: {
                HXRow(
                    text: row.repositoryName ?? hx("agent.wizard.repository.select"),
                    subtitle: nil,
                    systemImage: "square.stack",
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            .hxOrderActions(index: index, count: vm.skillRows.count, move: vm.moveSkillRow)
            // Clearing the repository zeroes the whole row — the web's `allowClear` does the same
            // (`SkillConfigPanel.tsx:111`), because a row with a repository and no skill is the state the
            // validator refuses, and this is the only way out of the one row left on screen.
            if row.repositoryID != nil {
                Button {
                    Task { await vm.pickRepository(nil, into: row.id) }
                } label: {
                    HXText("env.binding.clear")
                }
                .buttonStyle(.hxInline)
                .padding(.leading, 14)
                .padding(.bottom, 8)
            }
            Button {
                picking = .skill(row.id)
            } label: {
                HXRow(
                    text: row.skillName ?? hx("agent.wizard.skill.select"),
                    subtitle: row.repositoryName.map { hx("agent.wizard.skill.repository", $0) },
                    systemImage: "puzzlepiece.extension",
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            // The skill dropdown has nothing to list until a repository is chosen
            // (`SkillConfigPanel.tsx:129`).
            .disabled(row.repositoryID == nil)
            .opacity(row.repositoryID == nil ? 0.5 : 1)
            if row.skillID != nil {
                HStack(spacing: 10) {
                    Spacer(minLength: 0)
                    Button {
                        vm.clearSkill(row.id)
                    } label: {
                        HXText("env.binding.clear")
                    }
                    .buttonStyle(.hxInline)
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 8)
            }
            HStack(spacing: 10) {
                Spacer(minLength: 0)
                // The card's place in the list is what goes on the wire as the comma string's order
                // (`AgentSaveDraft.swift` joins `skillIDs` with 「,」), so this step is arranged like the other
                // three.
                HXOrderHandles(index: index, count: vm.skillRows.count, move: vm.moveSkillRow)
                trash { vm.removeSkillRow(row.id) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
    }

    // MARK: - Step 5

    private var cliStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader(
                "agent.wizard.cli.hint",
                actionKey: "agent.wizard.cli.add",
                disabled: !vm.canAddCLI,
                action: vm.addCLI
            )
            if vm.selectedCLIIDs.isEmpty {
                emptyLine("agent.wizard.cli.empty")
            }
            ForEach(Array(vm.selectedCLIIDs.enumerated()), id: \.element) { index, id in
                cliCard(index, id)
            }
        }
    }

    private func cliCard(_ index: Int, _ id: Int64) -> some View {
        HXGroupCard {
            Button {
                picking = .cli(index)
            } label: {
                HXRow(
                    text: vm.cliName(for: id),
                    subtitle: nil,
                    systemImage: "terminal",
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            .hxOrderActions(index: index, count: vm.selectedCLIIDs.count, move: vm.moveCLI)
            HStack(spacing: 8) {
                if let skill = vm.shippedSkillName(for: id) {
                    // The package's own skill: loaded by the runtime, not chosen here
                    // (`specs/01-agent-team.md`:「CLI 包自带技能」).
                    HXText("agent.wizard.cli.skills")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textTertiary))
                    HXChip(skill, tone: .brand)
                }
                Spacer(minLength: 8)
                // The tables live in `cliEnv`, keyed by package id, so moving a card takes its typed values
                // along and leaves the others' behind.
                HXOrderHandles(index: index, count: vm.selectedCLIIDs.count, move: vm.moveCLI)
                trash { vm.removeCLI(at: index) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            if (vm.cliEnv[id] ?? []).isEmpty {
                emptyLine("agent.wizard.cli.noEnvParams")
                    .padding(.horizontal, 14)
                    .padding(.bottom, 12)
            } else {
                HXEnvBindingEditor(
                    rows: vm.cliEnvBinding(for: id),
                    candidates: vm.envCandidates,
                    showDefaultHint: true
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
            }
        }
    }

    // MARK: - Shared parts

    /// The web's own step header: a secondary sentence on the left, the add button on the right
    /// (`ToolConfigPanel.tsx:92`-`:98`).
    private func sectionHeader(
        _ hintKey: String,
        actionKey: String,
        disabled: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        HStack(alignment: .top, spacing: 10) {
            HXText(hintKey)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Button(action: action) {
                HXText(actionKey)
            }
            .buttonStyle(.hxInline)
            // 「添加 CLI」 is disabled once every package has a card (`CliConfigPanel.tsx:135`-`:136`).
            .disabled(disabled)
        }
    }

    private func emptyLine(_ key: String) -> some View {
        HXText(key)
            .font(.footnote)
            .foregroundStyle(Color.hx(.textTertiary))
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func trash(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: "trash")
                .foregroundStyle(Color.hx(.danger))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: hx("state.action.delete")))
    }

    /// `HXField` is a one-line box. Description and prompt are `TEXT` columns the web gives 3 and 8 rows to, so
    /// they grow here rather than get a ceiling the backend does not impose.
    private func multilineField(
        _ placeholderKey: String,
        text: Binding<String>,
        lines: ClosedRange<Int>
    ) -> some View {
        TextField(hx(placeholderKey), text: text, axis: .vertical)
            .font(.body)
            .foregroundStyle(Color.hx(.textPrimary))
            .tint(Color.hx(.brand))
            .lineLimit(lines)
            #if canImport(UIKit)
            .textInputAutocapitalization(.sentences)
            #endif
            .padding(.horizontal, 13)
            .padding(.vertical, 12)
            .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .strokeBorder(Color.hx(.separator), lineWidth: 1)
            )
    }

    // MARK: - Footer

    /// The web's own three actions: 上一步 disabled on step 1, 下一步 on steps 1–4, and the submit on step 5
    /// where it is 完成 for a create and 保存 for an edit (`CreateForm.tsx:338`-`:343`,
    /// `UpdateForm.tsx:440`-`:445`).
    private var footer: some View {
        HStack(spacing: 10) {
            Button {
                dropTransientNotices()
                vm.back()
            } label: {
                HXText("agent.wizard.previous")
            }
            .buttonStyle(.hxSecondary)
            .disabled(vm.step == .basic)
            if vm.step.isSkippable, !vm.step.isLast {
                // 跳过 never validates, so whatever this step refused stays refused until the save meets it
                // again — the chip keeps reading as refused.
                Button {
                    dropTransientNotices()
                    vm.skip()
                } label: {
                    HXText("agent.wizard.skip")
                }
                .buttonStyle(.hxSecondary)
            }
            if vm.step.isLast {
                Button {
                    submit()
                } label: {
                    HXText(vm.isSaving ? "agent.wizard.saving" : submitKey)
                }
                .buttonStyle(.hxPrimary)
                .disabled(vm.isSaving)
            } else {
                Button {
                    advance()
                } label: {
                    HXText("agent.wizard.next")
                }
                .buttonStyle(.hxPrimary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 14)
        .background(Color.hx(.background))
        .overlay(alignment: .top) {
            Color.hx(.separator).frame(height: 1)
        }
    }

    private var submitKey: String { vm.isCreate ? "agent.wizard.finish" : "common.save" }

    // MARK: - Walk

    /// A chip is a jump, not a re-validation: the step being left keeps whatever it refused, and the step
    /// being entered shows it. Only the notices that were about the tap that produced them go with the walk.
    private func move(to step: AgentWizardStep) {
        dropTransientNotices()
        vm.step = step
    }

    /// The two notices that describe one tap rather than one step: the server's sentence about a write that
    /// failed, and a save refused because another was still running.
    private func dropTransientNotices() {
        failure = nil
        saveBlocked = false
    }

    /// `next()` validates and records what it found under the step each issue names; the refusal reaching the
    /// screen is the model's own state now, so this has nothing left to carry.
    private func advance() {
        dropTransientNotices()
        vm.next()
    }

    private func submit() {
        dropTransientNotices()
        Task {
            switch await vm.save() {
            case .created:
                finish()
            case let .updated(id, name):
                // Only an update offers the panel: a create has no session that could hold a stale snapshot.
                refreshTarget = vm.makeRefreshTarget(id: id, name: name)
                pendingDismiss = true
                showingRefresh = true
            case let .invalid(found, _):
                // `save()` has already recorded `found` under each step it belongs to and jumped to the first
                // one, so the banner the operator lands on is the one that explains the jump. An empty
                // refusal is the re-entrant guard — a tap while a save is still running — and it has to be
                // said out loud rather than render as nothing.
                saveBlocked = found.isEmpty
            case let .failed(message):
                failure = message
            }
        }
    }

    private func finish() {
        onSaved()
        dismiss()
    }

    /// The panel closes itself once every row has an outcome (`HXSessionRefreshSheet` watches `isFinished`), and
    /// that is when a dismissal an update parked goes back on.
    private func finishOncePanelGone() {
        guard pendingDismiss else { return }
        pendingDismiss = false
        finish()
    }

    // MARK: - Pickers

    /// One sheet for the six entity controls. Each keeps the exclusion set its web dropdown applies — every
    /// other row's choice is dropped, the row's own stays listed
    /// (`ToolConfigPanel.tsx:37`-`:41`, `McpConfigPanel.tsx:110`-`:112`, `SkillConfigPanel.tsx:71`-`:74`,
    /// `CliConfigPanel.tsx:93`-`:95`).
    @ViewBuilder
    private func pickerSheet(_ pick: Pick) -> some View {
        switch pick {
        case .model:
            HXEntityPicker(
                titleKey: "agent.wizard.model",
                searchKey: "agent.wizard.model.search",
                emptyKey: "agent.wizard.model.none",
                options: vm.modelOptions,
                selectedID: vm.modelID,
                onPick: vm.pickModel
            )
        case let .tool(rowID):
            HXEntityPicker(
                titleKey: "agent.wizard.tool.select",
                searchKey: "agent.wizard.tool.search",
                emptyKey: "agent.wizard.tool.none",
                options: vm.toolOptions(excluding: vm.excludedToolIDs(except: rowID)),
                selectedID: row(rowID)?.toolID,
                onPick: { vm.pickTool($0, into: rowID) }
            )
        case let .mcp(rowID):
            HXEntityPicker(
                titleKey: "agent.wizard.mcp.select",
                searchKey: "agent.wizard.mcp.search",
                emptyKey: "agent.wizard.mcp.none",
                options: vm.mcpOptions(excluding: vm.excludedMCPIDs(except: rowID)),
                selectedID: mcpRow(rowID)?.mcpID,
                onPick: { vm.pickMCP($0, into: rowID) }
            )
        case let .repository(rowID):
            HXEntityPicker(
                titleKey: "agent.wizard.repository.select",
                searchKey: "agent.wizard.repository.search",
                emptyKey: "agent.wizard.repository.empty",
                options: vm.repositoryOptions,
                selectedID: skillRow(rowID)?.repositoryID,
                // Choosing a repository reads that repository's skill page, so the pick is a task rather than an
                // assignment (`SkillConfigPanel.tsx:116`-`:130`).
                onPick: { option in Task { await vm.pickRepository(option, into: rowID) } }
            )
        case let .skill(rowID):
            HXEntityPicker(
                titleKey: "agent.wizard.skill.select",
                searchKey: "agent.wizard.skill.search",
                emptyKey: "agent.wizard.skill.none",
                options: vm.skillOptions(
                    in: skillRow(rowID)?.repositoryID,
                    excluding: vm.excludedSkillIDs(except: rowID)
                ),
                selectedID: skillRow(rowID)?.skillID,
                onPick: { vm.pickSkill($0, into: rowID) }
            )
        case let .cli(index):
            HXEntityPicker(
                titleKey: "agent.wizard.cli.select",
                searchKey: "agent.wizard.cli.search",
                emptyKey: "agent.wizard.cli.none",
                options: vm.cliOptions(excluding: takenByOtherCards(index)),
                selectedID: vm.selectedCLIIDs.indices.contains(index) ? vm.selectedCLIIDs[index] : nil,
                onPick: { vm.pickCLI($0, at: index) }
            )
        }
    }

    /// Every card's package except this one's. A card may keep its own choice listed — re-picking it is a no-op,
    /// not a duplicate (`CliConfigPanel.tsx:93`-`:98`).
    private func takenByOtherCards(_ index: Int) -> Set<Int64> {
        var taken = vm.excludedCLIIDs
        guard vm.selectedCLIIDs.indices.contains(index) else { return taken }
        taken.remove(vm.selectedCLIIDs[index])
        return taken
    }

    private func row(_ id: UUID) -> AgentToolRow? {
        vm.toolRows.first { $0.id == id }
    }

    private func mcpRow(_ id: UUID) -> AgentMcpRow? {
        vm.mcpRows.first { $0.id == id }
    }

    private func skillRow(_ id: UUID) -> AgentSkillRow? {
        vm.skillRows.first { $0.id == id }
    }
}

/// Which control is asking for an entity. The row/card identity rides with it, because the view model's edits
/// are addressed by it.
private enum Pick: Identifiable {
    case model
    case tool(UUID)
    case mcp(UUID)
    case repository(UUID)
    case skill(UUID)
    case cli(Int)

    var id: String {
        switch self {
        case .model: return "model"
        case let .tool(rowID): return "tool:\(rowID.uuidString)"
        case let .mcp(rowID): return "mcp:\(rowID.uuidString)"
        case let .repository(rowID): return "repository:\(rowID.uuidString)"
        case let .skill(rowID): return "skill:\(rowID.uuidString)"
        case let .cli(index): return "cli:\(index)"
        }
    }
}
