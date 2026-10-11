import SwiftUI
import HarnaxCore
import HarnaxKit

/// One memory candidate, whole: the text it produced, the text it read, the files it drained and the days it
/// rewrites — then the only two writes this surface has.
///
/// Four cards, not tabs. The console splits the same four parts across a `Tabs` strip
/// (`draftDetail.tsx:596`, tab builders `:380-460`), but on a phone a tab strip under the root tab bar hides three
/// quarters of the decision, and the decision here is exactly the thing that must not be one swipe away from the
/// material it is made of: an approval replaces the owner's whole long-term layer and rewrites K of their own day
/// files in the same write. So the proposal text, the base text, the sources and the targets all stack on one
/// scroll, and sources and targets stay in **separate cards** because they are opposite directions — a source is
/// material taken *out* of a conversation and cleared on approval, a target is material written *into* the
/// owner's own ledger (`MemoryDraftResponse.kt:91-95`, `MemoryDraftSource.kt:40-53`).
///
/// Nothing on this screen is a second attempt field. There is no name and no target to choose
/// (`MemoryDraftApproveRequest.kt:18-25`) — the candidate already says whose layer it rewrites — so the digest the
/// approval sends is the one on screen, and a raced decision is answered by re-reading rather than by retrying
/// with an edited copy.
public struct MemoryDraftDetailView: View {
    @StateObject private var vm: MemoryDraftDetailViewModel
    private let catalog: any MemoryDraftCataloging

