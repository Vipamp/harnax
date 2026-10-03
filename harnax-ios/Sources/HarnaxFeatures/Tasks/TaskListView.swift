import SwiftUI
import HarnaxCore
import HarnaxKit

/// `/agent/task` — the scheduled-task list (`§2.2`).
///
/// A card carries the web row's seven columns and nothing more: name, target agent, the expression in a
/// monospaced slot, the last run's status and time, the schedule switch, and the overflow menu. The two
/// columns the web table has and this one does not are `concurrent` and `timeoutSeconds`, which the console
/// only shows inside the edit modal.
///
/// Rows are not sorted here. The page arrives in `update_time DESC` order and there is no sort parameter to
/// send (`AgentTaskMapper.xml:95`), so reordering client-side would disagree with the next page fetched.
public struct TaskListView: View {
    /// Which form the sheet shows. An edit seeds its fields from the row the list already holds, because this
    /// domain needs no `GET /{id}` to do so (`§1` #2 is the one route iOS deliberately leaves unused).
    private enum Target: Identifiable, Equatable {
        case create
        case edit(AgentTaskSummary)

        var id: String {
            switch self {
            case .create: return "create"
            case let .edit(row): return "edit-\(row.id ?? 0)"
            }
        }

        var row: AgentTaskSummary? {
            switch self {
            case .create: return nil
            case let .edit(row): return row
            }
        }
    }

    @StateObject private var vm: TaskListViewModel
    private let catalog: any AgentTaskCataloging
    private let account: AccountSnapshot?

    /// Which task's log is open. A row without an id has no path to address, so the entry never appears on it.
    private struct LogSheetItem: Identifiable {
        let row: AgentTaskSummary
        var id: Int64 { row.id ?? 0 }
    }

    /// The poll runs only in the foreground (`§6.2`): a background timer would spend requests on rows nobody is
    /// looking at, and coming back has to re-arm from the data rather than from a pull.
    @Environment(\.scenePhase) private var scenePhase

    @State private var target: Target?
    @State private var logItem: LogSheetItem?

    public init(catalog: any AgentTaskCataloging, account: AccountSnapshot?) {
        _vm = StateObject(wrappedValue: TaskListViewModel(catalog: catalog))
        self.catalog = catalog
        self.account = account
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("task.title")))
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("task.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { addButton }
                ToolbarItem(placement: .primaryAction) { filterMenu }
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
            .sheet(item: $target) { target in
                TaskFormView(row: target.row, catalog: catalog) { outcome in
                    self.target = nil
                    Task { await vm.formDidSave(outcome) }
                }
            }
            .sheet(item: $logItem) { item in
                TaskLogSheet(
                    taskID: item.row.id ?? 0,
                    taskTitle: item.row.title,
                    catalog: catalog,
                    account: account,
                    onDismiss: {
                        self.logItem = nil
                        Task { await vm.refresh() }
                    }
                )
            }
            .confirmationDialog(
                Text(verbatim: hx("task.delete.title")),
                isPresented: Binding(
                    get: { vm.deleteTarget != nil },
                    set: { if !$0 { vm.cancelDelete() } }
                ),
                titleVisibility: .visible,
                presenting: vm.deleteTarget
            ) { row in
                Button(role: .destructive) {
                    Task { await vm.confirmDelete() }
                } label: {
                    HXText("task.delete.confirm", row.name)
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                // Whether the name is free again afterwards is not this device's business: `uk_name` is a
                // database constraint and the create route just reports the collision (`§4.1`).
                Text(verbatim: hx("task.delete.note"))
            }
    }

    private var addButton: some View {
        HXPlusButton(titleKey: "task.form.create") { target = .create }
    }

    /// The status dropdown. `all` leaves the parameter off the query entirely — the page filters on
    /// `taskStatus` (`AgentTaskMapper.xml:92-94`), and this app never sends a sentinel for "no opinion".
    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: vm.filter != .all,
            accessibilityLabel: hx("state.filter.status")
        ) {
            Picker(selection: $vm.filter) {
                ForEach(StatusFilter.allCases) { option in
                    HXText(option.titleKey).tag(option)
                }
            } label: {
                HXText("state.filter.status")
            }
        }
    }

    /// One scroll for the whole column: the host's switch row first, then whichever phase this screen is in.
    ///
    /// The bar rides inside rather than above because pinned outside it held still while the rows and the
    /// navigation bar moved. A state card is only a shorter column, so it scrolls here too — the bar stays
    /// reachable and pulling it is still this list's refresh.
    private var content: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                HXSegmentBarRow()
                switch vm.phase {
                case .loading:
                    HXStateView(.loading)
                case .empty:
                    HXStateView(
                        .empty,
                        message: vm.isFiltered ? hx("task.empty.filtered") : hx("task.empty"),
                        retry: { Task { await vm.refresh() } }
                    )
                case let .failed(message):
                    HXStateView(.error, message: message, retry: { Task { await vm.refresh() } })
                case .content:
                    rows
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    @ViewBuilder
    private var rows: some View {
        if let inline = vm.inlineError {
            HXBanner("state.error.title", message: inline, systemImage: "exclamationmark.triangle", tone: .danger)
        }
        // Not errors: "your save paused this task" and a 40902's own sentence. Both attach to a write
        // that succeeded, so they get the warning band (`§4.3`, `§6.4`).
        ForEach(Array(vm.noticeLines.enumerated()), id: \.offset) { _, line in
            HXBanner("task.notice.title", message: line, systemImage: "exclamationmark.triangle", tone: .warning)
        }
        Text(verbatim: hxCount("task.count", vm.total))
            .font(.caption)
            .foregroundStyle(Color.hx(.textTertiary))
            .frame(maxWidth: .infinity, alignment: .leading)
        // Row identity is the array index, as in the other catalogs: a page may carry rows whose `id`
        // the backend left null and two nil ids under one identity would drop a card.
        ForEach(Array(vm.items.enumerated()), id: \.offset) { index, row in
            TaskRecordCard(
                row: row,
                enabled: vm.status(of: row),
                isPending: row.id.flatMap(vm.pendingIDs.contains) ?? false,
                isTriggered: row.id.flatMap(vm.triggeredIDs.contains) ?? false,
                canWrite: row.writable(by: account),
                onToggle: { value in Task { await vm.setStatus(value, for: row) } },
                onTrigger: {
                    if await vm.trigger(row), row.id != nil {
                        logItem = LogSheetItem(row: row)
                    }
                },
                onEdit: { target = .edit(row) },
                onDelete: { vm.beginDelete(row) },
                onLogs: row.id == nil ? nil : { logItem = LogSheetItem(row: row) }
            )
            .onAppear {
                if index == vm.items.count - 1 { Task { await vm.loadMore() } }
            }
        }
        if vm.isAppending {
            HXStateView(.loading)
        }
    }
}

