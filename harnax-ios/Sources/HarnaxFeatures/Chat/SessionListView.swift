import SwiftUI
import HarnaxCore
import HarnaxKit

/// The chat tab's landing screen — the conversation list half.
///
/// Rows carry more than a title because `GET /api/admin/sessions/page` already denormalises the executor's
/// name, its model, and both binding lists onto every row (`SessionServiceImpl.kt:86-120`), so the card needs
/// no second request. What a row does not carry is the executor's tools, CLI packages and members, and that is
/// why the detail sheet this screen presents reads the executor's own row (`ExecutorReading.swift`).
///
/// What a row *cannot* do is reach the message stream: opening one is handed to `onOpen`, which the
/// composition root fills with the chat window this screen knows nothing about.
///
/// The payload is a `ChatConversation` rather than the row, for two reasons. The chat window is addressed by
/// the string business key, so a row without one has no conversation to open and its text column stays static
/// instead of tapping into nothing. And the title has to be this screen's effective title, which still
/// carries a rename that was saved but not yet re-read.
public struct SessionListView: View {
    @StateObject private var vm: SessionListViewModel
    private let creating: (any SessionCreating)?
    private let config: (any SessionConfiguring)?
    private let workspace: (any SessionWorkspaceReading)?
    private let teamArtifacts: (any TeamArtifactReading)?
    /// The by-id read the detail sheet fires for its tools, CLI and member panels. A host that wires none gets
    /// the panels the conversation's own row can answer for, and no line saying otherwise.
    private let executor: (any ExecutorReading)?
    private let onOpen: ((ChatConversation) -> Void)?

    @State private var renameTarget: SessionSummary?
    @State private var detailTarget: SessionSummary?
    @State private var isCreating = false
    @State private var workspaceTarget: SessionSummary?
    @State private var artifactsTarget: SessionSummary?
    @State private var pendingClear: SessionSummary?
    @State private var pendingDelete: SessionSummary?
    /// Set by the detail sheet when its config write landed, and spent by its dismissal. The row the sheet was
    /// built from is a snapshot of this list's own page, so a write through admin has moved four columns this
    /// screen still shows the old values of — invisible on the card, which draws none of them, and wrong the
    /// moment the sheet is reopened from the same row.
    @State private var configMoved = false