    public init(id: Int64, catalog: any MemoryDraftCataloging) {
        self.catalog = catalog
        _vm = StateObject(wrappedValue: MemoryDraftDetailViewModel(id: id, catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("memory.draft.detail.title")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await vm.load() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(hx("memory.draft.reload"))
                }
            }
            .task {
                if vm.phase == .loading { await vm.load() }
            }
            .refreshable { await vm.load() }
            .confirmationDialog(
                Text(verbatim: hx("memory.draft.approve.confirm.title")),
                isPresented: $vm.isConfirmingApprove,
                titleVisibility: .visible
            ) {
                Button(role: .destructive) {
                    Task { await vm.confirmApprove() }
                } label: {
                    HXText("memory.draft.approve")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: {
                Text(verbatim: confirmBody)
            }
            .sheet(isPresented: $vm.isRejectOpen) {
                rejectionSheet
            }
    }

    /// The whole rewrite, named: the confirmation has to say whose layer it replaces, and how many *more* objects
    /// the same click writes (`draftDetail.tsx:306-333`, whose `confirmDaily` line only appears when
    /// `dailyTargets.length > 0` at `:322`).
    private var confirmBody: String {
        var text = hx("memory.draft.approve.confirm.body", vm.draft?.agentName ?? MemoryDraftCopy.dash)
        if vm.dailyTargetCount > 0 {
            text += " " + hx("memory.draft.approve.confirm.daily", vm.dailyTargetCount)
        }
        return text
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

    private func screen(_ draft: MemoryDraftDetail) -> some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                summary(draft)
                if let notice = vm.notice {
                    MemoryDraftNoticeView(notice: notice)
                }
                decidedBanner
                decisions(draft)
                mergedCard
                baseCard
                sourcesCard(draft)
                targetsCard(draft)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    // MARK: - Summary

    /// Whose layer, out of which conversation, at which base version, and — while it is still pending — the
    /// digest this screen will send back (`draftDetail.tsx:521-565`).
    private func summary(_ draft: MemoryDraftDetail) -> some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 10) {
                    Text(verbatim: draft.agentName.isEmpty ? hx("memory.draft.unnamed") : draft.agentName)
                        .font(.headline)
                        .foregroundStyle(Color.hx(.textPrimary))
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    MemoryDraftStatusBadge(status: draft.status)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: hx("memory.draft.column.session"))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textSecondary))
                    MemoryDraftHash(label: draft.sessionId, full: draft.sessionId)
                }
                badges(draft)
                Text(verbatim: stamps(draft))
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
                if draft.isPending, let digest = vm.expectedDigest {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: hx("memory.draft.digest.label"))
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textSecondary))
                        MemoryDraftHash(label: vm.digestPreview ?? digest, full: digest)
                        Text(verbatim: hx("memory.draft.digest.hint"))
                            .font(.caption2)
                            .foregroundStyle(Color.hx(.textTertiary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }

    /// The four numbers a merge is made of, as chips: the version it read against, how much text it produced and
    /// how many files it moves in each direction. `v0` and「no layer yet」are kept apart on purpose
    /// (`drafts.tsx:140-151`) — they are different facts about the same `0`. The patched chip runs
    /// `SkillDraftRules.isPatched`, which is the very rule `MemoryDraftRow.patched` delegates to, so a row and
    /// the candidate it links to can never disagree about whether the merge rewrote the proposal.
    private func badges(_ draft: MemoryDraftDetail) -> some View {
        HStack(spacing: 6) {
            if draft.baseVersion > 0 {
                HXChip("v\(draft.baseVersion)")
            } else {
                HXChip(hx("memory.draft.firstLayer"), tone: .brand)
            }
            HXChip(hx("memory.draft.row.merged", MemoryDraftCopy.charCount(draft.mergedMd)))
            HXChip(hx("memory.draft.row.sources", draft.sources.count))
            HXChip(hx("memory.draft.row.targets", draft.targets.count))
            if SkillDraftRules.isPatched(createTime: draft.createTime, updateTime: draft.updateTime) {
                HXChip(hx("memory.draft.patched"), tone: .indigo)
            }
        }
    }

    private func stamps(_ draft: MemoryDraftDetail) -> String {
        var pieces: [String] = []
        if let created = hxPresented(draft.createTime) {
            pieces.append(hx("memory.draft.column.proposed") + " " + MemoryDraftCopy.stamp(created))
        }
        if let updated = hxPresented(draft.updateTime) {
            pieces.append(hx("memory.draft.column.patched") + " " + MemoryDraftCopy.stamp(updated))
        }
        if let by = hxPresented(draft.reviewedBy) {
            pieces.append(hx("memory.draft.column.decidedBy") + " " + by)
        }
        return pieces.isEmpty ? MemoryDraftCopy.dash : pieces.joined(separator: " · ")
    }

    /// A decided candidate's report. Only a rejection carries its reason, because an approval blanks the column
    /// server-side and a stale value must not read as a refusal (`draftDetail.tsx:573-582`).
    @ViewBuilder
    private var decidedBanner: some View {
        if let decided = vm.decidedSummary {
            VStack(alignment: .leading, spacing: 6) {
                HXBanner(
                    decided.statusKey ?? "memory.draft.outcome.reviewed",
                    message: hx("memory.draft.decided.hint", decided.by, decided.at),
                    systemImage: "checkmark.seal",
                    tone: MemoryDraftCopy.statusTone(vm.draft?.status)
                )
                if let reason = decided.reason {
                    HXRow(text: hx("memory.draft.rejectReason"), subtitle: reason, divider: false)
                }
            }
        }
    }

    // MARK: - Decisions

    /// Both buttons on a pending candidate and on nothing else (`draftDetail.tsx:486-499`). Approval is the
    /// destructive one here: it replaces text every later conversation of this agent is told.
    @ViewBuilder
    private func decisions(_ draft: MemoryDraftDetail) -> some View {
        if draft.isPending {
            VStack(spacing: 10) {
                if vm.isActing {
                    HXBanner("memory.draft.decision.working", systemImage: "hourglass", tone: .textSecondary)
                }
                Button {
                    Task { await vm.approve() }
                } label: {
                    HXText("memory.draft.approve")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.hxPrimary)
                .disabled(vm.isActing)
                Button {
                    vm.startRejection()
                } label: {
                    HXText("memory.draft.reject")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.hxDestructive)
                .disabled(vm.isActing)
            }
        }
    }

    // MARK: - The proposal

    /// The complete `MEMORY.md` the merge produced — the text the approval installs
    /// (`draftDetail.tsx:398-413`, whose heading carries `draft.mergedMd.length`).
    private var mergedCard: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HXSectionHeader("memory.draft.merged.title")
                if let text = vm.mergedMarkdown {
                    Text(verbatim: hx("memory.draft.merged.hint", vm.mergedCharCount))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                        .fixedSize(horizontal: false, vertical: true)
                    HXMarkdownText(text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    HXStateView(.empty, message: hx("memory.draft.merged.empty"))
                }
            }
        }
    }

    /// The owner's text the merge read, at the version it read it at. `baseVersion == 0` means there was no file
    /// at all, which is a different claim from an empty one (`MemoryDraftResponse.kt:80-88`).
    private var baseCard: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HXSectionHeader("memory.draft.base.title")
                if let text = vm.baseMarkdown {
                    Text(verbatim: hx("memory.draft.base.hint", baseVersionText, vm.baseCharCount))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                        .fixedSize(horizontal: false, vertical: true)
                    HXMarkdownText(text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Text(verbatim: hx("memory.draft.base.hint", baseVersionText, vm.baseCharCount))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                    HXStateView(.empty, message: hx("memory.draft.base.empty"))
                }
            }
        }
    }

    private var baseVersionText: String {
        guard let version = vm.draft?.baseVersion else { return MemoryDraftCopy.dash }
        return version == 0 ? hx("memory.draft.firstLayer") : "v\(version)"
    }

    // MARK: - Sources

    /// What the merge took *out*: one card per conversation file, with the bytes the approval has to still find
    /// there before it clears them (`draftDetail.tsx:670-720`, `MemoryDraftSource.kt:19-25`).
    private func sourcesCard(_ draft: MemoryDraftDetail) -> some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HXSectionHeader("memory.draft.sources.title")
                Text(verbatim: hx("memory.draft.sources.hint", draft.sources.count))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
                if draft.sources.isEmpty {
                    HXStateView(.empty, message: hx("memory.draft.sources.empty"))
                } else {
                    ForEach(Array(draft.sources.enumerated()), id: \.offset) { _, source in
                        MemoryDraftSourceCard(source: source)
                    }
                }
            }
        }
    }

    // MARK: - Targets

    /// What the same approval writes *in*: the owner's own day files, each with its own version precondition —
    /// which is why a `STALE_BASE` refusal has a file to name
    /// (`draftDetail.tsx:731-825`, `MemoryDraftSource.kt:40-53`).
    private func targetsCard(_ draft: MemoryDraftDetail) -> some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HXSectionHeader("memory.draft.targets.title")
                Text(verbatim: hx("memory.draft.targets.hint", draft.targets.count))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
                if draft.targets.isEmpty {
                    HXStateView(.empty, message: hx("memory.draft.targets.empty"))
                } else {
                    ForEach(Array(draft.targets.enumerated()), id: \.offset) { _, target in
                        MemoryDraftTargetCard(target: target)
                    }
                }
            }
        }
    }

    // MARK: - Rejection

    /// The reason sheet. It stays open holding what the reviewer typed when the server refuses it, and the
    /// refusal goes inside the sheet rather than on the screen behind
    /// (`draftDetail.tsx:604-663` leaves the modal up).
    private var rejectionSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                if let refusal = vm.rejectRefusal {
                    HXBanner("state.error.title", message: refusal, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                Text(verbatim: hx("memory.draft.reject.hint"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
                TextField(
                    hx("memory.draft.reject.placeholder"),
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
                Text(verbatim: hx("memory.draft.reject.counter", MemoryDraftCopy.charCount(vm.rejectReason), MemoryDraftRules.maxRejectReasonLength))
                    .font(.caption2)
                    .foregroundStyle(counterTone)
                Spacer(minLength: 0)
            }
            .padding(16)
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("memory.draft.reject.title")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { vm.closeRejection() } label: { HXText("common.cancel") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await vm.submitRejection() }
                    } label: {
                        HXText("memory.draft.reject")
                    }
                    .disabled(vm.isActing)
                }
            }
        }
    }

    /// The counter turns red past the limit the *predicate* enforces, which is the same 512 Core measures in
    /// UTF-16 units (`MemoryDraftRules.maxRejectReasonLength`).
    private var counterTone: Color {
        Color.hx(MemoryDraftRules.isRejectReasonAdmissible(vm.rejectReason) ? .textTertiary : .danger)
    }
}

