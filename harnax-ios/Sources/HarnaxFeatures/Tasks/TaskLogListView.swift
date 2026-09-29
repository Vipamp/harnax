import SwiftUI
import HarnaxCore
import HarnaxKit

/// One task's execution log, opened from the row that owns it (`§5.3`).
///
/// The route is `GET /api/admin/agent-tasks/{id}/logs` and it is the only log read in the domain, so this sheet is
/// scoped by the task in the path rather than by a filter — there is no cross-task log page to land on.
///
/// Two behaviours come from the console and are worth naming because they look like omissions:
///
/// - the rows are never sorted here. The page is fixed to `ORDER BY l.create_time DESC`
///   (`AgentTaskLogMapper.xml:162`) and the route has no sort parameter, so a client-side sort would disagree with
///   the next page;
/// - the detail pane costs no request. There is no single-log endpoint (`§5.4`), so it renders the row already in
///   hand and lets the poll re-point it — which is also why it stays open across a page reload.
public struct TaskLogSheet: View {
    @StateObject private var vm: TaskLogListViewModel
    /// The task whose log this is. The path id addresses it and the name only ever labels it.
    private let taskTitle: String?

    /// The console refreshes the task list when the modal closes (`TaskLogModal.tsx:121-132`, `§6.2`): a run that
    /// finished while the log was open is exactly the news the list's own badge is stale about.
    private let onDismiss: () -> Void

    /// The window editor is staged — the two pickers write a draft and 确定 is what reaches the route, because a
    /// date wheel fires on every tick and each tick would otherwise be a request.
    @State private var draftFromIncluded = false
    @State private var draftFromDate = Date()
    @State private var draftToIncluded = false
    @State private var draftToDate = Date()
    @Environment(\.scenePhase) private var scenePhase

