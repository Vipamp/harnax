import SwiftUI
import HarnaxCore
import HarnaxKit

/// Which save opened the panel. The intro sentence has to name the object that actually changed, so the
/// three sources keep three sentences.
///
/// Mirror: `harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:141-167`
public enum SessionRefreshSource: Sendable {
    case agent
    case cli
    case team

    var hintKey: String {
        switch self {
        case .agent: return "session.hint.agent"
        case .cli: return "session.hint.cli"
        case .team: return "session.hint.team"
        }
    }
}

/// What the panel is about: the entity's own name for the intro sentence, and the read that lists its
/// sessions.
///
/// The loader is a closure rather than a facade so the three domains can hand in their own endpoint
/// (`/agents/{id}/related-sessions`, `/teams/{id}/related-sessions`, `/clis/{id}/related-sessions`) without
/// this component importing all three protocols.
public struct SessionRefreshTarget: Identifiable, Sendable {
    public let id: Int64
    public let name: String
    public let source: SessionRefreshSource
    public let load: @Sendable () async -> Result<[RelatedSession], APIError>

    public init(
        id: Int64,
        name: String,
        source: SessionRefreshSource,
        load: @escaping @Sendable () async -> Result<[RelatedSession], APIError>
    ) {
        self.id = id
        self.name = name
        self.source = source
        self.load = load
    }
}

/// A refresh is a per-session verdict: the endpoint answers `200` with one line per session, so a batch
/// where three of five failed is not a success the panel may report as clean.
@MainActor
public final class SessionRefreshModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        /// The list answered with nothing — "no session holds this configuration", which says the opposite
        /// of the failure below and must not share its copy.
        case empty
        case ready
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var rows: [RelatedSession] = []
    @Published public private(set) var selected: Set<String> = []
    /// Filled by the submit; a row the stack refused keeps its own reason on screen.
    @Published public private(set) var outcomes: [String: SessionRefreshOutcome] = [:]
    @Published public private(set) var isSubmitting = false
    /// The call as a whole failed — a non-200 envelope — as opposed to individual rows inside a `200`.
    @Published public private(set) var submitError: String?
    /// Set once every selected session took the new configuration, or once the operator skipped; the sheet
    /// dismisses on it.
    @Published public private(set) var isFinished = false

    private let target: SessionRefreshTarget
    private let refresher: any SessionRefreshing

    public init(target: SessionRefreshTarget, refresher: any SessionRefreshing) {
        self.target = target
        self.refresher = refresher
    }

    public var selectedCount: Int { selected.count }
    public var subjectName: String { target.name }
    public var hintKey: String { target.source.hintKey }

    /// Everything arrives checked: a session keeps its stale snapshot until told otherwise, so an operator
    /// who means "refresh it all" should not have to tick each row first.
    public func load() async {
        phase = .loading
        outcomes = [:]
        submitError = nil
        switch await target.load() {
        case let .success(sessions):
            rows = sessions
            selected = Set(sessions.map(\.id))
            phase = sessions.isEmpty ? .empty : .ready
        case let .failure(error):
            phase = .failed(ErrorMessage.text(for: error))
        }
    }

    public func toggle(_ session: RelatedSession) {
        guard outcomes.isEmpty else { return }
        if selected.contains(session.id) {
            selected.remove(session.id)
        } else {
            selected.insert(session.id)
        }
    }

    /// Nothing ticked means "leave the cached snapshots alone" — a skip, and no request.
    public func submit() async {
        guard !selected.isEmpty else {
            isFinished = true
            return
        }
        isSubmitting = true
        defer { isSubmitting = false }
        let ids = rows.filter { selected.contains($0.id) }.map(\.id)
        switch await refresher.refreshSessions(ids) {
        case let .success(lines):
            outcomes = Dictionary(lines.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            if outcomes.values.contains(where: \.failed) == false { isFinished = true }
        case let .failure(error):
            submitError = ErrorMessage.text(for: error)
        }
    }

    /// With nothing ticked the primary action is a skip, not a refresh of zero sessions. The two labels take
    /// a different number of arguments, so the model renders the text.
    public var primaryLabel: String {
        selected.isEmpty ? hx("session.skip") : hx("session.submit", selectedCount)
    }

    public func outcome(for session: RelatedSession) -> SessionRefreshOutcome? { outcomes[session.id] }

    /// `Refreshed n` when the batch is clean, `n ok / m failed` when it is not. The two sentences take a
    /// different number of arguments, so the model renders the text rather than handing out a key.
    /// (`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:88-105`)
    public var summaryText: String? {
        guard !outcomes.isEmpty else { return nil }
        return failedCount == 0
            ? hx("session.outcome.all", succeededCount)
            : hx("session.outcome.partial", succeededCount, failedCount)
    }

    public var failedCount: Int { outcomes.values.filter(\.failed).count }
    public var succeededCount: Int { outcomes.count - failedCount }
}

