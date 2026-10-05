import SwiftUI
import HarnaxCore
import HarnaxKit

/// The one row the review queue hangs off: the top of the conversation list.
///
/// It is a **navigation** row, not a counter row, and that distinction is the whole of its failure handling.
/// - With no `drafts` facade the row is not drawn at all (`specs/07-skill-draft-review.md` §5.1): a host that
///   cannot read the queue would only offer a screen that reports its own inability.
/// - When the count read fails, the number disappears and the row stays. The queue is still reachable and the
///   pending total is the least of the reasons to reach it — pulling down on the conversation list re-reads
///   both, so a number that is wrong right now is not a number that stays wrong.
///
/// The count is the whole tenant's queue, not this conversation's: the endpoint has no session filter at all
/// (`SkillDraftController.kt:55-68`), which is why the copy says「待审」rather than「本会话」.
///
/// The row pushes `SkillDraftQueueRoute` by value and `ChatTabView` answers it, so the facade is the only
/// availability question left: the destination is registered by this row's single host, and a link that has a
/// destination has nothing to be disabled for.
public struct SkillDraftEntryRow: View {
    private let drafts: (any SkillDraftCataloging)?
    /// Bumped by the conversation list's pull-to-refresh. `Equatable` so `.task(id:)` re-runs the read on a
    /// new value and does nothing when the row merely re-renders.
    private let reloadToken: Int

    @State private var pendingCount: Int?

    public init(
        drafts: (any SkillDraftCataloging)?,
        reloadToken: Int = 0
    ) {
        self.drafts = drafts
        self.reloadToken = reloadToken
    }

    /// No facade, no entry: the caller asks before building the row so the list's spacing does not reserve
    /// a slot for something that is not there.
    public static func isAvailable(_ drafts: (any SkillDraftCataloging)?) -> Bool { drafts != nil }

    public var body: some View {
        if let drafts {
            NavigationLink(value: SkillDraftQueueRoute()) {
                HXCard {
                    HStack(spacing: 12) {
                        Image(systemName: "checklist")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(Color.hx(.brand))
                            .frame(width: 26)
                        VStack(alignment: .leading, spacing: 3) {
                            HXText("skill.draft.entry")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color.hx(.textPrimary))
                            Text(verbatim: hx("skill.draft.entry.hint"))
                                .font(.caption)
                                .foregroundStyle(Color.hx(.textSecondary))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 8)
                        if let pendingCount, pendingCount > 0 {
                            HXChip(
                                hx("skill.draft.pendingCount", pendingCount),
                                tone: .warning
                            )
                        }
                        HXChevron()
                    }
                }
            }
            .buttonStyle(.plain)
            .task(id: reloadToken) {
                // The read failure path is `pendingCount = nil`, which is the same value as「nothing pending」
                // and is exactly the point: neither removes the row.
                pendingCount = await drafts.pendingCount()
            }
        }
    }
}