    public init(taskID: Int64, taskTitle: String?, catalog: any AgentTaskCataloging, account: AccountSnapshot?, onDismiss: @escaping () -> Void = {}) {
        _vm = StateObject(wrappedValue: TaskLogListViewModel(taskID: taskID, catalog: catalog, account: account))
        self.taskTitle = taskTitle
        self.onDismiss = onDismiss
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if vm.isWindowEditorShown {
                    windowEditor
                }
                content
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("task.log.title")))
#if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("task.log.search")))
            .toolbar {
                ToolbarItem(placement: .navigation) { statusFilterMenu }
                ToolbarItem(placement: .confirmationAction) { closeButton }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    vm.resumePolling()
                } else {
                    vm.pausePolling()
                }
            }
            .onDisappear { vm.stopPolling() }
            .navigationDestination(item: detailBinding) { row in
                TaskLogDetailView(
                    row: row,
                    stoppable: vm.stoppable(row),
                    isStopping: vm.stoppingIDs.contains(row.id),
                    onStop: { vm.beginStop(row) }
                )
            }
            .confirmationDialog(
                Text(verbatim: hx("task.log.stop.title")),
                isPresented: Binding(
                    get: { vm.stopTarget != nil },
                    set: { if !$0 { vm.cancelStop() } }
                ),
                titleVisibility: .visible,
                presenting: vm.stopTarget
            ) { row in
                Button(role: .destructive) {
                    Task { await vm.confirmStop() }
                } label: {
                    HXText("task.log.stop")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                // Not "this will fail politely": the route sends an INTERRUPT through the router and really does
                // cut the run short (`§5.5`), which is the one thing a mis-tap cannot take back.
                Text(verbatim: hx("task.log.stop.note"))
            }
        }
    }

    // MARK: - chrome

    private var closeButton: some View {
        Button {
            vm.stopPolling()
            onDismiss()
        } label: { HXText("common.close") }
    }

    /// The six statuses the console lists, in its own order of precedence
    /// (`TaskLogModal.tsx:283-289`), plus the choice that leaves `status` off the query entirely.
    private var statusFilterMenu: some View {
        Menu {
            Picker(selection: statusBinding) {
                Text(verbatim: hx("state.filter.all")).tag(AgentTaskLastRun?.none as AgentTaskLastRun?)
                ForEach(AgentTaskLastRun.allCases, id: \.rawValue) { state in
                    HXText(state.titleKey).tag(AgentTaskLastRun?.some(state))
                }
            } label: {
                HXText("state.filter.status")
            }
            Divider()
            Button {
                openWindowEditor()
            } label: {
                HXText("task.log.window")
            }
            if vm.filter.from != nil || vm.filter.to != nil {
                Button(role: .destructive) {
                    vm.clearWindow()
                } label: {
                    HXText("task.log.window.clear")
                }
            }
        } label: {
            Image(systemName: vm.isFiltered
                ? "line.3.horizontal.decrease.circle.fill"
                : "line.3.horizontal.decrease.circle")
        }
    }

    private var statusBinding: Binding<AgentTaskLastRun?> {
        Binding(get: { vm.filter.state }, set: { vm.filter.state = $0 })
    }

    /// The detail pane's item binding. Writing `nil` is the back button; a new value is a tap on a row.
    private var detailBinding: Binding<AgentTaskLog?> {
        Binding(
            get: { vm.selected },
            set: { newValue in
                if let newValue {
                    vm.beginDetail(newValue)
                } else {
                    vm.closeDetail()
                }
            }
        )
    }

    // MARK: - window editor

    private func openWindowEditor() {
        let now = Date()
        draftFromIncluded = vm.filter.from != nil
        draftFromDate = vm.filter.from ?? now
        draftToIncluded = vm.filter.to != nil
        draftToDate = vm.filter.to ?? now
        vm.isWindowEditorShown = true
    }

    private var windowEditor: some View {
        VStack(alignment: .leading, spacing: 10) {
            windowRow(titleKey: "task.log.window.from", included: $draftFromIncluded, date: $draftFromDate)
            windowRow(titleKey: "task.log.window.to", included: $draftToIncluded, date: $draftToDate)
            // The wheel stops at the minute and the route spells its bound with seconds, so an end bound is the
            // whole minute's start — said here rather than left for a missing row to explain.
            Text(verbatim: hx("task.log.window.hint"))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                Button {
                    vm.setWindow(
                        from: draftFromIncluded ? draftFromDate : nil,
                        to: draftToIncluded ? draftToDate : nil
                    )
                    vm.isWindowEditorShown = false
                } label: {
                    HXText("common.confirm")
                }
                .buttonStyle(.hxPrimary)
                Button {
                    vm.isWindowEditorShown = false
                } label: {
                    HXText("common.cancel")
                }
                .buttonStyle(.hxSecondary)
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color.hx(.surfaceAlt))
    }

    private func windowRow(titleKey: String, included: Binding<Bool>, date: Binding<Date>) -> some View {
        HStack(spacing: 8) {
            Toggle(isOn: included) {}
                .labelsHidden()
                .tint(Color.hx(.brand))
                .frame(width: 36)
            HXText(titleKey)
                .font(.subheadline)
                .foregroundStyle(Color.hx(.textPrimary))
            Spacer(minLength: 8)
            DatePicker(
                selection: date,
                in: Date()...,
                displayedComponents: [.date, .hourAndMinute]
            ) {
                EmptyView()
            }
            .datePickerStyle(.compact)
            .labelsHidden()
            .disabled(!included.wrappedValue)
        }
    }

    // MARK: - states

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("task.log.empty.filtered") : hx("task.log.empty"),
                retry: { Task { await vm.refresh() } }
            )
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.refresh() } })
        case .content:
            list
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                if let inline = vm.inlineError {
                    HXBanner("state.error.title", message: inline, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                // A stop that was accepted is not a failure and must not borrow the failure band: the row under it
                // still says 运行中 until the next read settles it (`§5.5`).
                if let notice = vm.noticeLine {
                    HXBanner("task.notice.title", message: notice, systemImage: "pause.circle", tone: .warning)
                }
                if let taskTitle {
                    Text(verbatim: hx("task.log.subject", taskTitle))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.hx(.textPrimary))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .lineLimit(2)
                }
                Text(verbatim: hxCount("task.log.count", vm.total))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(vm.items) { row in
                    TaskLogCard(
                        row: row,
                        stoppable: vm.stoppable(row),
                        isStopping: vm.stoppingIDs.contains(row.id),
                        onTap: { vm.beginDetail(row) },
                        onStop: { vm.beginStop(row) }
                    )
                    .onAppear {
                        if row.id == vm.items.last?.id { Task { await vm.loadMore() } }
                    }
                }
                if vm.isAppending {
                    HXStateView(.loading)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 20)
        }
    }
}

/// One log row: the status badge, the prompt, how long it ran and when it started.
///
/// The console's table has a fifth column, `response`, truncated to 60 characters
/// (`TaskLogModal.tsx:183-248`); here the answer rides under the prompt instead, because a card has vertical room
/// and a table does not. The full text of both is the detail pane's job (`§5.4`).
struct TaskLogCard: View {
    private let row: AgentTaskLog
    private let stoppable: Bool
    private let isStopping: Bool
    private let onTap: () -> Void
    private let onStop: () -> Void

    init(row: AgentTaskLog, stoppable: Bool, isStopping: Bool, onTap: @escaping () -> Void, onStop: @escaping () -> Void) {
        self.row = row
        self.stoppable = stoppable
        self.isStopping = isStopping
        self.onTap = onTap
        self.onStop = onStop
    }

