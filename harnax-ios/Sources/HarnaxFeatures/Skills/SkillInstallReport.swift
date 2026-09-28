import Foundation
import HarnaxCore
import HarnaxKit

/// How loud an install answer has to be.
public enum SkillInstallTone: Equatable, Sendable {
    case success
    case warning
    case error

    public var slot: PaletteSlot {
        switch self {
        case .success: .success
        case .warning: .warning
        case .error: .danger
        }
    }
}

/// One line inside a bucket's expanded detail.
public struct SkillInstallLine: Equatable, Identifiable, Sendable {
    public let name: String
    /// The scan or persistence reason; only failures and flags have one.
    public let reason: String?

    public var id: String { name }
}

/// One bucket of the report: a section that sums itself up in a line and expands to its rows
/// (`RepositoryList.tsx:153-183` renders exactly these five partitions).
public struct SkillInstallBucket: Equatable, Identifiable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case installed
        case updated
        case flagged
        case failed
        case stale

        public var titleKey: String { "skill.bucket.\(rawValue)" }
    }

    public let kind: Kind
    public let lines: [SkillInstallLine]

    public var id: String { kind.rawValue }
    public var count: Int { lines.count }
}

/// The graded reading of a `SkillInstallResponse`.
///
/// Backend contract this exists for: the endpoint answers `200` for a run where every single skill failed,
/// because per-skill failures used to be swallowed by a log line (`SkillInstallResponse.kt:5-10`). The
/// priority order below is the console's (`harnax-webui/src/utils/skillInstall.ts:32-129`), ported as-is:
/// the first matching rule wins, and a zero count is never allowed to report as green.
public struct SkillInstallReport: Equatable, Sendable {
    public let tone: SkillInstallTone
    /// Already rendered: the branches take a different number of arguments, so handing out a key would push
    /// the arity mismatch into the view (`HARNESS-NOTES.md` "文案落点").
    public let title: String
    /// Server-side sentence attached to a source-level break; `nil` when the report is all about counts.
    public let message: String?
    public let buckets: [SkillInstallBucket]

    public init(tone: SkillInstallTone, title: String, message: String? = nil, buckets: [SkillInstallBucket]) {
        self.tone = tone
        self.title = title
        self.message = message
        self.buckets = buckets
    }

    public var savedCount: Int {
        (bucket(.installed)?.count ?? 0) + (bucket(.updated)?.count ?? 0)
    }

    public func bucket(_ kind: SkillInstallBucket.Kind) -> SkillInstallBucket? {
        buckets.first { $0.kind == kind }
    }

    public var hasBuckets: Bool { !buckets.isEmpty }

    /// A clean run is one the modal may close on: nothing failed, nothing was flagged, the source opened,
    /// and at least one skill actually landed. A stale list alone is still worth reading before it dismisses.
    public var isClean: Bool {
        tone == .success && bucket(.flagged) == nil && bucket(.stale) == nil
    }

    /// `nil` is its own branch and not a zero: a response that carried no install half at all says the
    /// write happened, and nothing more (`skillInstall.ts:39-46`).
    public static func describe(_ install: SkillInstallOutcome?) -> SkillInstallReport {
        let buckets = buckets(of: install)
        guard let install else {
            return SkillInstallReport(tone: .success, title: hx("skill.install.unknown"), buckets: buckets)
        }
        let failed = install.failedCount
        let saved = install.savedCount

        if failed > 0, saved == 0 {
            return SkillInstallReport(tone: .error, title: hx("skill.install.noneSaved", failed), buckets: buckets)
        }
        if failed > 0 {
            return SkillInstallReport(tone: .warning, title: hx("skill.install.partial", saved, failed), buckets: buckets)
        }
        if !install.flagged.isEmpty {
            return SkillInstallReport(
                tone: .warning,
                title: hx("skill.install.flagged", install.flagged.count),
                buckets: buckets
            )
        }
        if let sourceError = hxPresented(install.sourceError) {
            return SkillInstallReport(tone: .error, title: hx("skill.install.sourceError"), message: sourceError, buckets: buckets)
        }
        if let emptyReason = hxPresented(install.emptyReason) {
            return SkillInstallReport(tone: .warning, title: hx("skill.install.empty"), message: emptyReason, buckets: buckets)
        }
        if saved == 0 {
            return SkillInstallReport(tone: .warning, title: hx("skill.install.nothing"), buckets: buckets)
        }
        return SkillInstallReport(tone: .success, title: hx("skill.install.saved", saved), buckets: buckets)
    }