/// One conversation file the merge drained: its address and the exact bytes an approval must still find there.
///
/// The content is the precondition, not a quotation — `MemoryDraftServiceImpl` clears the file only while it
/// still holds what was recorded, which is why `keptSources` can be non-zero on an approval
/// (`MemoryDraftDecisionResponse.kt:28-36`). It is therefore shown, monospaced and selectable, rather than being
/// summarised away.
struct MemoryDraftSourceCard: View {
    let source: MemoryDraftSource

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HXValueText(hxPresented(source.path) ?? hx("memory.draft.sources.noPath"), lines: 2)
            HStack(spacing: 6) {
                HXChip(hx("memory.draft.sources.chars", MemoryDraftCopy.charCount(source.content)))
            }
            if let content = hxPresented(source.content) {
                Text(verbatim: content)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(Color.hx(.textSecondary))
                    .lineLimit(12)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Text(verbatim: hx("memory.draft.sources.noContent"))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One day file of the owner's own ledger this candidate rewrites.
///
/// `expectedVersion == 0` means the day did not exist and this approval creates it
/// (`MemoryDraftSource.kt:44-47`, and the console's column prints「New for this day」for exactly that,
/// `draftDetail.tsx:745-756`), so it gets its own sentence rather than a `v0`.
struct MemoryDraftTargetCard: View {
    let target: MemoryDraftTarget

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                Text(verbatim: target.dayLabel.isEmpty ? hx("memory.draft.sources.noPath") : target.dayLabel)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.hx(.textPrimary))
                Spacer(minLength: 0)
                versionChip
            }
            if let path = hxPresented(target.path) {
                HXValueText(path, lines: 2)
            }
            HStack(spacing: 6) {
                HXChip(hx("memory.draft.targets.chars", MemoryDraftCopy.charCount(target.mergedText)))
            }
            DisclosureGroup {
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: hx("memory.draft.targets.baseHeading"))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.hx(.textSecondary))
                    baseText
                    Text(verbatim: hx("memory.draft.targets.mergedHeading"))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.hx(.textSecondary))
                    mergedText
                }
                .padding(.top, 6)
            } label: {
                Text(verbatim: hx("memory.draft.targets.diff"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textPrimary))
            }
            .tint(Color.hx(.brand))
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var versionChip: some View {
        if target.expectedVersion == 0 {
            HXChip(hx("memory.draft.targets.newDay"), tone: .teal)
        } else {
            HXChip("v\(target.expectedVersion)")
        }
    }

    /// Both texts, the way the console's expandable row shows them: the day as it stands and the day as this
    /// approval would write it, with the blank branch tested on the trimmed value
    /// (`draftDetail.tsx:796-820`, whose `baseText` branch is `.trim()` at `:800`).
    @ViewBuilder
    private var baseText: some View {
        if let text = hxPresented(target.baseText) {
            HXMarkdownText(text)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text(verbatim: hx("memory.draft.targets.baseEmpty"))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }

    @ViewBuilder
    private var mergedText: some View {
        if let text = hxPresented(target.mergedText) {
            HXMarkdownText(text)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text(verbatim: hx("memory.draft.targets.noText"))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }
}

/// What the last decision actually did.
///
/// One row per contract outcome, keyed on `kind` and never on the server's `reason`
/// (`MemoryDraftDecisionResponse.kt:44-45` says that field is not a contract). Each outcome shows the extra
/// field it owes the reviewer: the version an approval landed at, the new digest after `DRAFT_CHANGED`, who
/// decided first after `ALREADY_REVIEWED`, which day moved after `STALE_BASE`, the raw token after anything the
/// contract has never heard of (`draftDetail.tsx:94-250`).
public struct MemoryDraftNoticeView: View {
    private let notice: MemoryDraftNotice

    public init(notice: MemoryDraftNotice) {
        self.notice = notice
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HXBanner(titleKey, message: message, systemImage: systemImage, tone: notice.tone.slot)
            extra
        }
    }

    private var titleKey: String {
        switch notice {
        case .approved: "memory.draft.outcome.approved"
        case .rejectionRecorded: "memory.draft.outcome.rejected"
        case .draftChanged: "memory.draft.outcome.changed"
        case .alreadyReviewed: "memory.draft.outcome.reviewed"
        case .staleBase: "memory.draft.outcome.stale"
        case .unrecognised: "memory.draft.outcome.unknown"
        case .refused: "memory.draft.outcome.failed"
        }
    }

    private var systemImage: String {
        switch notice.tone {
        case .success: "checkmark.seal"
        case .warning: "arrow.triangle.2.circlepath"
        case .info: "person.2"
        case .error: "exclamationmark.triangle"
        }
    }

    private var message: String? {
        switch notice {
        case let .approved(version, cleared, kept, absent, dailyApplied):
            // The console keeps the two optional clauses out of the sentence when their number is zero
            // (`draftDetail.tsx:114-137`); a receipt that says「0 were left behind」is noise about a write that
            // went cleanly.
            var text = hx("memory.draft.approved.body", version.map { String($0) } ?? MemoryDraftCopy.dash, cleared)
            if kept > 0 { text += " " + hx("memory.draft.approved.kept", kept) }
            if absent > 0 { text += " " + hx("memory.draft.approved.absent", absent) }
            if dailyApplied > 0 { text += " " + hx("memory.draft.approved.daily", dailyApplied) }
            return text
        case .rejectionRecorded:
            return hx("memory.draft.reject.done")
        case .draftChanged:
            return hx("memory.draft.changed.hint")
        case let .alreadyReviewed(by, at, _):
            return hx("memory.draft.reviewed.hint", by, at)
        case let .staleBase(currentBaseVersion, _):
            return hx("memory.draft.stale.hint", currentBaseVersion.map { String($0) } ?? MemoryDraftCopy.dash)
        case let .unrecognised(raw):
            // Reads as a refusal and carries the token verbatim: the screen has no idea what the new outcome
            // means, and guessing would be the one wrong answer available.
            return hx("memory.draft.unknown.hint", raw)
        case let .refused(text):
            return text
        }
    }

    @ViewBuilder
    private var extra: some View {
        switch notice {
        case let .draftChanged(digest):
            if let digest = hxPresented(digest) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: hx("memory.draft.changed.digest"))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textSecondary))
                    MemoryDraftHash(label: SkillDraftRules.truncated(digest, digits: 16), full: digest)
                }
            }
        case let .alreadyReviewed(_, _, reason):
            if let reason {
                HXRow(text: hx("memory.draft.rejectReason"), subtitle: reason, divider: false)
            }
        case let .staleBase(_, staleTarget):
            if let staleTarget {
                HXRow(text: hx("memory.draft.stale.target"), subtitle: staleTarget, divider: false)
            }
        case .approved, .rejectionRecorded, .unrecognised, .refused:
            EmptyView()
        }
    }
}