    /// `creating` is the new-conversation route. A host that has not wired it gets no create button rather
    /// than a sheet that cannot submit — the same optional-dependency shape the chat window uses for its
    /// command and history legs. `workspace` and `teamArtifacts` open the two file drawers the card menu
    /// offers; an unwired one simply leaves its item off the menu. `config` is the one admin write the detail
    /// sheet can make; without it the sheet stays the reading surface it has always been. `executor` is the
    /// read that sheet fires for its tools, CLI and member panels.
    public init(
        sessions: any SessionCataloging,
        creating: (any SessionCreating)? = nil,
        config: (any SessionConfiguring)? = nil,
        workspace: (any SessionWorkspaceReading)? = nil,
        teamArtifacts: (any TeamArtifactReading)? = nil,
        executor: (any ExecutorReading)? = nil,
        onOpen: ((ChatConversation) -> Void)? = nil
    ) {
        _vm = StateObject(wrappedValue: SessionListViewModel(sessions: sessions))
        self.creating = creating
        self.config = config
        self.workspace = workspace
        self.teamArtifacts = teamArtifacts
        self.executor = executor
        self.onOpen = onOpen
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("chat.search")))
            .toolbar {
                if creating != nil {
                    ToolbarItem(placement: .primaryAction) { addButton }
                }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $renameTarget) { session in
                SessionRenameSheet(vm: vm, session: session)
            }
            .sheet(item: $detailTarget, onDismiss: refreshIfConfigMoved) { session in
                SessionDetailSheet(
                    session: session,
                    config: config,
                    executor: executor,
                    onWritten: { configMoved = true }
                )
            }
            .sheet(item: $workspaceTarget) { session in
                if let workspace, let sessionId = hxPresented(session.sessionId) {
                    WorkspaceSheet(workspace: workspace, sessionId: sessionId)
                }
            }
            .sheet(item: $artifactsTarget) { session in
                if let teamArtifacts, let sessionId = hxPresented(session.sessionId) {
                    TeamArtifactsSheet(reading: teamArtifacts, sessionId: sessionId)
                }
            }
            .sheet(isPresented: $isCreating) {
                if let creating {
                    SessionCreateSheet(creating: creating) {
                        Task { await vm.refresh() }
                    }
                }
            }
            .confirmationDialog(
                Text(verbatim: hx("chat.clear.title")),
                isPresented: Binding(
                    get: { pendingClear != nil },
                    set: { if !$0 { pendingClear = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingClear
            ) { session in
                Button(role: .destructive) {
                    Task { await vm.clearMessages(session) }
                } label: {
                    HXText("chat.clear.confirm", session.displayName ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                Text(verbatim: hx("chat.clear.note"))
            }
            .confirmationDialog(
                Text(verbatim: hx("chat.delete.title")),
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { if !$0 { pendingDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { session in
                Button(role: .destructive) {
                    Task { await vm.delete(session) }
                } label: {
                    HXText("state.delete.confirm", session.displayName ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                Text(verbatim: hx("chat.delete.note"))
            }
    }

    /// The detail sheet's dismissal: a page re-read if and only if that sheet wrote something. Closing a
    /// conversation's detail after merely reading it must not churn the list the user was scrolling.
    private func refreshIfConfigMoved() {
        guard configMoved else { return }
        configMoved = false
        Task { await vm.refresh() }
    }

    private var addButton: some View {
        HXPlusButton(titleKey: "session.create.sheet.title") { isCreating = true }
    }

    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: vm.filter != .all,
            accessibilityLabel: hx("state.filter.status"),
            choices: statusFilterChoices(vm.filter) { vm.filter = $0 }
        )
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            // A filter that matches nothing is not the same sentence as an account with no conversations.
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("chat.empty.filtered") : hx("chat.empty"),
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
                if let notice = vm.notice {
                    HXBanner("chat.clear.done", message: notice, systemImage: "checkmark.circle", tone: .success)
                }
                // Row identity is the array index: a page may carry rows whose `id` the backend left null,
                // and two nil ids under one `ForEach` identity would drop a card.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, session in
                    card(session)
                    .onAppear {
                        if index == vm.items.count - 1 { Task { await vm.loadMore() } }
                    }
                }
                if vm.isAppending {
                    HXStateView(.loading)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    private func card(_ session: SessionSummary) -> SessionRecordCard {
        SessionRecordCard(
            session: session,
            title: vm.title(of: session),
            enabled: vm.status(of: session),
            isBusy: session.id.flatMap(vm.pendingIDs.contains) ?? false,
            canWrite: vm.canWrite(session),
            onOpen: chatAction(for: session),
            onDetail: { detailTarget = session },
            onWorkspace: workspaceAction(for: session),
            onArtifacts: artifactsAction(for: session),
            onRename: { renameTarget = session },
            onToggle: { value in Task { await vm.setStatus(value, for: session) } },
            onClear: { pendingClear = session },
            onDelete: { pendingDelete = session }
        )
    }

    /// `nil` when this host has no workspace leg or the row has no business key to reach one with.
    private func workspaceAction(for session: SessionSummary) -> (() -> Void)? {
        guard workspace != nil, hxPresented(session.sessionId) != nil else { return nil }
        return { workspaceTarget = session }
    }

    /// `nil` unless the row is a team conversation *and* this host has the artifact leg. A one-agent
    /// conversation has no publishing member to read from, so the drawer could only ever report itself
    /// empty — which is not worth an entry point.
    private func artifactsAction(for session: SessionSummary) -> (() -> Void)? {
        guard teamArtifacts != nil, session.teamId != nil, hxPresented(session.sessionId) != nil else {
            return nil
        }
        return { artifactsTarget = session }
    }

    /// `nil` for a row the runtime cannot be reached through, which is what makes the card's text column
    /// static rather than a button that does nothing.
    private func chatAction(for session: SessionSummary) -> ((SessionSummary) -> Void)? {
        guard let onOpen, let sessionId = hxPresented(session.sessionId) else { return nil }
        return { row in onOpen(ChatConversation(id: sessionId, title: vm.title(of: row))) }
    }
}

struct SessionRecordCard: View {
    let session: SessionSummary
    let title: String
    let enabled: Bool
    let isBusy: Bool
    let canWrite: Bool
    let onOpen: ((SessionSummary) -> Void)?
    let onDetail: () -> Void
    var onWorkspace: (() -> Void)? = nil
    var onArtifacts: (() -> Void)? = nil
    let onRename: () -> Void
    let onToggle: (Bool) -> Void
    let onClear: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HXCard {
            HStack(alignment: .top, spacing: 12) {
                HXAvatar(name: session.executorName ?? title)
                VStack(alignment: .leading, spacing: 7) {
                    titleRow
                    badgeRow
                    textColumn
                    let byline = RowMeta.byline(creator: session.creator, createTime: session.createTime)
                    if !byline.isEmpty {
                        Text(verbatim: byline)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                }
            }
        }
        .overlay(alignment: .topTrailing) { menu.padding(10) }
    }

    /// The title alone on its row, with the state badges on the row under it. A conversation title is free
    /// text the runtime fills in and wraps to two lines, and badges beside a wrapping paragraph read as if
    /// they were part of its last line.
    private var titleRow: some View {
        Text(verbatim: title)
            .font(.headline)
            .foregroundStyle(Color.hx(.textPrimary))
            .lineLimit(2)
            .multilineTextAlignment(.leading)
            .padding(.trailing, 34)
    }

    @ViewBuilder
    private var badgeRow: some View {
        if !enabled || session.isTeamConversation || session.isShared {
            HStack(spacing: 6) {
                if !enabled {
                    HXBadge("state.badge.disabled", tone: .textTertiary)
                }
                if session.isTeamConversation {
                    HXBadge("chat.badge.team", tone: .indigo)
                }
                if session.isShared {
                    HXChip(hx("state.badge.shared"), tone: .teal)
                }
            }
        }
    }

    /// Everything under the title. Wrapped in one button when the root can open the conversation, because
    /// the card's own menu sits in an overlay and a card-wide tap would swallow it.
    @ViewBuilder
    private var textColumn: some View {
        if let onOpen {
            Button { onOpen(session) } label: { details }
                .buttonStyle(.plain)
        } else {
            details
        }
    }

    @ViewBuilder
    private var details: some View {
        VStack(alignment: .leading, spacing: 7) {
            if let detail = session.detail {
                Text(verbatim: detail)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let executor = session.executorName {
                Text(verbatim: hx("chat.row.executor", executor))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .lineLimit(1)
            }
            if hasChips {
                HXFlow(spacing: 6) { chips }
            }
            // The string key is what the chat window and every runtime route address, so it is the value
            // worth copying off a row; the numeric id is only ever used by the four admin writes.
            if let sessionId = hxPresented(session.sessionId) {
                HXValueText(sessionId)
            }
        }
    }

    private var hasChips: Bool {
        hxPresented(session.modelName) != nil || !session.skillNames.isEmpty || !session.mcpNames.isEmpty
    }

    @ViewBuilder
    private var chips: some View {
        if let model = hxPresented(session.modelName) {
            HXChip(hx("agent.chip.model", model), tone: .brand)
        }
        if !session.skillNames.isEmpty {
            HXChip(hx("agent.chip.skills", session.skillNames.count), tone: .purple)
        }
        if !session.mcpNames.isEmpty {
            HXChip(hx("agent.chip.mcp", session.mcpNames.count))
        }
    }

    private var menu: some View {
        Menu {
            Button(action: onDetail) { HXText("chat.detail.title") }

            if let onWorkspace {
                Button(action: onWorkspace) { HXText("chat.workspace.title") }
            }
            if let onArtifacts {
                Button(action: onArtifacts) { HXText("chat.artifacts.title") }
            }

            Button(action: onRename) { HXText("chat.action.rename") }
                .disabled(!canWrite)

            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            .disabled(!canWrite || isBusy)

            Button(action: onClear) { HXText("chat.action.clear") }
                .disabled(hxPresented(session.sessionId) == nil || isBusy)

            if canWrite {
                Button(role: .destructive, action: onDelete) { HXText("state.action.delete") }
            }
        } label: {
            Image(systemName: isBusy ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
        .accessibilityLabel(hx("state.action.more"))
    }
}

/// The rename sheet. Title only, because `title` is the only display column the route changes — the body's
/// `sessionDescription` reaches a column `updateById` never writes
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt:252-253`,
/// `harnax-entity/src/main/resources/mapper/SessionMapper.xml:86-101`), so a description field here would be
/// a control that cannot write.
public struct SessionRenameSheet: View {
    @ObservedObject var vm: SessionListViewModel
    let session: SessionSummary
    @Environment(\.dismiss) private var dismiss
    @State private var title: String = ""
    @State private var refusal: String?
    @State private var isSaving = false

    public init(vm: SessionListViewModel, session: SessionSummary) {
        self.vm = vm
        self.session = session
    }

    public var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                if let refusal {
                    HXBanner("state.error.title", message: refusal, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                HXField("chat.rename.placeholder", text: $title, systemImage: "textformat")
                Text(verbatim: hx("chat.rename.note"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(16)
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("chat.rename.title")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { HXText("common.cancel") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        submit()
                    } label: {
                        HXText(isSaving ? "chat.rename.saving" : "common.save")
                    }
                    .disabled(isSaving || hxPresented(title) == nil)
                }
            }
        }
        .onAppear { title = vm.title(of: session) }
    }

    private func submit() {
        isSaving = true
        refusal = nil
        Task {
            switch await vm.rename(session, to: title) {
            case .saved:
                dismiss()
            case let .invalid(message), let .failed(message):
                refusal = message
            }
            isSaving = false
        }
    }
}
