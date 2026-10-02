import SwiftUI
import HarnaxCore
import HarnaxKit

/// C4 — the three-step team wizard, in both modes.
///
/// One view for create and edit because the console serves both from one component and the two differ only by
/// their seed, their submit call and the label of the last button
/// (`harnax-webui/src/pages/team/components/TeamWizard.tsx:17`-`:37`). Everything that decides — which step may
/// close, which candidates a row may offer, what goes on the wire — lives in `TeamFormViewModel`; this type only
/// lays it out.
///
/// Three shapes change from the console rather than being copied: the three dropdowns become one searchable
/// sheet per control (`HXEntityPicker`, `specs/01-agent-team.md` 注意点 4); the member dropdown's remote search
/// becomes a keyword field on step 3, because the sheet only filters the list it is handed and the search has to
/// reach past the seed page (注意点 5); and the web's non-clickable `Steps` become chips that may jump anywhere.
///
/// What the agent wizard has and this one deliberately does not: any tool, MCP or CLI step, and any
/// environment-parameter table. A team's lead carries skills and nothing else, and there is no env column for a
/// team binding anywhere on the wire (`TeamCreateRequest.kt:10`-`:35`).
public struct TeamFormView: View {
    @StateObject private var vm: TeamFormViewModel
    /// The panel an update offers posts through; the wizard owns no refresh endpoint of its own.
    private let refresher: any SessionRefreshing
    private let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss

    /// Which control is asking for an entity. One sheet for all four pickers, because four separate flags could
    /// leave two of them open at once.
    @State private var picking: Pick?
    /// What each step last refused. The agent wizard keeps this in its model; this one keeps it here, because
    /// `TeamFormViewModel` hands back what a pass found and remembers nothing, and the sentence that explains a
    /// save's jump must survive the chip tap that follows it.
    @State private var outstanding: [TeamWizardIssue] = []
    /// The server's own sentence, shown verbatim (`TeamWizardResult.failed`).
    @State private var failure: String?
    /// The re-entrant save refusal: a tap that landed while a save is already running. It carries no issue, and
    /// rendering nothing for it would leave the button looking dead.
    @State private var saveBlocked = false
    /// The member step's remote keyword, and the debounce that goes with it: the model owns the request shape
    /// and the fallback to the seed page, the view owns when it fires
    /// (`TeamFormViewModel.searchDebounceMilliseconds`, `MembersField.tsx:41`-`:50`).
    @State private var memberKeyword = ""
    /// Set by an update: the wizard stays on screen while the panel is up and closes once it goes.
    @State private var refreshTarget: SessionRefreshTarget?
    @State private var showingRefresh = false
    @State private var pendingDismiss = false

    /// - Parameter vm: the wizard's own view model, in either mode. It seeds an edit off the list row, so the
    ///   caller hands in the row it was standing on (`TeamFormViewModel.Mode.edit`).
    public init(
        vm: TeamFormViewModel,
        sessionRefresher: any SessionRefreshing,
        onSaved: @escaping () -> Void = {}
    ) {
        _vm = StateObject(wrappedValue: vm)
        self.refresher = sessionRefresher
        self.onSaved = onSaved
    }