    /// Empty buckets are dropped rather than rendered as "0" lines, and `stale` keeps its own section even on
    /// an otherwise green run — it is reported, never deleted (`SkillInstallResponse.kt:31-32`).
    static func buckets(of install: SkillInstallOutcome?) -> [SkillInstallBucket] {
        guard let install else { return [] }
        var out: [SkillInstallBucket] = []
        if !install.installed.isEmpty {
            out.append(SkillInstallBucket(kind: .installed, lines: install.installed.map { SkillInstallLine(name: $0, reason: nil) }))
        }
        if !install.updated.isEmpty {
            out.append(SkillInstallBucket(kind: .updated, lines: install.updated.map { SkillInstallLine(name: $0, reason: nil) }))
        }
        if !install.flagged.isEmpty {
            out.append(SkillInstallBucket(
                kind: .flagged,
                // `reasons` is a list on the wire; one file can trip several scan rules at once.
                lines: install.flagged.map {
                    SkillInstallLine(name: $0.name, reason: $0.reasons.isEmpty ? nil : $0.reasons.joined(separator: " · "))
                }
            ))
        }
        if !install.failed.isEmpty {
            out.append(SkillInstallBucket(
                kind: .failed,
                lines: install.failed.map { SkillInstallLine(name: $0.name, reason: hxPresented($0.reason)) }
            ))
        }
        if !install.stale.isEmpty {
            out.append(SkillInstallBucket(kind: .stale, lines: install.stale.map { SkillInstallLine(name: $0, reason: nil) }))
        }
        return out
    }
}

/// The same five partitions read back off a source's stored report, so the row can say what its last sync
/// did without a fresh install. `lastSyncDetail` uses `saved` and a merged `error` instead of the response's
/// three fields (`SkillSyncRecorder.kt:52-64`).
public enum SkillSyncDetailReader {
    public static func buckets(of detail: SkillSyncDetail) -> [SkillInstallBucket] {
        let installed = detail.installed ?? []
        let updated = detail.updated ?? []
        let stale = detail.stale ?? []
        var out: [SkillInstallBucket] = []
        if !installed.isEmpty {
            out.append(SkillInstallBucket(kind: .installed, lines: installed.map { SkillInstallLine(name: $0, reason: nil) }))
        }
        if !updated.isEmpty {
            out.append(SkillInstallBucket(kind: .updated, lines: updated.map { SkillInstallLine(name: $0, reason: nil) }))
        }
        out += SkillInstallReport.buckets(of: SkillInstallOutcome(
            installed: [],
            updated: [],
            failed: detail.failed ?? [],
            flagged: detail.flagged ?? [],
            sourceError: nil,
            emptyReason: nil,
            stale: []
        )).filter { $0.kind == .flagged || $0.kind == .failed }
        if !stale.isEmpty {
            out.append(SkillInstallBucket(kind: .stale, lines: stale.map { SkillInstallLine(name: $0, reason: nil) }))
        }
        return out
    }

    /// The badge copy: the four states the recorder writes, and nothing else.
    public static func statusKey(for status: String?) -> String? {
        switch status?.uppercased() {
        case "SUCCESS": return "skill.sync.status.success"
        case "PARTIAL": return "skill.sync.status.partial"
        case "FAILED": return "skill.sync.status.failed"
        case "EMPTY": return "skill.sync.status.empty"
        default: return nil
        }
    }

    public static func slot(for status: String?) -> PaletteSlot {
        switch status?.uppercased() {
        case "SUCCESS": .success
        case "PARTIAL": .warning
        case "FAILED": .danger
        default: .textTertiary
        }
    }
}
