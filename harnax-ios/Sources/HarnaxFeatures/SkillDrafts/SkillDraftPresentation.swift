import SwiftUI
import HarnaxCore
import HarnaxKit

/// The address of one draft, as the queue hands it to the stack.
///
/// `SkillDraftRow.id` is nullable (`SkillDraftResponse.kt:16-20`), and a by-value push needs something to
/// push: unwrapping the id into this route is what turns「a row with no address is not tappable」from a dead
/// control into a type error. Same rule `SkillTableView.SkillDetailRoute` documents
/// (`HarnaxFeatures/Skills/SkillTableView.swift:75-86`).
public struct SkillDraftRef: Identifiable, Hashable, Sendable {
    public let id: Int64

    public init(id: Int64) { self.id = id }

    /// The route a row offers, or `nil` when the row carries no id to send.
    public static func forRow(_ row: SkillDraftRow) -> SkillDraftRef? { row.id.map(SkillDraftRef.init(id:)) }
}

/// The queue's own push value, carried by the session list's entry row.
///
/// A value rather than a `Bool` flag on the chat tab, because the queue is not a leaf: its rows push a detail
/// one level deeper. An item- or `isPresented`-presented screen is re-evaluated by the path change its own
/// deeper push causes, and while the binding still reads true it gets pushed a second time on top of the
/// screen it just opened — the defect `SkillNavigationTests` records for 2026-10-03, where tapping a skill left
/// a second copy of the table over the detail. Pushing by value puts this flow on the same shape as
/// `ModelProviderListView` and the skill flow: value at every level.
public struct SkillDraftQueueRoute: Hashable, Sendable {
    public init() {}
}

/// Which of the six panes the detail screen shows.
///
/// The console's own split (`harnax-webui/src/pages/skill/draftDetail.tsx:275-333`), with the trail pulled out
/// of the origin tab into a pane of its own: `specs/07-skill-draft-review.md` §5.3 lists six, and a trail
/// buried under a second heading inside another pane is the one thing a reviewer forgets to read.
public enum SkillDraftTab: Int, CaseIterable, Identifiable, Sendable {
    case body
    case files
    case scripts
    case scans
    case source
    case history

    public var id: Int { rawValue }

    public var titleKey: String {
        switch self {
        case .body: return "skill.draft.tab.body"
        case .files: return "skill.draft.tab.files"
        case .scripts: return "skill.draft.tab.scripts"
        case .scans: return "skill.draft.tab.scans"
        case .source: return "skill.draft.tab.source"
        case .history: return "skill.draft.tab.history"
        }
    }
}

/// The tone of a finished decision, so the sheet that reports it can be coloured without the view re-deriving
/// which outcome means what.
public enum SkillDraftNoticeTone: Sendable {
    case success
    case warning
    case info
    case error

    var slot: PaletteSlot {
        switch self {
        case .success: .success
        case .warning: .warning
        case .info: .brand
        case .error: .danger
        }
    }
}

/// What a review action ended in, reduced to the one thing each outcome owes the screen.
///
/// Carries values rather than finished sentences: `PROMOTED` needs the name and the hit count, `ALREADY_REVIEWED`
/// needs who and when, `DRAFT_CHANGED` needs the digest the next attempt has to send. That keeps the six rows of
/// `specs/07-skill-draft-review.md` §5.4 assertable without a view.
public enum SkillDraftNotice: Equatable, Sendable {
    /// `PROMOTED` with `skillStatus == 1`: the row is written *and* live.
    case promotedEnabled(name: String, skillID: Int64?, canOpenSkill: Bool)
    /// `PROMOTED` with anything else — 0 or absent. The scan held it back; `findings` is `decision.findings.count`.
    case promotedHeldBack(name: String, findings: Int, skillID: Int64?, canOpenSkill: Bool)
    /// The agent patched after this screen loaded the draft. `digest` is the new one, copied, not retyped.
    case draftChanged(digest: String?)
    /// Somebody else closed it first. Both halves default to the console's own `-` (`draftDetail.tsx:138-139`).
    case alreadyReviewed(by: String, at: String, reason: String?)
    /// The rejection landed — the `REJECTED` outcome, which is a success for the reject button.
    case rejectionRecorded
    /// Envelope `code: 409` on HTTP 200: the name was taken mid-approval, the write rolled back and the draft
    /// is still pending.
    case nameRace
    /// Anything the screen cannot act on, already rendered — the server's sentence, or the local fallback.
    case refused(message: String)

    public var tone: SkillDraftNoticeTone {
        switch self {
        case .promotedEnabled, .rejectionRecorded: .success
        case .promotedHeldBack, .draftChanged, .nameRace: .warning
        case .alreadyReviewed: .info
        case .refused: .error
        }
    }