    /// Assembly from the app's dependency bag, so the host that opens the wizard does not have to reach past the
    /// bag for the four catalogs it reads and the refresh panel writes.
    public init(
        dependencies: HarnaxDependencies,
        mode: TeamFormViewModel.Mode,
        account: AccountSnapshot?,
        onSaved: @escaping () -> Void = {}
    ) {
        self.init(
            vm: TeamFormViewModel(dependencies: dependencies, mode: mode, account: account),
            sessionRefresher: dependencies.sessionRefresher,
            onSaved: onSaved
        )
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    stepChips
                    subtitle
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

    /// The three web `Steps` as a chip row (`TeamWizard.tsx:261`-`:269`), clickable because on a handset the way
    /// back to the lead model should not be three taps.
    private var stepChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(TeamWizardStep.allCases) { step in
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

    /// Current is brand, a step with something outstanding is refused, and only a step behind the current one
    /// that nothing was found in reads as finished.
    private func chipTone(for step: TeamWizardStep) -> PaletteSlot? {
        if step == vm.step { return .brand }
        if outstanding.contains(where: { $0.step == step }) { return .danger }
        return step.rawValue < vm.step.rawValue ? .success : nil
    }

    /// The web's own modal subtitle: a create explains the walk, an edit warns that running team sessions keep
    /// the previous members until they are refreshed (`TeamWizard.tsx:252`-`:257`).
    private var subtitle: some View {
        Text(verbatim: hx(vm.subtitleKey))
            .font(.footnote)
            .foregroundStyle(Color.hx(.textSecondary))
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Notices

    /// The things that can be wrong on a step, in the order an operator reads them: what this step refused, what
    /// a save that was already running turned into, what the server said, and what a failed candidate list left
    /// behind.
    @ViewBuilder
    private var notices: some View {
        if !currentIssues.isEmpty {
            HXBanner(
                "state.error.title",
                message: issueText,
                systemImage: "exclamationmark.triangle",
                tone: .danger
            )
        }
        if saveBlocked {
            HXBanner("team.wizard.save.inFlight", systemImage: "hourglass", tone: .warning)
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

    /// The step on screen shows the refusals that belong to it, whenever they were found: `next()` holding the
    /// step closed, or `save()` finding them from the last step and walking back. They stay until that step is
    /// validated again.
    private var currentIssues: [TeamWizardIssue] {
        outstanding.filter { $0.step == vm.step }
    }

    /// One line per distinct sentence: three blank member rows are three refusals of the same rule, and the
    /// banner would otherwise read as a list of copies.
    private var issueText: String {
        var seen = Set<String>()
        return currentIssues.map(\.message).filter { seen.insert($0).inserted }.joined(separator: "\n")
    }

    // MARK: - Steps

    @ViewBuilder
    private var stepContent: some View {
        switch vm.step {
        case .basic: basicStep
        case .skill: skillStep
        case .member: memberStep
        }
    }

    // MARK: - Step 1

    /// Name, description, the lead's own prompt, model, visibility (`TeamWizard.tsx:341`-`:401`). There is no
    /// owner line: `createTeam` stamps the calling account itself and the DTO has no such field
    /// (`TeamServiceImpl.kt:88`).
    ///
    /// Every field carries a label, the way the web's `Form.Item` does. `HXField` renders only a placeholder, so
    /// with the placeholders alone the two multiline boxes below are indistinguishable from each other the moment
    /// the operator types — a description and a system prompt are both prose.
    private var basicStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            HXGroupCard {
                labeled("team.wizard.name") {
                    HXField("team.wizard.name.placeholder", text: $vm.name, systemImage: "textformat")
                }
                labeled("team.wizard.description") {
                    multilineField("team.wizard.description.placeholder", text: detailBinding, lines: 1...4)
                }
                labeled("team.wizard.prompt") {
                    multilineField("team.wizard.prompt.placeholder", text: $vm.systemPrompt, lines: 1...9)
                }
                .padding(.bottom, 14)
            }
            modelRow
            visibilityRow
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
    /// (`TeamWizard.tsx:51`-`:53`); `TeamFormViewModel.modelName` renders it and the row only shows the result.
    ///
    /// Two things the web's `Select` gives this field and a row cannot: `allowClear` (`UpdateForm`'s equivalent
    /// at the team's own edit leg) and a degraded look for a reference that no longer resolves
    /// (`specs/01-agent-team.md` 注意点 12). The clear sits on the row that opens the picker rather than inside
    /// `HXEntityPicker` itself — the sheet has no clear of its own.
    private var modelRow: some View {
        HXGroupCard {
            Button {
                picking = .model
            } label: {
                HXRow(
                    "team.wizard.model",
                    subtitle: nil,
                    systemImage: "cpu",
                    divider: false
                ) {
                    HStack(spacing: 6) {
                        Text(verbatim: vm.modelName)
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

    /// A stale reference reads degraded, not chosen: `modelName` carries the web's 「该模型已停用、已删除或不再是
    /// 对话模型」 suffix and the warning tone is what tells the eye before the sentence is read. The row still
    /// names the model, because the candidate page may not hold it any more.
    private var modelValueColor: Color {
        if vm.modelID == nil { return Color.hx(.textTertiary) }
        return vm.isStoredModelUnavailable ? Color.hx(.warning) : Color.hx(.textPrimary)
    }

    /// `canChangeVisibility` is the team's own answer, and it is always yes: a team's 是否公开 has no permission
    /// rule in front of it at all, which is the one place this form and the agent form disagree
    /// (`TeamWizard.tsx:388`-`:401`, where the `Switch` carries no `disabled`; `specs/01-agent-team.md:141`).
    private var visibilityRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HXGroupCard {
                HXRow(text: hx("team.wizard.isPublic"), subtitle: nil, divider: false) {
                    Toggle(isOn: Binding(get: { vm.isPublic }, set: { vm.setIsPublic($0) })) {
                        HXText("team.wizard.isPublic")
                    }
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .tint(Color.hx(.brand))
                    .disabled(!vm.canChangeVisibility)
                }
            }
            Text(verbatim: hx("team.wizard.public.hint"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Step 2

    /// The lead's skills: the same two-picker row the agent wizard's step 4 draws, and the same panel the web
    /// reuses whole (`TeamWizard.tsx:412`-`:418` renders `SkillConfigPanel`).
    private var skillStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("team.wizard.skill.hint", actionKey: "team.wizard.skill.add", action: vm.addSkillRow)
            if vm.excludedSkillIDs.isEmpty {
                emptyLine("team.wizard.skill.empty")
            }
            ForEach(vm.skillRows) { row in
                skillCard(row)
            }
        }
    }

    private func skillCard(_ row: TeamSkillRow) -> some View {
        HXGroupCard {
            Button {
                picking = .repository(row.id)
            } label: {
                HXRow(
                    text: row.repositoryName ?? hx("team.wizard.repository.select"),
                    subtitle: nil,
                    systemImage: "square.stack",
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
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
                    text: skillLabel(for: row),
                    subtitle: row.repositoryName.map { hx("team.wizard.skill.repository", $0) },
                    systemImage: "puzzlepiece.extension",
                    tone: vm.isSkillUnavailable(for: row.id) ? .warning : .brand,
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            // The skill dropdown has nothing to list until a repository is chosen
            // (`SkillConfigPanel.tsx:129`), and choosing one is what reads that repository's page.
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
                // No `HXOrderHandles` on this step, unlike the four agent lists and the member list: the
                // skill field's no-send rule compares the set (`TeamFormViewModel.skillIDsIfChanged`, 注意点 11),
                // so a pure rearrangement would be judged unchanged and undone by the next read. Making it stick
                // would mean sending a stored-but-broken skill into the resolver that refuses it
                // (`SkillBindingResolver.kt:43`-`:100`); that product call is out of this form's keeping.
                // Always offered, even for the only row left: `removeSkillRow` clears that row in place rather
                // than deleting it, because the add button appends to the tail and a step with no row would
                // leave nowhere to pick the replacement (`SkillConfigPanel.tsx:76`-`:84`).
                trash { vm.removeSkillRow(row.id) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
    }

    /// A stored skill that no longer resolves keeps its name and gets the web's 「已停用或已被删除」 suffix
    /// (`TeamWizard.tsx:107`-`:113`). Dropping the label would leave the operator wondering where a skill went.
    private func skillLabel(for row: TeamSkillRow) -> String {
        guard let name = row.skillName else { return hx("team.wizard.skill.select") }
        guard !vm.isSkillUnavailable(for: row.id) else {
            return "\(name) · \(vm.unavailableSuffix(for: .skill))"
        }
        return name
    }

    // MARK: - Step 3

    /// The members this lead delegates to, in the order it reads them (`MembersField.tsx:192`-`:197`).
    private var memberStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("team.wizard.member.hint", actionKey: "team.wizard.member.add", action: vm.addMemberRow)
            memberSearch
            ForEach(Array(vm.memberRows.enumerated()), id: \.element.id) { index, row in
                memberCard(index, row)
            }
        }
        // The console's 300 ms `setTimeout`, in the shape the spec points at: `.task(id:)` cancels the pending
        // read as the next keystroke lands, so one request goes out after typing stops
        // (`MembersField.tsx:41`-`:50`, `specs/01-agent-team.md` 注意点 5). An emptied keyword costs no request
        // at all — `searchMembers` returns to the seed page on its own.
        .task(id: memberKeyword) {
            try? await Task.sleep(for: .milliseconds(TeamFormViewModel.searchDebounceMilliseconds))
            guard !Task.isCancelled else { return }
            await vm.searchMembers(memberKeyword)
        }
    }

    /// The remote half of the member dropdown. `HXEntityPicker`'s own box filters the list it is handed, so a
    /// search that reaches past the 200-row seed page has to sit outside the sheet; what it narrows is
    /// `memberPool`, which every row's picker then lists.
    private var memberSearch: some View {
        HStack(spacing: 10) {
            HXField("team.wizard.member.search", text: $memberKeyword, systemImage: "magnifyingglass")
            if vm.isSearchingMembers {
                ProgressView()
                    .controlSize(.small)
                    .tint(Color.hx(.brand))
            }
        }
    }

    /// - Parameter index: the card's place in the list, which is what the order rule makes load-bearing — the
    ///   lead reads its delegates in array order, and this is the only handle an operator has when two rows say
    ///   the same thing.
    private func memberCard(_ index: Int, _ row: TeamMemberRow) -> some View {
        HXGroupCard {
            Text(verbatim: hx("team.wizard.member.row", index + 1))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.hx(.textSecondary))
                .padding(.horizontal, 14)
                .padding(.top, 12)
            Button {
                picking = .member(row.id)
            } label: {
                HXRow(
                    text: memberLabel(for: row),
                    subtitle: nil,
                    systemImage: "person.2",
                    tone: vm.isMemberUnavailable(for: row.id) ? .warning : .brand,
                    divider: false
                ) {
                    HXChevron()
                }
            }
            .buttonStyle(.plain)
            .hxOrderActions(index: index, count: vm.memberRows.count, move: vm.moveMemberRow)
            VStack(alignment: .leading, spacing: 6) {
                HXText("team.wizard.member.delegation")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.hx(.textSecondary))
                multilineField(
                    "team.wizard.member.delegation.placeholder",
                    text: delegationBinding(for: row.id),
                    lines: 1...4
                )
            }
            .padding(.horizontal, 14)
            .padding(.top, 4)
            HStack(spacing: 10) {
                Spacer(minLength: 0)
                // The array's order is what the lead reads its delegates in, so a member's place is arranged
                // here rather than left to the order the rows were picked in.
                HXOrderHandles(index: index, count: vm.memberRows.count, move: vm.moveMemberRow)
                // The remove icon only appears while there is more than one row, so a team always has a place
                // to pick into (`MembersField.tsx:170`-`:176`).
                if vm.canRemoveMemberRow {
                    trash { vm.removeMemberRow(row.id) }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
    }

    /// A member whose agent row is gone or switched off keeps its name and gets the suffix — never a bare id,
    /// and never a silently shorter list (`MembersField.tsx:62`-`:81`).
    private func memberLabel(for row: TeamMemberRow) -> String {
        let name = vm.memberName(for: row.id)
        guard !name.isEmpty else { return hx("team.wizard.member.select") }
        guard !vm.isMemberUnavailable(for: row.id) else {
            return "\(name) · \(vm.unavailableSuffix(for: .member))"
        }
        return name
    }

    /// What this team asks of the member, capped where the web's `maxLength` caps it
    /// (`MembersField.tsx:150`-`:157`). The validator's own sentence still refuses an over-long value that came
    /// back from an edit, so the cap is a typing stop rather than the rule.
    private func delegationBinding(for rowID: UUID) -> Binding<String> {
        Binding(
            get: { vm.memberRows.first { $0.id == rowID }?.delegationDescription ?? "" },
            set: { vm.setDelegation(cap($0, to: TeamFormViewModel.delegationLimit), of: rowID) }
        )
    }

    // MARK: - Shared parts

    /// The web's own step header: a secondary sentence on the left, the add button on the right
    /// (`ToolConfigPanel.tsx:92`-`:98`).
    private func sectionHeader(
        _ hintKey: String,
        actionKey: String,
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

    /// `HXField` is a one-line box. Description and prompt are `TEXT` columns the web gives 2 and 6 rows to, so
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

    /// The description's own `maxLength` (`TeamWizard.tsx:329`). Nothing validates it — the console cannot
    /// produce a longer value in the first place — so the field is where the cap lives.
    private var detailBinding: Binding<String> {
        Binding(get: { vm.detail }, set: { vm.detail = cap($0, to: TeamFormViewModel.detailLimit) })
    }

    /// Trimming to a UTF-16 budget, because that is what the backend's `@Size` counts
    /// (`TeamCreateRequest.kt:22`-`:24`, `:57`-`:59`). Graphemes are kept whole: cutting in the middle of one
    /// would put a stray half-mark on the wire.
    private func cap(_ text: String, to limit: Int) -> String {
        var kept = ""
        var units = 0
        for character in text {
            let width = String(character).utf16.count
            guard units + width <= limit else { break }
            units += width
            kept.append(character)
        }
        return kept
    }

    // MARK: - Footer

    /// The web's own two actions, with the 跳过 this screen adds (`TeamWizard.tsx:437`-`:447`): 上一步 disabled on
    /// step 1, 下一步 on steps 1 and 2, and the submit on step 3, where it is 完成 for a create and 保存 for an
    /// edit.
    private var footer: some View {
        HStack(spacing: 10) {
            Button {
                dropTransientNotices()
                vm.back()
            } label: {
                HXText("team.wizard.previous")
            }
            .buttonStyle(.hxSecondary)
            .disabled(vm.step == .basic)
            if vm.step.isSkippable {
                // 跳过 never validates, so whatever this step refused stays refused until the save meets it
                // again — the chip keeps reading as refused.
                Button {
                    dropTransientNotices()
                    vm.skip()
                } label: {
                    HXText("team.wizard.skip")
                }
                .buttonStyle(.hxSecondary)
            }
            if vm.step.isLast {
                Button {
                    submit()
                } label: {
                    HXText(vm.isSaving ? "team.wizard.saving" : submitKey)
                }
                .buttonStyle(.hxPrimary)
                .disabled(vm.isSaving)
            } else {
                Button {
                    advance()
                } label: {
                    HXText("team.wizard.next")
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

    private var submitKey: String { vm.isCreate ? "team.wizard.finish" : "common.save" }

    // MARK: - Walk

    /// A chip is a jump, not a re-validation: the step being left keeps whatever it refused, and the step being
    /// entered shows it. Only the notices that were about the tap that produced them go with the walk.
    private func move(to step: TeamWizardStep) {
        dropTransientNotices()
        vm.step = step
    }

    /// The two notices that describe one tap rather than one step: the server's sentence about a write that
    /// failed, and a save refused because another was still running.
    private func dropTransientNotices() {
        failure = nil
        saveBlocked = false
    }

    /// `next()` validates the step it stands on and hands back what it found, moving on when it found nothing.
    /// Only that step's refusals are replaced, so a save that found something on step 2 does not go quiet the
    /// moment step 1 passes.
    private func advance() {
        dropTransientNotices()
        let validated = vm.step
        record(vm.next(), for: validated)
    }

    private func record(_ found: [TeamWizardIssue], for step: TeamWizardStep) {
        outstanding = outstanding.filter { $0.step != step } + found
    }

    private func submit() {
        dropTransientNotices()
        Task {
            switch await vm.save() {
            case .created:
                finish()
            case let .updated(id, name):
                // Only an update offers the panel: a create has no session that could hold a stale snapshot
                // (`team/index.tsx:394`-`:399`).
                refreshTarget = vm.makeRefreshTarget(id: id, name: name)
                pendingDismiss = true
                showingRefresh = true
            case let .invalid(found, _):
                // `save()` ran every step's rules and jumped to the step that owns the first refusal, so this
                // replaces the whole record rather than one step's slice. An empty refusal is the re-entrant
                // guard — a tap while a save is still running — and it has to be said out loud.
                outstanding = found
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

    /// One sheet for the four entity controls. The two skill pickers keep the web's exclusion — a skill another
    /// row holds is not offered (`SkillConfigPanel.tsx:71`-`:74`) — and the member picker deliberately does not:
    /// `MembersField` leaves every agent selectable and refuses the pair with a row-level validator
    /// (`:114`-`:130`), because a team's members are de-duplicated by a refusal the operator can read rather
    /// than by a silent drop.
    @ViewBuilder
    private func pickerSheet(_ pick: Pick) -> some View {
        switch pick {
        case .model:
            // `modelOptions` is the selectable half of `modelChoices`: the stored model that fell out of the
            // page keeps its row on the screen above, where the suffix is already printed, and a sheet that
            // listed it could only offer a tap that changes nothing.
            HXEntityPicker(
                titleKey: "team.wizard.model",
                searchKey: "team.wizard.model.search",
                emptyKey: "team.wizard.model.empty",
                options: vm.modelOptions,
                selectedID: vm.modelID,
                onPick: vm.pickModel
            )
        case let .repository(rowID):
            HXEntityPicker(
                titleKey: "team.wizard.repository.select",
                searchKey: "team.wizard.repository.search",
                emptyKey: "team.wizard.repository.empty",
                options: vm.repositoryOptions,
                selectedID: skillRow(rowID)?.repositoryID,
                // Choosing a repository reads that repository's skill page, so the pick is a task rather than an
                // assignment (`TeamWizard.tsx:152`-`:164`).
                onPick: { option in Task { await vm.pickRepository(option, into: rowID) } }
            )
        case let .skill(rowID):
            HXEntityPicker(
                titleKey: "team.wizard.skill.select",
                searchKey: "team.wizard.skill.search",
                emptyKey: "team.wizard.skill.none",
                options: vm.skillOptions(
                    in: skillRow(rowID)?.repositoryID,
                    excluding: vm.excludedSkillIDs(except: rowID)
                ),
                selectedID: skillRow(rowID)?.skillID,
                onPick: { vm.pickSkill($0, into: rowID) }
            )
        case let .member(rowID):
            HXEntityPicker(
                titleKey: "team.wizard.member.select",
                searchKey: "team.wizard.member.search",
                emptyKey: "team.wizard.member.none",
                options: vm.memberOptions,
                selectedID: memberRow(rowID)?.agentID,
                onPick: { vm.pickMember($0, into: rowID) }
            )
        }
    }

    private func skillRow(_ id: UUID) -> TeamSkillRow? {
        vm.skillRows.first { $0.id == id }
    }

    private func memberRow(_ id: UUID) -> TeamMemberRow? {
        vm.memberRows.first { $0.id == id }
    }
}

/// Which control is asking for an entity. The row's identity rides with it, because the view model's edits are
/// addressed by it.
private enum Pick: Identifiable {
    case model
    case repository(UUID)
    case skill(UUID)
    case member(UUID)

    var id: String {
        switch self {
        case .model: return "model"
        case let .repository(rowID): return "repository:\(rowID.uuidString)"
        case let .skill(rowID): return "skill:\(rowID.uuidString)"
        case let .member(rowID): return "member:\(rowID.uuidString)"
        }
    }
}
