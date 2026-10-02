import SwiftUI
import HarnaxCore
import HarnaxKit

/// The plan drawer (`§计划面板（右侧滑出）`): the plan the session is on now, and every plan it has written.
///
/// Whoever owns the chat screen mounts this — the panel is a plain `View` over a session id, a `PlanReading` and
/// the `enablePlan` flag, with no chat state in its initialiser, so it can also stand alone in a walkthrough
/// screen. `onClose` is the header's close button, and it is optional because a panel hosted in a native drawer is
/// dismissed by the drawer itself.
///
/// The console's two panes are both here, and the layout follows what a phone can hold rather than the web layout:
/// the current plan is a card whose body collapses (`ChatWindow.tsx:2965-3038`), and the history is an accordion
/// whose newest row opens by default (`:3041-3290`). The history's subtask *table* — three columns, a horizontal
/// scroll and one expandable row each — becomes three-line rows with the same three fields behind the same
/// disclosure, because a table at 390 pt shows one column at a time.
public struct PlanPanelView: View {
    @StateObject private var vm: PlanPanelViewModel
    private let onClose: (() -> Void)?
    @Environment(\.scenePhase) private var scenePhase

    public init(
        sessionId: String,
        reading: any PlanReading,
        isEnabled: Bool = true,
        onClose: (() -> Void)? = nil
    ) {
        _vm = StateObject(wrappedValue: PlanPanelViewModel(
            sessionId: sessionId,
            reading: reading,
            isEnabled: isEnabled
        ))
        self.onClose = onClose
    }

    /// For a host that already owns the view model — the chat screen, which has the session id and the plan switch
    /// and can hand both to the panel.
    public init(vm: PlanPanelViewModel, onClose: (() -> Void)? = nil) {
        _vm = StateObject(wrappedValue: vm)
        self.onClose = onClose
    }

    public var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            content
        }
        .harnaxScreen()
        .task { await vm.open() }
        // Not `stopPolling()`: a shared panel outlives its drawer, and the conversation that handed it over is
        // still reading the plan for the card in its own stream (`vm.close()` only retires the loop once nothing
        // follows it).
        .onDisappear { vm.close() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                vm.resumePolling()
            } else {
                vm.pausePolling()
            }
        }
    }

    // MARK: - chrome

    /// Panel title, then the manual history refresh (`handleRefreshPlans`, `ChatWindow.tsx:3173-3180`), then the
    /// close the console also has (`:3155-3160`).
    private var header: some View {
        HStack(spacing: 8) {
            HXText("chat.plan.title")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
            Spacer(minLength: 8)
            Button {
                Task { await vm.refreshHistory() }
            } label: {
                HStack(spacing: 4) {
                    if vm.isLoadingHistory {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                    HXText("chat.plan.refresh")
                }
                .font(.footnote.weight(.semibold))
            }
            .buttonStyle(.hxInline)
            .disabled(vm.isLoadingHistory)
            if let onClose {
                Button {
                    // `handleTogglePlanPanel`'s else-branch: the timers go with the drawer, the data stays for the
                    // next time it slides out.
                    vm.close()
                    onClose()
                } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(hx("common.close"))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    // MARK: - states

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(.empty, message: hx("chat.plan.none.hint"), retry: { Task { await vm.reload() } })
        case let .failed(message):
            // Nothing was ever on screen, so this is the one case allowed to replace the panel.
            HXStateView(.error, message: message, retry: { Task { await vm.reload() } })
        case .content:
            panel
        }
    }

    /// A failed read lands on the banner line rather than replacing the panel: whatever the last good answer put on
    /// screen stays on screen (`ChatWindow.tsx:2640-2644` only logs, which on a handset reads as a refresh button
    /// that does nothing).
    private var panel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let inline = vm.inlineError {
                    HXBanner("state.error.title", message: inline, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                currentSection
                historySection
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
    }

    // MARK: - current plan

    @ViewBuilder
    private var currentSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("chat.plan.current")
            // A plan with no name is not a plan (`hasValidCurrentPlan`), so the card and the no-plan block are the
            // two and only two outcomes — an unnamed plan never becomes an empty card.
            if let current = vm.current {
                CurrentPlanCard(
                    plan: current,
                    expanded: vm.isCurrentPlanExpanded,
                    live: vm.isLive,
                    onToggle: { vm.toggleCurrentPlan() }
                )
            } else {
                emptyBlock(symbol: "list.bullet", titleKey: "chat.plan.none")
            }
        }
    }

    // MARK: - history

    @ViewBuilder
    private var historySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                HXSectionHeader("chat.plan.history")
                if !vm.history.isEmpty {
                    // The count sits next to the heading the way the console's `planCount` sits next to its `h4`
                    // (`ChatWindow.tsx:3168-3172`). A number is not copy.
                    HXChip("\(vm.history.count)", tone: .brand)
                }
                Spacer(minLength: 0)
            }
            if vm.isLoadingHistory && vm.history.isEmpty {
                HXStateView(.loading)
            } else if vm.history.isEmpty {
                emptyBlock(symbol: "clock", titleKey: "chat.plan.empty")
            } else {
                // The route answers newest-first and neither side sorts or slices it.
                ForEach(vm.history) { plan in
                    PlanHistoryRow(
                        plan: plan,
                        expanded: vm.isHistoryRowExpanded(plan),
                        onToggle: { vm.toggleHistoryRow(plan) }
                    )
                }
            }
        }
    }

    /// The two places the console says the same sentence: no plan open, and no plan ever written
    /// (`noCurrentPlanHint` / `noPlansHint` are one string there, `ChatWindow.tsx:3145-3170`).
    private func emptyBlock(symbol: String, titleKey: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: symbol)
                .font(.system(size: 26))
                .foregroundStyle(Color.hx(.textTertiary))
            HXText(titleKey)
                .font(.subheadline)
                .foregroundStyle(Color.hx(.textSecondary))
            HXText("chat.plan.none.hint")
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
    }
}