/// C4 — the panel a saved agent, a saved team and a disabled CLI all reuse.
public struct HXSessionRefreshSheet: View {
    @StateObject private var model: SessionRefreshModel
    @Environment(\.dismiss) private var dismiss

    public init(target: SessionRefreshTarget, refresher: any SessionRefreshing) {
        _model = StateObject(wrappedValue: SessionRefreshModel(target: target, refresher: refresher))
    }

    public var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HXText(model.hintKey, model.subjectName)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                    if let error = model.submitError {
                        HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                    }
                    list
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            footer
        }
        .background(Color.hx(.background))
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task { await model.load() }
        .onChange(of: model.isFinished) {
            if model.isFinished { dismiss() }
        }
    }

    private var header: some View {
        HStack {
            HXText("session.title")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
            Spacer(minLength: 0)
            Button { dismiss() } label: { HXText("common.cancel") }
                .buttonStyle(.hxInline)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private var list: some View {
        switch model.phase {
        case .loading:
            HXStateView(.loading)
        case let .failed(message):
            // A failed read is its own state; rendering it as the empty list would claim that nothing
            // depends on this configuration.
            HXStateView(.error, message: message, retry: { Task { await model.load() } })
        case .empty:
            HXStateView(.empty, message: hx("session.empty"))
        case .ready:
            VStack(spacing: 0) {
                ForEach(model.rows) { session in
                    row(session)
                }
            }
            .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    private func row(_ session: RelatedSession) -> some View {
        Button { model.toggle(session) } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: model.selected.contains(session.id) ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(Color.hx(model.selected.contains(session.id) ? .brand : .textTertiary))
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        HXChip(
                            hx(session.isChannel ? "session.type.channel" : "session.type.session"),
                            tone: session.isChannel ? .brand : .purple
                        )
                        Text(verbatim: session.displayName)
                            .font(.subheadline)
                            .foregroundStyle(Color.hx(.textPrimary))
                            .lineLimit(1)
                        if let owner = session.ownerName {
                            HXChip(owner, tone: .indigo)
                        }
                    }
                    Text(verbatim: Self.shortID(session.id))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                    if let reason = model.outcome(for: session)?.reason {
                        HXBanner("session.outcome.failed", message: reason, systemImage: "xmark.circle", tone: .danger)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!model.outcomes.isEmpty)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            if let summary = model.summaryText {
                Text(verbatim: summary)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
            }
            Button {
                Task { await model.submit() }
            } label: {
                Text(verbatim: model.primaryLabel)
            }
            .buttonStyle(.hxPrimary)
            .disabled(model.isSubmitting || model.phase == .loading)
            Button { dismiss() } label: {
                HXText("session.later")
            }
            .buttonStyle(.hxSecondary)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    /// The console cuts the UUID at 24 characters so the row stays one line
    /// (`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:211-213`).
    private static func shortID(_ id: String) -> String {
        id.count > 24 ? "\(id.prefix(24))…" : id
    }
}