    /// The row the sheet pushes when the reviewer takes the「打开技能」offer. `nil` for every outcome that has
    /// no skill behind it, and for a host that carries no skill facade.
    public var openableSkillID: Int64? {
        guard canOpenSkill else { return nil }
        switch self {
        case let .promotedEnabled(_, skillID, _), let .promotedHeldBack(_, _, skillID, _): return skillID
        default: return nil
        }
    }

    private var canOpenSkill: Bool {
        switch self {
        case let .promotedEnabled(_, _, allowed), let .promotedHeldBack(_, _, _, allowed): return allowed
        default: return false
        }
    }
}

/// One name collision, as the dialog has to show it.
///
/// `name` is the name **this attempt asked to land** — for a rename that is the `newName` just typed, not the
/// draft's title (`draftDetail.tsx:179-184`), because that is the name the repository said no to.
public struct SkillDraftConflict: Equatable, Sendable {
    public let name: String
    /// The row already holding that name, when the service could name it (`draftDetail.tsx:501-510`).
    public let skillID: Int64?

    public init(name: String, skillID: Int64?) {
        self.name = name
        self.skillID = skillID
    }
}

/// The queue's and the detail screen's shared read of a row: stamps, labels, colours.
///
/// Slicing rather than re-formatting: a `DateFormatter` round trip would drag the device's locale and calendar
/// into a table that has to read identically in both languages. So the stamp is cut at its own separator, and
/// both serialisations admin answers with — the space form and the `T` form — are accepted, which is what
/// `Support/RowMeta.swift` establishes for every other screen's dates. Anything unreadable keeps its own text
/// rather than being guessed at — a wrong timestamp on a review screen is worse than a raw one.
public enum SkillDraftCopy {
    /// The console's empty cell (`drafts.tsx:86,155`).
    public static let dash = "-"

    /// The empty cell for a value the server left out. `ALREADY_REVIEWED` prints「who decided」even when the
    /// row carries no `reviewedBy`, because a blank there reads as no decision at all
    /// (`draftDetail.tsx:138-139`).
    public static func dashOr(_ raw: String?) -> String { hxPresented(raw) ?? dash }

    /// `2026-10-05 09:12:33` → `2026-10-05 09:12`, in either serialisation the stack answers with.
    public static func stamp(_ raw: String?) -> String {
        guard let text = hxPresented(raw) else { return dash }
        guard let (date, time) = split(text) else { return text }
        guard let hourMinute = hhmm(time) else { return text }
        return "\(date) \(hourMinute)"
    }

    /// `2026-10-05 09:12:33` → `10-05 09:12`: the decided-by column drops the year the same way the row
    /// bylines elsewhere in the app do (`harnax-webui/src/pages/skill/drafts.tsx:152`, `Support/RowMeta.swift:19-27`).
    public static func shortStamp(_ raw: String?) -> String {
        guard let text = hxPresented(raw), let (_, time) = split(text), let hourMinute = hhmm(time) else {
            return dash
        }
        guard let monthDay = hxMonthDay(text) else { return text }
        return "\(monthDay) \(hourMinute)"
    }

    /// The catalogue key of a status the contract has, or `nil` for anything else ever written in that column.
    public static func statusKey(_ status: String?) -> String? {
        guard let status, let arm = SkillDraftStatus(rawValue: status) else { return nil }
        return arm.titleKey
    }

    /// `PENDING` orange, `APPROVED` green, `REJECTED` red (`drafts.tsx:10-14`); anything else reads neutral
    /// rather than borrowing one of those three meanings.
    public static func statusTone(_ status: String?) -> PaletteSlot {
        switch status {
        case SkillDraftStatus.pending.rawValue: .warning
        case SkillDraftStatus.approved.rawValue: .success
        case SkillDraftStatus.rejected.rawValue: .danger
        default: .textTertiary
        }
    }

    /// The verdict tag is the server's own word, kept verbatim (`drafts.tsx:128`) — but the three values it
    /// echoes back (`SkillDraftServiceImpl.kt:569`) each get their own colour, and an unrecognised fourth reads
    /// neutral instead of claiming a verdict.
    public static func verdictTone(_ verdict: String?) -> PaletteSlot {
        switch verdict {
        case "SAFE": .success
        case "CAUTION": .warning
        case "DANGEROUS": .danger
        default: .textSecondary
        }
    }

    private static func split(_ text: String) -> (String, String)? {
        let parts = text.split(whereSeparator: { $0 == " " || $0 == "T" || $0 == "." })
        guard parts.count >= 2 else { return nil }
        return (String(parts[0]), String(parts[1]))
    }

    private static func hhmm(_ time: String) -> String? {
        let parts = time.split(separator: ":")
        guard parts.count >= 2, parts[0].count <= 2, parts[1].count == 2 else { return nil }
        return "\(parts[0]):\(parts[1])"
    }
}

