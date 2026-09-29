import SwiftUI
import HarnaxCore
import HarnaxKit

/// D4 — what a registered package actually declared in its `plugin.yaml`.
///
/// Three of these fields exist nowhere else on the client: the page shape answers without `payloadDigest`,
/// `depsApt` and `runtimeEnv` because the server drops nulls from the envelope, so this sheet is the only
/// reader of `GET /api/admin/clis/{id}` (`harnax-webui/src/pages/cli/components/CliDetailDrawer.tsx:21-24`).
/// That is also why it loads its own copy of the row instead of rendering what the list handed over.
///
/// The webui lays these out as one long description table. On a phone the same fields split into the three
/// groups the package really has — its fingerprints, the apt packages it needs, and the environment it
/// expects at container creation — with the plain identity fields above them.
enum CliDetailPresenter {
    static func sections(for cli: CliSummary) -> [HXBindingSection] {
        [
            HXBindingSection(titleKey: "cli.section.overview", rows: overview(cli)),
            HXBindingSection(titleKey: "cli.section.fingerprint", rows: fingerprints(cli)),
            HXBindingSection(titleKey: "cli.section.apt", rows: [
                HXBindingRow(
                    title: hx("cli.field.depsApt"),
                    subtitle: cli.aptDependencies.isEmpty ? none() : nil,
                    badges: cli.aptDependencies
                ),
            ]),
            HXBindingSection(titleKey: "cli.section.runtime", rows: runtime(cli)),
        ]
    }

    private static func overview(_ cli: CliSummary) -> [HXBindingRow] {
        var rows = [
            HXBindingRow(title: hx("cli.field.version"), subtitle: cli.version ?? none(), badges: []),
            HXBindingRow(title: hx("cli.field.description"), subtitle: hxPresented(cli.description) ?? none(), badges: []),
            HXBindingRow(
                title: hx("cli.field.status"),
                subtitle: nil,
                badges: [hx(cli.isEnabled ? "state.badge.enabled" : "state.badge.disabled")]
            ),
        ]
        // The shipped skill is the only skill a CLI ever has, so a package with no `skill` key says so
        // rather than dropping the row and leaving the operator to wonder where it went.
        rows.append(HXBindingRow(
            title: hx("cli.field.skill"),
            subtitle: cli.skill?.detail,
            badges: cli.shippedSkillName.map { [$0] } ?? [none()]
        ))
        rows.append(HXBindingRow(title: hx("cli.field.createTime"), subtitle: hxPresented(cli.createTime) ?? none(), badges: []))
        return rows
    }

    /// The two digests, each with the short form the row shows as a mark and the full value under the label:
    /// a 64-character hash is only comparable in full, and the second one is what the sandbox image was
    /// built from.
    private static func fingerprints(_ cli: CliSummary) -> [HXBindingRow] {
        [
            HXBindingRow(
                title: hx("cli.field.packageDigest"),
                subtitle: hxPresented(cli.packageDigest) ?? none(),
                badges: cli.packageDigestAbbrev.map { [$0] } ?? []
            ),
            HXBindingRow(
                title: hx("cli.field.payloadDigest"),
                subtitle: hxPresented(cli.payloadDigest) ?? none(),
                badges: cli.payloadDigestAbbrev.map { [$0] } ?? []
            ),
        ]
    }

    /// Health check, the declared parameter keys, then one line per runtime slot.
    ///
    /// `envParams` are declarations the operator fills per binding, while `runtimeEnv` is what the platform
    /// injects at container creation — the two look alike and mean different things, so the slots keep their
    /// own rows (`key = value`, as the drawer renders them) instead of merging into the chip flow.
    private static func runtime(_ cli: CliSummary) -> [HXBindingRow] {
        var rows = [
            HXBindingRow(
                title: hx("cli.field.checkCommand"),
                subtitle: hxPresented(cli.checkCommand) ?? none(),
                badges: []
            ),
            HXBindingRow(
                title: hx("cli.field.envParams"),
                subtitle: cli.envParamEntries.isEmpty ? none() : nil,
                badges: cli.envParamEntries.map(\.envParamName)
            ),
        ]
        rows.append(contentsOf: cli.runtimeEnvEntries.map { entry in
            HXBindingRow(title: entry.key, subtitle: entry.value, badges: [])
        })
        if cli.runtimeEnvEntries.isEmpty {
            rows.append(HXBindingRow(title: hx("cli.field.runtimeEnv"), subtitle: none(), badges: []))
        }
        return rows
    }

    private static func none() -> String { hx("cli.value.none") }
}

/// The drawer's own read. A package that vanished between the page load and the tap answers 404, and that
/// has to say "could not load" rather than open an empty sheet.
@MainActor
public final class CliDetailModel: ObservableObject {
    public enum Phase: Equatable {
        case unaddressable
        case loading
        case ready
        case failed(String)
    }

    @Published public private(set) var phase: Phase
    @Published public private(set) var detail: CliSummary?
    /// Set once the read answered, whether or not it answered well: the sheet distinguishes "still on the
    /// wire" from "nothing to show" the same way the drawer's `!detail && !loading` branch does.
    @Published public private(set) var hasAnswered = false

    private let clis: any CliCataloging
    private let id: Int64?

    /// `CliResponse.kt` declares the id a nullable `Long?`, so a page row can arrive without one. With no
    /// address there is nothing to read, and inventing one would spend a request on a package nobody
    /// registered and report the server's refusal as if the row had gone away.
    public init(clis: any CliCataloging, id: Int64?) {
        self.clis = clis
        self.id = id
        self.phase = id == nil ? .unaddressable : .loading
    }

    public func load() async {
        guard let id else {
            phase = .unaddressable
            return
        }
        phase = .loading
        switch await clis.cliDetail(id: id) {
        case let .success(detail):
            self.detail = detail
            phase = .ready
        case let .failure(error):
            phase = .failed(ErrorMessage.text(for: error))
        }
        hasAnswered = true
    }

    /// Internal because `HXBindingSection` is: the row type is a this-module layout detail, and only the
    /// sheet below and its tests read it.
    var sections: [HXBindingSection] {
        guard let detail else { return [] }
        return CliDetailPresenter.sections(for: detail)
    }
}

/// D4 — the field groups of one registered package. Read-only by construction: there is nothing here to
/// edit, and the switch that does exist lives on the row that asked for it.
public struct CliDetailSheet: View {
    @StateObject private var model: CliDetailModel
    private let fallbackTitle: String

    public init(clis: any CliCataloging, cli: CliSummary) {
        _model = StateObject(wrappedValue: CliDetailModel(clis: clis, id: cli.id))
        self.fallbackTitle = cli.title ?? ""
    }

    public var body: some View {
        HXBindingSheet(title: model.detail?.title ?? fallbackTitle) {
            content
        }
        .task {
            if !model.hasAnswered { await model.load() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .unaddressable:
            HXStateView(.empty, message: hx("cli.detail.noAddress"))
        case .loading:
            HXStateView(.loading)
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await model.load() } })
        case .ready:
            HXBindingListView(sections: model.sections)
        }
    }
}