    var body: some View {
        Button(action: onTap) {
            HXCard {
                VStack(alignment: .leading, spacing: 7) {
                    HStack(spacing: 6) {
                        badge
                        if let stamp = hxTaskStamp(row.startTime) {
                            Text(verbatim: stamp)
                                .font(.caption)
                                .foregroundStyle(Color.hx(.textTertiary))
                        }
                        Spacer(minLength: 0)
                        Text(verbatim: row.durationText)
                            .font(.caption.monospaced())
                            .foregroundStyle(Color.hx(.textSecondary))
                    }
                    Text(verbatim: row.promptText ?? "")
                        .font(.subheadline)
                        .foregroundStyle(Color.hx(.textPrimary))
                        .lineLimit(2)
                    // An empty answer is not the same fact as a missing one: the column defaults to `""`
                    // (`AgentTaskLog.kt:26`), so a run that produced nothing reads as nothing here too.
                    Text(verbatim: row.answerText ?? "—")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .lineLimit(1)
                    if let failure = row.failureText {
                        Text(verbatim: failure)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.danger))
                            .lineLimit(2)
                    }
                    if stoppable {
                        stopButton
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    /// A number outside 0..5 is read as absent (`§5.1`), and the console paints it as Failed. Naming it 状态未知
    /// is the honest version of the same fallback, and it is the row's own text rather than a guess.
    @ViewBuilder
    private var badge: some View {
        if let state = row.state {
            HXBadge(state.titleKey, tone: Self.tone(for: state))
        } else {
            HXBadge("task.lastRun.unknown", tone: .danger)
        }
    }

    private static func tone(for state: AgentTaskLastRun) -> PaletteSlot {
        switch state {
        case .succeeded: .success
        case .failed, .timedOut: .danger
        case .running: .brand
        case .stopping: .warning
        case .stopped: .textTertiary
        }
    }

    /// Only a `3` is still running, and only its creator may cut it short: the read is gated by the task's
    /// visibility while the write runs `requireOwnedLog` (`§5.5`), so a row can be readable and not stoppable.
    private var stopButton: some View {
        Button(action: onStop) {
            HStack(spacing: 5) {
                if isStopping {
                    ProgressView().controlSize(.small)
                }
                HXText("task.log.stop")
            }
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.hx(.danger))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.hxFill(.danger, alpha: 0.14), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(isStopping)
    }
}

/// The whole record of one run, from the row already in hand (`§5.4`).
///
/// There is no endpoint behind this screen. The 14 fields arrive with the list, and while the run is alive the
/// sheet's poll re-points the value this view renders — so the duration ticks up and the answer appears without a
/// second request anywhere.
public struct TaskLogDetailView: View {
    private let row: AgentTaskLog
    private let stoppable: Bool
    private let isStopping: Bool
    private let onStop: () -> Void

    public init(row: AgentTaskLog, stoppable: Bool, isStopping: Bool, onStop: @escaping () -> Void) {
        self.row = row
        self.stoppable = stoppable
        self.isStopping = isStopping
        self.onStop = onStop
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HXGroupCard {
                    metaRow("task.log.started", row.startTime)
                    metaRow("task.log.ended", row.endTime)
                    HXRow("task.log.duration") {
                        Text(verbatim: row.durationText)
                            .font(.subheadline)
                            .foregroundStyle(Color.hx(.textSecondary))
                    }
                    HXRow("task.log.session", divider: false) {
                        HXValueText(row.conversationID ?? "—")
                    }
                }
                if stoppable {
                    Button(action: onStop) {
                        HStack(spacing: 6) {
                            if isStopping {
                                ProgressView().controlSize(.small)
                            }
                            HXText("task.log.stop")
                        }
                    }
                    .buttonStyle(.hxSecondary)
                }
                if let failure = row.failureText {
                    HXBanner("task.log.error", message: failure, systemImage: "xmark.circle", tone: .danger)
                }
                textBlock(titleKey: "task.form.prompt", body: row.promptText)
                textBlock(titleKey: "task.log.response", body: row.answerText)
                Text(verbatim: RowMeta.byline(creator: row.creator, createTime: row.createTime))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 24)
        }
        .harnaxScreen()
        .navigationTitle(Text(verbatim: row.title ?? hx("task.log.title")))
#if canImport(UIKit)
        .navigationBarTitleDisplayMode(.inline)
#endif
    }

    private func metaRow(_ titleKey: String, _ raw: String?) -> some View {
        HXRow(titleKey) {
            Text(verbatim: raw ?? "—")
                .font(.subheadline)
                .foregroundStyle(Color.hx(.textSecondary))
                .lineLimit(1)
        }
    }

    /// `TEXT` columns with no length ceiling on either side (`AgentTaskLog.kt:23,26`), so both blocks are free to
    /// run long and get the height they need rather than a line limit. Selection is on: copying a prompt out of
    /// a run is the reason this pane exists on a phone at all.
    @ViewBuilder
    private func textBlock(titleKey: String, body: String?) -> some View {
        if let body {
            VStack(alignment: .leading, spacing: 6) {
                HXSectionHeader(titleKey)
                Text(verbatim: body)
                    .font(.callout)
                    .foregroundStyle(Color.hx(.textPrimary))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(Color.hx(.separator), lineWidth: 1)
                    )
            }
        }
    }
}
