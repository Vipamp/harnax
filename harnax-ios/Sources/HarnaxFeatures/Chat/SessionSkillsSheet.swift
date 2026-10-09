import SwiftUI
import HarnaxCore
import HarnaxKit

/// The session page's own skill panel, in a sheet: one row per skill this conversation's agent wrote, with the
/// enable action on the rows that are not live yet.
///
/// The console's `harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx` is the reference, and the
/// merge that decides which row says「已启用」is shared with it in Core (`SessionSkillRules.merged`) rather than
/// re-derived here. Two things this screen therefore never does: it does not show a skill the session did not
/// write (the published library is the context tab's job), and it does not offer an enable for a row the
/// directory already answers as enabled. That is not because the route would refuse it — a re-enable answers
/// success (`SessionSkillStore.kt:125`, `:144`), and is refused only when the sandbox has stopped or the draft
/// has been archived (`:103`, `:106`) — it is because it gets there by `rm -rf`ing the directory the session is
/// using and re-copying whatever the draft holds at that moment (`:128-131`). The row is already live; the
/// button would swap the bytes under it and still report nothing changed.
///
/// The panel is opened, not polled: both reads are asked when the sheet appears and again by the refresh row
/// below, never by the conversation's own lifecycle (`ChatView`'s `.task(id: conversation)` stays untouched).
public struct SessionSkillsSheet: View {
    @StateObject private var vm: SessionSkillsViewModel

    public init(reading: (any SessionSkillReading)?, sessionId: String) {
        _vm = StateObject(wrappedValue: SessionSkillsViewModel(reading: reading, sessionId: sessionId))
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    content
                    // Under the rows rather than above them: the sentence names one row, and a reader has to
                    // still be looking at that row to know which.
                    refusals
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("chat.skills.title")))
            .toolbar {
                // `.primaryAction` is the only placement the macOS test host accepts.
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await vm.refresh() }
                    } label: {
                        HXText("common.refresh")
                    }
                }
            }
        }
        .task { await vm.refresh() }
        .refreshable { await vm.refresh() }
    }

    @ViewBuilder
    private var content: some View {
        if vm.isLoading, vm.rows.isEmpty {
            HXStateView(.loading)
        } else if vm.rows.isEmpty {
            // Two different sentences, and telling them apart is the whole of this screen's honesty: a queue
            // that would not load is not a conversation whose agent wrote nothing.
            if vm.unavailable {
                HXStateView(.error, message: hx("chat.skills.loadFailed")) {
                    Task { await vm.refresh() }
                }
            } else {
                HXStateView(.empty, message: hx("chat.skills.empty")) {
                    Task { await vm.refresh() }
                }
            }
        } else {
            rows
        }
    }

    /// One row per name, in the order the merge answers it — the queue's newest proposal first, then the
    /// directory's leftovers. The two glyphs are the two this app already uses for the same two facts: the draft
    /// queue's own checklist (`SkillDraftEntryRow.swift`) for a proposal nobody has acted on, and the seal it
    /// puts on a decision somebody did agree to (`ChatTranscriptRows.swift:419`). A row's state therefore reads
    /// here the way it reads on the reviewer's screens.
    private var rows: some View {
        HXGroupCard {
            ForEach(vm.rows) { row in
                HXRow(
                    text: row.name,
                    subtitle: row.description,
                    systemImage: row.enabled ? "checkmark.seal" : "checklist",
                    tone: row.enabled ? .success : .brand,
                    divider: row.id != vm.rows.last?.id
                ) {
                    trailing(row)
                }
            }
        }
    }

    /// The action, or the state that replaced it. An enabled row shows no button: its enable has already happened,
    /// and the route on that name only re-copies the draft over the live directory (see the type's note above),
    /// so there is no state left for it to advance.
    ///
    /// While a write is running every pending row's button goes off — the model drops a second tap
    /// (`SessionSkillsViewModel.enable`), and a button that ignores the finger it accepted is worse than a greyed
    /// one — and the row actually being copied shows the spinner, the same per-row answer the console's drawer
    /// gives with `loading={busyName === entry.name}`. The spinner sits beside the label rather than replacing
    /// it, so the row still says what it is busy with (`McpDetailView.swift:232-241` puts one in a row's
    /// trailing slot for the same reason).
    @ViewBuilder
    private func trailing(_ row: SessionSkillRow) -> some View {
        if row.enabled {
            HXBadge("chat.skills.enabled", tone: .success)
        } else {
            HStack(spacing: 6) {
                Button {
                    Task { await vm.enable(name: row.name) }
                } label: {
                    HXText("chat.skills.enable")
                }
                .buttonStyle(.hxInline)
                .disabled(vm.isActing)
                if vm.actingName == row.name {
                    ProgressView()
                        .controlSize(.small)
                        .tint(Color.hx(.brand))
                }
            }
        }
    }

    /// Why the last enable was refused — each of the five causes with its own sentence, and a code nobody
    /// documented with the plain one (`SessionSkillRefusal.messageKey`).
    ///
    /// A refused re-read beside rows that are still on screen says so here too: the list it did not replace is
    /// the list the reader is looking at, and it is now a statement about the last answer that did arrive.
    @ViewBuilder
    private var refusals: some View {
        if let notice = vm.notice {
            HXBanner(
                "state.error.title",
                message: hx(notice.messageKey),
                systemImage: "exclamationmark.triangle",
                tone: .danger
            )
        }
        if vm.unavailable, !vm.rows.isEmpty {
            HXBanner(
                "state.error.title",
                message: hx("chat.skills.loadFailed"),
                systemImage: "exclamationmark.triangle",
                tone: .danger
            )
        }
    }
}