// MARK: - current plan card

/// The plan the session is on right now, re-read every 2 s while this card is open.
///
/// The live line follows the loop that actually runs rather than the mere existence of a plan: the console prints
/// `Realtime Update` under `hasValidCurrentPlan()` (`ChatWindow.tsx:3060-3067`), which would say the panel is
/// updating while its own collapsed card has stopped polling.
struct CurrentPlanCard: View {
    let plan: PlanNote
    let expanded: Bool
    let live: Bool
    let onToggle: () -> Void

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                Button(action: onToggle) {
                    HStack(spacing: 7) {
                        Image(systemName: "checklist")
                            .font(.caption)
                            .foregroundStyle(Color.hx(.brand))
                        Text(verbatim: plan.title ?? "")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.hx(.textPrimary))
                            .lineLimit(2)
                        if let progress = PlanPanelViewModel.progressText(plan) {
                            HXChip(progress, tone: .brand)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: expanded ? "chevron.down" : "chevron.right")
                            .font(.caption2)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                }
                .buttonStyle(.plain)

                if expanded {
                    if live {
                        HStack(spacing: 5) {
                            ProgressView().controlSize(.small)
                            HXText("chat.plan.live")
                        }
                        .font(.caption)
                        .foregroundStyle(Color.hx(.brand))
                    }
                    if let detail = plan.detail {
                        Text(verbatim: detail)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let goal = plan.goal {
                        PlanField(titleKey: "chat.plan.expected", text: goal)
                    }
                    if let elapsed = PlanPanelViewModel.elapsedText(plan.costTimeSeconds) {
                        HXChip("\(hx("chat.plan.costTime")) \(elapsed)")
                    }
                    ForEach(Array(plan.steps.enumerated()), id: \.offset) { _, step in
                        PlanSubTaskRow(step: step)
                    }
                }
            }
        }
    }
}

// MARK: - history row