/// One task row. Public for the same reason as its siblings: the group shell mounts it directly and the
/// preview harness reads a named card more easily than an inline chain.
public struct TaskRecordCard: View {
    private let row: AgentTaskSummary
    private let enabled: Bool
    private let isPending: Bool
    private let isTriggered: Bool
    private let canWrite: Bool
    private let onToggle: (Bool) -> Void
    private let onTrigger: @MainActor () async -> Void
    private let onEdit: () -> Void
    private let onDelete: () -> Void
    /// `nil` when the row has no id: the log route is addressed by the task, so such a row has nothing to open.
    private let onLogs: (() -> Void)?

    public init(
        row: AgentTaskSummary,
        enabled: Bool,
        isPending: Bool,
        isTriggered: Bool,
        canWrite: Bool,
        onToggle: @escaping (Bool) -> Void,
        onTrigger: @escaping @MainActor () async -> Void,
        onEdit: @escaping () -> Void,
        onDelete: @escaping () -> Void,
        onLogs: (() -> Void)? = nil
    ) {
        self.row = row
        self.enabled = enabled
        self.isPending = isPending
        self.isTriggered = isTriggered
        self.canWrite = canWrite
        self.onToggle = onToggle
        self.onTrigger = onTrigger
        self.onEdit = onEdit
        self.onDelete = onDelete
        self.onLogs = onLogs
    }

