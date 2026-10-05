import SwiftUI
import HarnaxCore
import HarnaxKit

/// One draft, whole: content, files, scripts, both scans, provenance, and the trail it took to get here.
///
/// The six panes are a segmented bar rather than a literal `TabView`, for the reason every pushed screen in
/// this app has: the screen already sits inside the root tab bar's `NavigationStack`, and a second tab strip
/// under the first is unreadable (`HarnaxFeatures/Skills/SkillDetailView.swift` sets the same precedent for its
/// own body/files split). The split itself is the console's (`draftDetail.tsx:275-333`) and the six names are §5.3.
///
/// What is *not* merged anywhere is the two finding lists. `localFindings` is harnax's own scan over the
/// stored bytes and non-empty means an approval stores the skill **disabled**; `scanFindings` is what the
/// sandbox reported when the proposal arrived and is display only (§5.3, `draftDetail.tsx:607-673`). One list
/// under one heading would tell the reviewer the sandbox can veto a publish, which is false.
public struct SkillDraftDetailView: View {
    @StateObject private var vm: SkillDraftDetailViewModel
    private let skills: (any SkillCataloging)?

    public init(id: Int64, drafts: any SkillDraftCataloging, skills: (any SkillCataloging)? = nil) {
        self.skills = skills
        _vm = StateObject(wrappedValue: SkillDraftDetailViewModel(id: id, drafts: drafts, skills: skills))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("skill.draft.detail.title")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await vm.load() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(hx("skill.draft.reload"))
                }
            }
            .task {
                if vm.phase == .loading { await vm.load() }
            }
            .refreshable { await vm.load() }
            .confirmationDialog(
                Text(verbatim: hx("skill.draft.approve.confirm.title")),
                isPresented: $vm.isConfirmingApprove,
                titleVisibility: .visible
            ) {
                Button(role: .destructive) {
                    Task { await vm.confirmApprove() }
                } label: {
                    HXText("skill.draft.approve")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: {
                Text(verbatim: hx("skill.draft.approve.confirm.body"))
            }
            .sheet(isPresented: $vm.isRejectOpen, onDismiss: { vm.rejectReason = "" }) {
                rejectionSheet
            }
            .sheet(isPresented: conflictBinding) {
                conflictSheet
            }
            .navigationDestination(for: SkillTableView.SkillDetailRoute.self) { route in
                if let skills {
                    SkillDetailView(id: route.id, skills: skills)
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.load() } })
        case .ready:
            if let draft = vm.draft {
                screen(draft)
            } else {
                HXStateView(.loading)
            }
        }
    }

    private func screen(_ draft: SkillDraftDetail) -> some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                summary(draft)
                if let notice = vm.notice {
                    SkillDraftNoticeView(notice: notice, openRoute: openRoute)
                }
                decisions(draft)
                panes
                paneContent(draft)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
    }

    // MARK: - Summary

    /// name / proposed / description / last patched, the state, and — while it is still pending — the twelve
    /// characters this screen will send back on approval (`draftDetail.tsx:399`, §5.3).
    private func summary(_ draft: SkillDraftDetail) -> some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 10) {
                    Text(verbatim: draft.title ?? hx("skill.table.unnamed"))
                        .font(.headline)
                        .foregroundStyle(Color.hx(.textPrimary))
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    SkillDraftStatusBadge(status: draft.status)
                }
                Text(verbatim: draft.detail ?? hx("skill.draft.description.empty"))
                    .font(.footnote)
                    .foregroundStyle(draft.detail == nil ? Color.hx(.textTertiary) : Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
                Text(verbatim: stamps(draft))
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
                if draft.isPending, let digest = vm.expectedDigest {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: hx("skill.draft.digest.label"))
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textSecondary))
                        SkillDraftHash(
                            label: SkillDraftRules.truncated(digest, digits: 12),
                            full: digest
                        )
                        Text(verbatim: hx("skill.draft.digest.hint"))
                            .font(.caption2)
                            .foregroundStyle(Color.hx(.textTertiary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                decidedLine
            }
        }
    }

    /// The decided draft's report, with the stored reason as its second line only for a rejection
    /// (`draftDetail.tsx:390-415`).
    @ViewBuilder
    private var decidedLine: some View {
        if let decided = vm.decidedSummary {
            VStack(alignment: .leading, spacing: 6) {
                HXBanner(
                    decided.statusKey ?? "skill.draft.outcome.reviewed.title",
                    message: hx("skill.draft.decided.hint", decided.by, decided.at),
                    systemImage: "checkmark.seal",
                    tone: SkillDraftCopy.statusTone(vm.draft?.status)
                )
                if let reason = decided.reason {
                    HXRow(
                        text: hx("skill.draft.rejectReason"),
                        subtitle: reason,
                        divider: false
                    )
                }
            }
        }
    }

    private func stamps(_ draft: SkillDraftDetail) -> String {
        var pieces: [String] = []
        if let created = hxPresented(draft.createTime) {
            pieces.append(hx("skill.draft.column.proposed") + " " + SkillDraftCopy.stamp(created))
        }
        if let updated = hxPresented(draft.updateTime) {
            pieces.append(hx("skill.draft.column.patched") + " " + SkillDraftCopy.stamp(updated))
        }
        return pieces.isEmpty ? SkillDraftCopy.dash : pieces.joined(separator: " · ")
    }

    // MARK: - Decisions

    /// Both buttons appear on a pending draft and on nothing else (§5.3, `draftDetail.tsx:353-363`).
    @ViewBuilder
    private func decisions(_ draft: SkillDraftDetail) -> some View {
        if draft.isPending {
            VStack(spacing: 10) {
                if vm.isActing {
                    HXBanner(
                        "skill.draft.decision.working",
                        systemImage: "hourglass",
                        tone: .textSecondary
                    )
                }
                Button {
                    vm.approve()
                } label: {
                    HXText("skill.draft.approve")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.hxPrimary)
                .disabled(vm.isActing)
                Button {
                    vm.startRejection()
                } label: {
                    HXText("skill.draft.reject")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.hxDestructive)
                .disabled(vm.isActing)
            }
        }
    }

    // MARK: - Panes

    private var panes: some View {
        HXSegmented(
            SkillDraftTab.allCases.map { HXSegmentOption(id: $0.rawValue, $0.titleKey) },
            selection: Binding(
                get: { vm.tab.rawValue },
                set: { vm.tab = SkillDraftTab(rawValue: $0) ?? .body }
            )
        )
    }

    @ViewBuilder
    private func paneContent(_ draft: SkillDraftDetail) -> some View {
        switch vm.tab {
        case .body: bodyPane(draft)
        case .files: filesPane(draft)
        case .scripts: scriptsPane(draft)
        case .scans: scansPane(draft)
        case .source: sourcePane(draft)
        case .history: historyPane(draft)
        }
    }

    private func bodyPane(_ draft: SkillDraftDetail) -> some View {
        Group {
            if hxPresented(draft.skillmd) == nil {
                HXStateView(.empty, message: hx("skill.draft.body.empty"))
            } else {
                HXCard {
                    HXMarkdownText(draft.skillmd)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    /// `resources` is a real object map on a draft (`SkillDraftResponse.kt:87-88`), unlike the JSON *string* a
    /// published skill row carries (`SkillItem.swift:26-28`). Sorted by path, because map order is not a
    /// contract and a tree that reshuffles between reads is worse than an alphabetical one.
    private func filesPane(_ draft: SkillDraftDetail) -> some View {
        Group {
            if draft.resourceFiles.isEmpty {
                HXStateView(.empty, message: hx("skill.draft.files.empty"))
            } else {
                ForEach(Array(draft.resourceFiles.enumerated()), id: \.offset) { _, file in
                    HXCard {
                        VStack(alignment: .leading, spacing: 8) {
                            HXValueText(file.path)
                            Text(verbatim: file.content)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(Color.hx(.textSecondary))
                                .lineLimit(12)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
        }
    }

    /// These numbers are harnax's own, computed over the stored bytes at read time
    /// (`SkillDraftCodec.kt:51-56,108-109`) — the sentence matters because a reviewer who reads them as the
    /// sandbox's verdict will weigh them wrongly (`draftDetail.tsx:559-605`).
    private func scriptsPane(_ draft: SkillDraftDetail) -> some View {
        Group {
            if draft.scripts.isEmpty {
                HXStateView(.empty, message: hx("skill.draft.scripts.empty"))
            } else {
                Text(verbatim: hx("skill.draft.scripts.hint"))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(draft.scripts) { script in
                    scriptCard(script)
                }
            }
        }
    }

    private func scriptCard(_ script: SkillDraftScript) -> some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HXValueText(script.relPath)
                HStack(spacing: 6) {
                    HXChip(hx("skill.draft.scripts.lines", script.totalLines))
                }
                SkillDraftHash(
                    label: SkillDraftRules.truncated(script.sha256, digits: 16),
                    full: script.sha256
                )
                DisclosureGroup {
                    Text(verbatim: script.headPreview)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(Color.hx(.textSecondary))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 6)
                } label: {
                    Text(verbatim: hx("skill.draft.scripts.preview"))
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textPrimary))
                }
                .tint(Color.hx(.brand))
            }
        }
    }

    /// The two scan lists, in two cards, with the two different consequences spelled out (§5.3).
    private func scansPane(_ draft: SkillDraftDetail) -> some View {
        VStack(spacing: 12) {
            HXCard {
                VStack(alignment: .leading, spacing: 8) {
                    HXSectionHeader("skill.draft.scan.localTitle")
                    HXBanner(
                        draft.hasLocalFindings
                            ? "skill.draft.scan.decides"
                            : "skill.draft.scan.noLocal",
                        systemImage: draft.hasLocalFindings ? "exclamationmark.triangle" : "checkmark.circle",
                        tone: draft.hasLocalFindings ? .danger : .success
                    )
                    ForEach(Array(draft.localFindings.enumerated()), id: \.offset) { _, finding in
                        findingRow(finding, tone: .danger)
                    }
                }
            }
            HXCard {
                VStack(alignment: .leading, spacing: 8) {
                    HXSectionHeader("skill.draft.scan.upstreamTitle")
                    HStack(spacing: 6) {
                        if let verdict = hxPresented(draft.scanVerdict) {
                            HXChip(verdict, tone: SkillDraftCopy.verdictTone(draft.scanVerdict))
                        }
                        Text(verbatim: hx("skill.draft.scan.upstreamHint"))
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if draft.scanFindings.isEmpty {
                        HXBanner(
                            "skill.draft.scan.noUpstream",
                            systemImage: "checkmark.circle",
                            tone: .success
                        )
                    } else {
                        ForEach(Array(draft.scanFindings.enumerated()), id: \.offset) { _, finding in
                            findingRow(finding, tone: .warning)
                        }
                    }
                }
            }
        }
    }

    private func findingRow(_ finding: String, tone: PaletteSlot) -> some View {
        HXRow(text: finding, systemImage: "circle.fill", tone: tone, divider: false)
            .padding(.leading, 2)
    }

    /// Provenance is context, not a condition: nothing on this screen gates a decision on the session id or
    /// the agent, because the queue has no per-session filter to gate on (§1, `SkillDraftController.kt:55-68`).
    private func sourcePane(_ draft: SkillDraftDetail) -> some View {
        VStack(spacing: 12) {
            HXGroupCard {
                HXRow(
                    text: hx("skill.draft.source.session"),
                    subtitle: nil,
                    systemImage: "bubble.left",
                    trailing: {
                        if let session = hxPresented(draft.sourceSessionId) {
                            HXValueText(session)
                        } else {
                            Text(verbatim: SkillDraftCopy.dash)
                                .foregroundStyle(Color.hx(.textTertiary))
                        }
                    }
                )
                HXRow(
                    text: hx("skill.draft.source.agent"),
                    systemImage: "cpu",
                    trailing: {
                        if let agentId = draft.agentId {
                            Text(verbatim: "#\(agentId)")
                                .font(.footnote)
                                .foregroundStyle(Color.hx(.textSecondary))
                        } else {
                            Text(verbatim: hx("skill.draft.source.noAgent"))
                                .font(.footnote)
                                .foregroundStyle(Color.hx(.textTertiary))
                        }
                    }
                )
                HXRow(
                    text: hx("skill.draft.digest.label"),
                    systemImage: "number",
                    divider: false,
                    trailing: {
                        if let digest = vm.expectedDigest {
                            SkillDraftHash(
                                label: SkillDraftRules.truncated(digest, digits: 16),
                                full: digest
                            )
                        } else {
                            Text(verbatim: SkillDraftCopy.dash)
                                .foregroundStyle(Color.hx(.textTertiary))
                        }
                    }
                )
            }
            Text(verbatim: hx("skill.draft.source.hint"))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func historyPane(_ draft: SkillDraftDetail) -> some View {
        Group {
            if draft.history.isEmpty {
                HXStateView(.empty, message: hx("skill.draft.history.empty"))
            } else {
                HXGroupCard {
                    ForEach(Array(draft.history.enumerated()), id: \.offset) { index, item in
                        HXRow(
                            text: hx("skill.draft.history.action") + " " + item.action,
                            subtitle: subtitle(of: item),
                            systemImage: symbol(for: item.action),
                            tone: tone(for: item.action),
                            divider: index < draft.history.count - 1
                        )
                    }
                }
            }
        }
    }

    private func subtitle(of item: SkillDraftHistoryItem) -> String {
        var pieces: [String] = [
            hx("skill.draft.history.actor") + " " + item.actor,
            hx("skill.draft.history.at") + " " + SkillDraftCopy.stamp(item.createTime),
        ]
        if let detail = hxPresented(item.detail) {
            pieces.append(hx("skill.draft.history.detail") + " " + detail)
        }
        return pieces.joined(separator: " · ")
    }

    private func symbol(for action: String) -> String {
        switch action {
        case "APPROVE": "checkmark.seal"
        case "REJECT": "xmark.seal"
        default: "paperplane"
        }
    }

    private func tone(for action: String) -> PaletteSlot {
        switch action {
        case "APPROVE": .success
        case "REJECT": .danger
        default: .brand
        }
    }

    // MARK: - Rejection

    private var rejectionSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                if let refusal = vm.rejectRefusal {
                    HXBanner("state.error.title", message: refusal, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                Text(verbatim: hx("skill.draft.reject.hint"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
                TextField(
                    hx("skill.draft.reject.placeholder"),
                    text: $vm.rejectReason,
                    axis: .vertical
                )
                .font(.body)
                .foregroundStyle(Color.hx(.textPrimary))
                .tint(Color.hx(.brand))
                .lineLimit(4...10)
                .padding(12)
                .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .strokeBorder(Color.hx(.separator), lineWidth: 1)
                )
                Spacer(minLength: 0)
            }
            .padding(16)
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("skill.draft.reject.title")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { vm.closeRejection() } label: { HXText("common.cancel") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await vm.submitRejection() }
                    } label: {
                        HXText("skill.draft.reject")
                    }
                    .disabled(vm.isActing || hxPresented(vm.rejectReason) == nil)
                }
            }
        }
    }

    // MARK: - Conflict

    /// A swipe-down dismissal counts as the cancel button: §5.5's「关框 + 清表单 + 重读」is about the digest,
    /// and it would be wrong to re-present a stale one just because the reviewer dragged the sheet away.
    private var conflictBinding: Binding<Bool> {
        Binding(
            get: { vm.conflict != nil },
            set: { open in
                if !open, vm.conflict != nil {
                    Task { await vm.cancelConflict() }
                }
            }
        )
    }

    private var conflictSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                if let conflict = vm.conflict {
                    Text(verbatim: hx("skill.draft.conflict.hint", conflict.name))
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                    if let refusal = vm.conflictRefusal {
                        HXBanner("state.error.title", message: refusal, systemImage: "exclamationmark.triangle", tone: .danger)
                    }
                    HXGroupCard {
                        resolutionRow(.rename, divider: true)
                        resolutionRow(.replace, divider: false)
                    }
                    if vm.resolution == .rename {
                        TextField(
                            vm.draft?.title ?? "",
                            text: $vm.newName
                        )
                        .font(.body)
                        .foregroundStyle(Color.hx(.textPrimary))
                        .tint(Color.hx(.brand))
                        .padding(.horizontal, 13)
                        .padding(.vertical, 13)
                        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 13, style: .continuous)
                                .strokeBorder(Color.hx(.separator), lineWidth: 1)
                        )
                    }
                    if let skillID = conflict.skillID, skills != nil {
                        NavigationLink(value: SkillTableView.SkillDetailRoute(id: skillID)) {
                            HXText("skill.draft.conflict.openExisting")
                        }
                        .buttonStyle(.hxInline)
                    }
                    Text(verbatim: hx("skill.draft.conflict.choose"))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("skill.draft.conflict.title")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        Task { await vm.cancelConflict() }
                    } label: {
                        HXText("common.cancel")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await vm.resolveConflict() }
                    } label: {
                        HXText("skill.draft.conflict.submit")
                    }
                    .disabled(vm.isActing)
                }
            }
            .navigationDestination(for: SkillTableView.SkillDetailRoute.self) { route in
                if let skills {
                    SkillDetailView(id: route.id, skills: skills)
                }
            }
        }
    }

    /// The two answers, **rename first** — the order is the console's and it is not cosmetic
    /// (`draftDetail.tsx:511-520`).
    private func resolutionRow(_ option: SkillDraftResolution, divider: Bool) -> some View {
        Button {
            vm.choose(option)
        } label: {
            HXRow(
                text: hx(option.titleKey),
                systemImage: vm.resolution == option ? "largecircle.fill.circle" : "circle",
                tone: vm.resolution == option ? .brand : .textTertiary,
                divider: divider
            )
        }
        .buttonStyle(.plain)
    }

    /// The promoted outcome's offer, as a route. `nil` unless the host carries a skill facade, which is what
    /// makes the sentence lose its link instead of growing a dead control (§5.4 末行).
    private var openRoute: SkillTableView.SkillDetailRoute? {
        guard let id = vm.openableSkillID else { return nil }
        return SkillTableView.SkillDetailRoute(id: id)
    }
}