/// One plan this conversation has written, in the accordion.
struct PlanHistoryRow: View {
    let plan: PlanNote
    let expanded: Bool
    let onToggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onToggle) {
                HStack(spacing: 7) {
                    Text(verbatim: plan.title ?? "")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.hx(.textPrimary))
                        .lineLimit(2)
                    Spacer(minLength: 4)
                    if let state = plan.status {
                        HXBadge(state.titleKey, tone: PlanStateStyle.tone(state))
                    }
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
            }
            .buttonStyle(.plain)

            if expanded {
                VStack(alignment: .leading, spacing: 9) {
                    if let detail = plan.detail {
                        PlanField(titleKey: "chat.plan.description", text: detail)
                    }
                    if let goal = plan.goal {
                        PlanField(titleKey: "chat.plan.expected", text: goal)
                    }
                    if !plan.steps.isEmpty {
                        HStack(spacing: 6) {
                            HXText("chat.plan.subtasks")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.hx(.textTertiary))
                            HXChip("\(plan.steps.count)")
                        }
                        ForEach(Array(plan.steps.enumerated()), id: \.offset) { _, step in
                            PlanSubTaskRow(step: step)
                        }
                    }
                    footer
                }
                .padding(.top, 10)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.hx(.separator), lineWidth: 1)
        )
    }

    /// `Created At:` and `Total Cost Time:` (`ChatWindow.tsx:3276-3280`). The stamp reaches the screen as the
    /// runtime wrote it — `PlanNote`'s header says why neither side parses it — and an absent cost renders as
    /// nothing rather than as `0s`.
    private var footer: some View {
        HStack(spacing: 10) {
            if let created = plan.createdAtText {
                HStack(spacing: 4) {
                    HXText("chat.plan.createdAt")
                    Text(verbatim: created)
                }
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
            }
            Spacer(minLength: 0)
            if let elapsed = PlanPanelViewModel.elapsedText(plan.costTimeSeconds) {
                HXChip("\(hx("chat.plan.costTime")) \(elapsed)")
            }
        }
    }
}

// MARK: - subtask row

/// One subtask: state, name and elapsed seconds, with the three long fields behind the same disclosure the
/// console's table puts under its expandable row (`ChatWindow.tsx:3206-3275`).
struct PlanSubTaskRow: View {
    let step: PlanSubTask
    /// Expandable rows start shut, in the table and here.
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                expanded.toggle()
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: PlanStateStyle.symbol(step.stateOrTodo))
                        .font(.caption)
                        .foregroundStyle(Color.hx(PlanStateStyle.tone(step.stateOrTodo)))
                    Text(verbatim: step.title ?? "")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textPrimary))
                        .lineLimit(2)
                    Spacer(minLength: 4)
                    if let elapsed = PlanPanelViewModel.elapsedText(step) {
                        Text(verbatim: elapsed)
                            .font(.caption.monospaced())
                            .foregroundStyle(Color.hx(.textSecondary))
                    }
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
            }
            .buttonStyle(.plain)

            if expanded {
                VStack(alignment: .leading, spacing: 5) {
                    if let detail = step.detail {
                        PlanField(titleKey: "chat.plan.description", text: detail)
                    }
                    if let goal = step.goal {
                        PlanField(titleKey: "chat.plan.expected", text: goal)
                    }
                    if let result = step.result {
                        PlanField(titleKey: "chat.plan.actual", text: result)
                    }
                }
                .padding(.leading, 17)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

/// A label over the text it labels. Every one of these fields is optional on the wire, so the whole block is
/// conditional rather than filled with a dash.
struct PlanField: View {
    let titleKey: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HXText(titleKey)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Color.hx(.textTertiary))
            Text(verbatim: text)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The four states' colour and glyph, spelled once. The console's own mapping: done green, in-progress blue,
/// abandoned red, todo the default grey (`ChatWindow.tsx:3011-3027`, `:3186-3196`). The *words* are never here —
/// `PlanState.titleKey` owns those.
enum PlanStateStyle {
    static func tone(_ state: PlanState) -> PaletteSlot {
        switch state {
        case .todo: return .textTertiary
        case .inProgress: return .brand
        case .done: return .success
        case .abandoned: return .danger
        }
    }

    static func symbol(_ state: PlanState) -> String {
        switch state {
        case .todo: return "circle"
        case .inProgress: return "clock"
        case .done: return "checkmark.circle"
        case .abandoned: return "xmark.circle"
        }
    }
}