/// The status pill.
///
/// Lives here rather than in each screen because the queue row, the detail header and the decided banner all
/// have to say the same word in the same colour — and none of them may echo a catalogue key when the column
/// holds something other than the three arms.
public struct SkillDraftStatusBadge: View {
    private let status: String?

    public init(status: String?) {
        self.status = status
    }

    public var body: some View {
        if let key = SkillDraftCopy.statusKey(status) {
            HXBadge(key, tone: SkillDraftCopy.statusTone(status))
        } else if let text = hxPresented(status) {
            HXChip(text, tone: .textTertiary)
        } else {
            HXChip(SkillDraftCopy.dash)
        }
    }
}

/// A monospaced value with the full text one tap away.
///
/// The console uses `Typography.Text copyable` for a digest and a script hash
/// (`draftDetail.tsx:169-171`, `:582`): the window shown is truncated by character count, and the copy hands
/// back what was never visible. `HXValueText` truncates by width instead
/// (`HarnaxKit/Components/HXValue.swift:6-15`) and keeps the whole value in its context menu, so this adds the
/// visible button a reviewer on a phone would otherwise have to discover by long press.
public struct SkillDraftHash: View {
    private let label: String
    private let full: String

    public init(label: String, full: String) {
        self.label = label
        self.full = full
    }

    public var body: some View {
        HStack(spacing: 8) {
            HXValueText(label)
            Button {
                HXPasteboard.copy(full)
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.brand))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: hx("common.copy")))
            Spacer(minLength: 0)
        }
    }
}

/// A finished decision, said out loud.
///
/// The sentence is assembled here rather than in the screen so the six rows of
/// `specs/07-skill-draft-review.md` §5.4 have exactly one place that decides what each outcome reads as, and a
/// test can read the payload off `SkillDraftNotice` without building a view.
///
/// Two things this deliberately does *not* do: it never shows a `reason` from the server where it has its own
/// sentence (`SkillDraftDecisionResponse.kt:35-36` is explicit that the field is not a contract), and the
///「打开技能」offer only appears when the caller hands it a route — a host without the skill facade gets the same
/// sentence with no dead button (§5.4 末行).
public struct SkillDraftNoticeView: View {
    private let notice: SkillDraftNotice
    private let openRoute: SkillTableView.SkillDetailRoute?

    public init(notice: SkillDraftNotice, openRoute: SkillTableView.SkillDetailRoute? = nil) {
        self.notice = notice
        self.openRoute = openRoute
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXBanner(titleKey, message: message, systemImage: systemImage, tone: notice.tone.slot)
            if case let .draftChanged(digest) = notice, let digest = hxPresented(digest) {
                SkillDraftHash(
                    label: SkillDraftRules.truncated(digest, digits: 16),
                    full: digest
                )
            }
            if let openRoute {
                NavigationLink(value: openRoute) {
                    HXText("skill.draft.promoted.openSkill")
                }
            }
        }
    }

    private var titleKey: String {
        switch notice {
        case .promotedEnabled: "skill.draft.outcome.promoted.title"
        case .promotedHeldBack: "skill.draft.outcome.held.title"
        case .draftChanged: "skill.draft.outcome.changed.title"
        case .alreadyReviewed: "skill.draft.outcome.reviewed.title"
        case .rejectionRecorded: "skill.draft.outcome.rejected.title"
        case .nameRace: "skill.draft.outcome.race.title"
        case .refused: "state.error.title"
        }
    }

    private var message: String {
        switch notice {
        case let .promotedEnabled(name, _, _):
            return hx("skill.draft.promoted.enabled", name)
        case let .promotedHeldBack(name, findings, _, _):
            return hx("skill.draft.promoted.held", name, findings)
        case .draftChanged:
            return hx("skill.draft.changed.body")
        case let .alreadyReviewed(by, at, reason):
            guard let reason = hxPresented(reason) else { return hx("skill.draft.reviewed.body", by, at) }
            return hx("skill.draft.reviewed.body", by, at) + "\n" + hx("skill.draft.reviewed.reason", reason)
        case .rejectionRecorded:
            return hx("skill.draft.reject.done")
        case .nameRace:
            return hx("skill.draft.nameRace")
        case let .refused(text):
            return text
        }
    }

    private var systemImage: String {
        switch notice {
        case .promotedEnabled: "checkmark.seal"
        case .promotedHeldBack, .nameRace: "exclamationmark.triangle"
        case .draftChanged: "arrow.triangle.2.circlepath"
        case .alreadyReviewed: "person.crop.circle.badge.checkmark"
        case .rejectionRecorded: "xmark.seal"
        case .refused: "exclamationmark.triangle"
        }
    }
}