    public var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                titleRow
                scheduleRow
                lastRunRow
                if let note = row.note {
                    Text(verbatim: note)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                }
                let byline = RowMeta.byline(creator: row.creator, createTime: row.createTime)
                if !byline.isEmpty {
                    Text(verbatim: byline)
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                switchRow
            }
        }
        .overlay(alignment: .topTrailing) { menu.padding(10) }
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            Text(verbatim: row.title ?? "")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            // `is_public` is what let someone else's row into this list at all
            // (`AgentTaskMapper.xml:85`), so it is worth a pill even though the console keeps it in a column.
            if row.isShared {
                HXChip(hx("task.badge.public"))
            }
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    /// The agent the task talks to, then the expression. `agentName` is a snapshot column admin stamps from
    /// `agentId` on the way in, so it can name an agent that has since been stopped — the list shows it as it
    /// reads and does not resolve anything (`AgentTaskController.kt:230-239`).
    private var scheduleRow: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                Image(systemName: "robot")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                Text(verbatim: row.agentDisplayName ?? hx("task.agent.none"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            HXValueText(row.cronExpression)
        }
    }

    private var lastRunRow: some View {
        HStack(spacing: 8) {
            lastRunBadge
            if let stamp = hxTaskStamp(row.lastRunTime) {
                Text(verbatim: hx("task.lastRun.at", stamp))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .lineLimit(1)
            }
            if isTriggered {
                HXChip(hx("task.run.triggered"), tone: .brand)
            }
            Spacer(minLength: 0)
        }
    }

    /// `lastRunStatus` is a LEFT JOIN onto the task's newest log row, so its absence means the task has never
    /// fired (`§2.2`). The console paints an unrecognised number as Failed; this reads it as unknown instead,
    /// which is the honest version of the same fallback.
    @ViewBuilder
    private var lastRunBadge: some View {
        switch row.lastRun {
        case let .some(run):
            HXBadge(run.titleKey, tone: Self.tone(for: run))
        case .none where row.lastRunStatus != nil:
            HXBadge("task.lastRun.unknown", tone: .danger)
        case .none:
            HXBadge("task.lastRun.never", tone: .textTertiary)
        }
    }

    private static func tone(for run: AgentTaskLastRun) -> PaletteSlot {
        switch run {
        case .succeeded: .success
        case .failed, .timedOut: .danger
        case .running: .brand
        case .stopping: .warning
        case .stopped: .textTertiary
        }
    }

    /// The next run, computed here rather than read: no field on the wire carries one, and the whole chain is
    /// `cronExpression` + the scheduler node's GMT+8 (`QuartzCron`, `§2.6`). The value follows the switch's
    /// displayed state rather than the fetched row's, so a toggle does not leave a "paused" line under an
    /// enabled task.
    private var nextRunText: String {
        guard enabled else { return hx("task.nextRun.paused") }
        guard let date = QuartzCron.nextMatch(of: row.cronExpression, after: Date()) else {
            return hx("task.nextRun.none")
        }
        return hx("task.nextRun.at", date.formatted(date: .abbreviated, time: .shortened))
    }

    /// The switch is the row's own `POST /toggle/{id}?status=` (`§2.2`'s 开关 column), and the creator-only
    /// gate shows here as well as on the menu: an account that did not write this task cannot start its
    /// schedule (`AgentTaskMapper.xml:64`).
    private var switchRow: some View {
        HStack(spacing: 8) {
            Toggle(isOn: Binding(get: { enabled }, set: onToggle)) {
                HXText("task.switch.schedule")
            }
            .labelsHidden()
            .tint(Color.hx(.brand))
            .disabled(!canWrite || isPending)

            if isPending {
                ProgressView().controlSize(.small)
            }

            Text(verbatim: nextRunText)
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 0)
        }
        .padding(.top, 2)
    }

    /// Run-now is the one action a non-creator keeps: `§2.4` gates edit, delete, stop and start/stop on the
    /// creator, and a single run goes through the same visibility read as the list itself.
    private var menu: some View {
        Menu {
            Button {
                Task { await onTrigger() }
            } label: {
                HXText("task.action.runNow")
            }
            .disabled(isPending)

            if let onLogs {
                Button(action: onLogs) {
                    HXText("task.log.action.logs")
                }
            }

            if canWrite {
                Button(action: onEdit) { HXText("task.action.edit") }
                Button(role: .destructive, action: onDelete) { HXText("state.action.delete") }
            }
        } label: {
            Image(systemName: isPending ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
        .accessibilityLabel(hx("state.action.more"))
    }
}

/// `2026-09-12 14:20:00` → `09-12 14:20`. The year goes for the same reason `RowMeta.byline` drops it, and the
/// minute stays because a scheduled run is worth reading to the minute. Both separators are accepted:
/// `date-format` asks for the space form but Jackson's treatment of `LocalDateTime` is not settled
/// (`application.yml:31-32`, `§0`), and an unreadable stamp is left out rather than printed raw.
func hxTaskStamp(_ raw: String?) -> String? {
    guard let raw else { return nil }
    let halves = raw.split(whereSeparator: { $0 == " " || $0 == "T" || $0 == "." })
    guard halves.count >= 2 else { return nil }
    let date = halves[0].split(separator: "-")
    let time = halves[1].split(separator: ":")
    guard date.count == 3, date[1].count == 2, date[2].count == 2, time.count >= 2 else { return nil }
    return "\(date[1])-\(date[2]) \(time[0]):\(time[1])"
}
