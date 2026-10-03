import SwiftUI
import UniformTypeIdentifiers
import HarnaxCore
import HarnaxKit

/// The left column of the skill screen: one card per source, with the writes a source row owns.
///
/// Actions go into a `Menu` rather than a row of buttons, because a source row already carries four pieces
/// of metadata (type, address, enabled-skill count, last sync) and the console's own table has to fold the
/// same five columns on a phone (`specs/04-context-domains.md` "表格列 → 行卡片").
public struct SkillSourceListView: View {
    @StateObject private var vm: SkillSourceListViewModel
    @State private var syncing: SkillSourceSummary?
    @State private var uploading = false
    @State private var showReport = false
    /// What the open sheet draws. The view model's own report is published only until the sheet goes away, so
    /// the sheet cannot read it through the dismissal it triggers on the way out.
    @State private var reportToShow: SkillInstallReport?

    private let skills: any SkillCataloging
    private let account: AccountSnapshot?

    /// Read here rather than drawn through `HXSegmentBarRow`: that row is an `EmptyView` when the host has no
    /// bar, and an empty row in a `List` still costs its insets and its background — which is what the
    /// walkthrough screen mounting this list bare would show.
    @Environment(\.harnaxSegmentBar) private var segmentBar: AnyView?

    public init(
        skills: any SkillCataloging,
        account: AccountSnapshot? = nil
    ) {
        _vm = StateObject(wrappedValue: SkillSourceListViewModel(skills: skills, account: account))
        self.skills = skills
        self.account = account
    }

    public var body: some View {
        List {
            if let segmentBar {
                segmentBar
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            if let error = vm.inlineError {
                HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                    .listRowBackground(Color.hx(.surface))
            }
            if let blocked = vm.blockedMessage {
                HXBanner("skill.source.delete.blockedTitle", message: blocked, systemImage: "lock", tone: .warning)
                    .listRowBackground(Color.hx(.surface))
            }
            if let hint = vm.configurationHint {
                HStack(alignment: .top, spacing: 8) {
                    HXBanner(
                        "skill.repository.updatedTitle",
                        message: hint,
                        systemImage: "arrow.triangle.2.circlepath",
                        tone: .warning
                    )
                    Button { vm.dismissConfigurationHint() } label: {
                        Image(systemName: "xmark")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(hx("common.close"))
                }
                .listRowBackground(Color.hx(.surface))
            }
            switch vm.phase {
            case .loading, .failed:
                HXStateView(stateViewKind, message: stateMessage, retry: { Task { await vm.refresh() } })
                    .listRowBackground(Color.clear)
            case .empty:
                HXStateView(.empty, message: hx("skill.source.empty"))
                    .listRowBackground(Color.clear)
            case .content:
                ForEach(vm.items) { source in
                    card(source)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                if vm.isAppending {
                    HXStateView(.loading).listRowBackground(Color.clear)
                }
            }
        }
        .listStyle(.plain)
        .harnaxScreen()
        .navigationTitle(Text(verbatim: hx("context.domain.skill")))
        .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("skill.source.search")))
        .toolbar {
            // Two entries, as the console has two writes on this card: one Create button
            // (`index.tsx:191-195`) and the ZIP half, which lives behind its own endpoint
            // (`SkillSourceServiceImpl.kt:119-123`). Both are glyphs so the pair reads the way every other
            // entity list's trailing pair does; a worded button beside the `+` would be the one odd shape here.
            ToolbarItem(placement: .primaryAction) {
                HXPlusButton(titleKey: "skill.repository.create") { vm.beginRepositoryCreate() }
            }
            ToolbarItem(placement: .primaryAction) {
                Button { uploading = true } label: {
                    Image(systemName: "arrow.up.doc")
                }
                .accessibilityLabel(hx("skill.source.upload"))
            }
        }
        .task { await vm.refresh() }
        .refreshable { await vm.refresh() }
        .sheet(item: $syncing) { source in
            // A sync rewrites the rows this list shows — the skill count and the last-sync column both come
            // off the source, so the report has to reload them rather than leave the old numbers on screen.
            SkillSyncSheet(source: source, skills: skills, onChanged: { Task { await vm.refresh() } })
        }
        .sheet(isPresented: $uploading) {
            SkillUploadSheet(vm: vm)
        }
        .sheet(item: $vm.repositoryForm) { form in
            SkillRepositoryFormSheet(form: form) {
                await vm.submitRepositoryForm()
            }
        }
        .sheet(isPresented: $showReport, onDismiss: { vm.dismissReport() }) {
            if let report = reportToShow {
                SkillReportSheet(report: report)
            }
        }
        .onChange(of: vm.lastReport) { _, report in
            reportToShow = report
            showReport = report != nil
        }
        .confirmationDialog(
            Text(verbatim: deleteTitle),
            isPresented: Binding(
                get: { vm.deleteTarget != nil },
                set: { if !$0 { vm.cancelDelete() } }
            ),
            titleVisibility: .visible,
            presenting: vm.deleteTarget
        ) { source in
            Button(role: .destructive) {
                Task { await vm.confirmDelete() }
            } label: {
                Text(verbatim: source.title)
            }
            Button(role: .cancel) { vm.cancelDelete() } label: {
                HXText("common.cancel")
            }
        }
    }

    private var stateViewKind: HXStateView.Kind {
        vm.phase == .loading ? .loading : .error
    }

    private var stateMessage: String? {
        if case let .failed(message) = vm.phase { return message }
        return hx("state.error.hint")
    }

    private var deleteTitle: String { hx("skill.source.delete.title") }

    @ViewBuilder
    private func card(_ source: SkillSourceSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            NavigationLink(value: SkillSourceRoute(id: source.id, name: hxPresented(source.title))) {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(spacing: 6) {
                            Text(verbatim: source.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color.hx(.textPrimary))
                                .lineLimit(1)
                            HXChip(hx(typeKey(for: source)), tone: source.isEnabled ? .brand : .textTertiary)
                            if !source.type.isRefreshable {
                                HXChip(hx("skill.source.readonly"), tone: .purple)
                            }
                        }
                        if let endpoint = source.endpoint {
                            HXValueText(endpoint)
                        }
                        HStack(spacing: 8) {
                            if let count = source.enabledSkills {
                                HXChip(hx("skill.source.skills", count), tone: .teal)
                            }
                            syncBadge(source)
                        }
                    }
                    Spacer(minLength: 0)
                    HXStatusDot(
                        tone: source.isEnabled ? .success : .textTertiary,
                        label: hx(source.isEnabled ? "state.badge.enabled" : "state.badge.disabled")
                    )
                }
            }
            .buttonStyle(.plain)
            if mayWrite(source) {
                menu(source)
            }
        }
        .padding(12)
        .background(
            Color.hx(vm.selection == source.id ? .surfaceAlt : .surface),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
    }

    @ViewBuilder
    private func syncBadge(_ source: SkillSourceSummary) -> some View {
        if let key = SkillSyncDetailReader.statusKey(for: source.lastSyncStatus) {
            HXChip(hx(key) + (hxMonthDay(source.lastSyncTime).map { " · " + $0 } ?? ""), tone: SkillSyncDetailReader.slot(for: source.lastSyncStatus))
        } else {
            HXChip(hx("skill.source.never"), tone: .textTertiary)
        }
    }

    private func typeKey(for source: SkillSourceSummary) -> String {
        switch source.type {
        case .git: "skill.type.git"
        case .npm: "skill.type.npm"
        case .zip: "skill.type.zip"
        case .builtin: "skill.type.builtin"
        case .unknown: "skill.type.other"
        }
    }

    /// Whether this row carries an action menu at all.
    ///
    /// The console hides the whole cluster — switch, sync, install, edit, delete — rather than disabling each
    /// control in it, on two conditions: the platform-owned row never has one, and anyone who is not an
    /// administrator only gets one on their own rows (`RepositoryList.tsx:384,438`).
    private func mayWrite(_ source: SkillSourceSummary) -> Bool {
        !source.isPlatformOwned && (account?.canManage(creator: source.creator) ?? false)
    }

    @ViewBuilder
    private func menu(_ source: SkillSourceSummary) -> some View {
        Menu {
            if source.type.isRefreshable {
                Button { syncing = source } label: {
                    HXText("skill.source.sync")
                }
                Button {
                    Task {
                        await vm.installAll(source)
                    }
                } label: {
                    HXText("skill.source.install")
                }
            }
            Button {
                Task { await vm.setStatus(!vm.status(of: source), for: source) }
            } label: {
                HXText(vm.status(of: source) ? "state.action.disable" : "state.action.enable")
            }
            // Not gated on `isRefreshable` the way sync and install are: an edit writes configuration, and a
            // ZIP row still has a name, version, description and two switches worth changing
            // (`RepositoryList.tsx:459-464` — the `EditButton` is unconditional, unlike its two neighbours).
            Button {
                vm.beginRepositoryEdit(source)
            } label: {
                HXText("state.action.edit")
            }
            Button(role: .destructive) {
                vm.requestDelete(source)
            } label: {
                HXText("state.action.delete")
            }
        } label: {
            Image(systemName: "ellipsis")
                .foregroundStyle(Color.hx(.textSecondary))
        }
        .accessibilityLabel(hx("state.action.more"))
        .disabled(vm.pendingIDs.contains(source.id))
    }
}

/// The ZIP sheet. Selecting an archive never uploads it — the operator names the source and presses the
/// button, exactly as the console's `beforeUpload: false` does (`RepositoryForm.tsx:258-277`).
///
/// Public so the DEBUG walkthrough can frame the sheet from a launch argument — the simulator takes no input,
/// so a sheet the harness cannot name is a sheet nobody can review (`App/HarnaxDebugScreens.swift`).
public struct SkillUploadSheet: View {
    @ObservedObject var vm: SkillSourceListViewModel
    /// Bound directly: it is an observable object in its own right, and `$vm.upload.name` would need a
    /// setter the view model deliberately does not expose.
    @ObservedObject private var upload: SkillUploadModel
    @Environment(\.dismiss) private var dismiss
    @State private var picking = false
    @State private var pickError: String?

    public init(vm: SkillSourceListViewModel) {
        self.vm = vm
        upload = vm.upload
    }

    public var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                HXField("skill.source.upload.name", text: $upload.name, systemImage: "textformat")
                Button { picking = true } label: {
                    HXText(upload.pickedFile == nil ? "skill.source.upload.pick" : "skill.source.upload.again")
                }
                .buttonStyle(.hxSecondary)
                if let file = upload.pickedFile {
                    HXValueText(file)
                }
                if let needs = upload.needsInput {
                    HXBanner("skill.source.upload.title", message: needs, systemImage: "exclamationmark.triangle", tone: .warning)
                }
                if let pickError {
                    HXBanner("skill.source.upload.title", message: pickError, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                if let error = upload.error {
                    HXBanner("state.error.title", message: error, systemImage: "xmark.circle", tone: .danger)
                }
                Button {
                    Task {
                        await vm.finishUpload()
                        if upload.error == nil { dismiss() }
                    }
                } label: {
                    HXText("skill.source.upload.confirm")
                }
                .buttonStyle(.hxPrimary)
                .disabled(!upload.canSubmit || upload.isWorking)
                Spacer(minLength: 0)
            }
            .padding(16)
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("skill.source.upload.title")))
#if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { HXText("common.cancel") }
                }
            }
            .fileImporter(
                isPresented: $picking,
                allowedContentTypes: [UTType.zip],
                allowsMultipleSelection: false
            ) { result in
                adopt(result)
            }
        }
    }

    /// The security-scoped URL is only readable while the sheet holds it, so the bytes come straight into
    /// memory here and the model never sees a path.
    private func adopt(_ result: Result<[URL], Error>) {
        switch result {
        case let .success(urls):
            guard let url = urls.first else { return }
            let readable = url.startAccessingSecurityScopedResource()
            defer { if readable { url.stopAccessingSecurityScopedResource() } }
            guard let payload = try? Data(contentsOf: url) else {
                pickError = hx("skill.source.upload.unreadable")
                return
            }
            vm.upload.select(fileName: url.lastPathComponent, payload: payload)
        case .failure:
            pickError = hx("skill.source.upload.unreadable")
        }
    }
}

/// The graded install answer: one summary line, and the buckets underneath it when there is anything to
/// read. Product naming for the five buckets is still open, so each one leads with a count and expands to
/// the rows (`RepositoryList.tsx:153-183`).
struct SkillInstallReportCard: View {
    let report: SkillInstallReport
    @State private var open: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HXBanner(
                "skill.install.title",
                message: report.message.map { report.title + ": " + $0 } ?? report.title,
                systemImage: report.tone == .success ? "checkmark.circle.fill" : "exclamationmark.triangle",
                tone: report.tone.slot
            )
            if !report.isClean {
                ForEach(report.buckets) { bucket in
                    section(bucket)
                }
            }
        }
    }

    @ViewBuilder
    private func section(_ bucket: SkillInstallBucket) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                if open.contains(bucket.id) {
                    open.remove(bucket.id)
                } else {
                    open.insert(bucket.id)
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: open.contains(bucket.id) ? "chevron.down" : "chevron.right")
                        .foregroundStyle(Color.hx(.textTertiary))
                    HXText(bucket.kind.titleKey)
                        .font(.subheadline)
                        .foregroundStyle(Color.hx(.textPrimary))
                    Spacer(minLength: 0)
                    HXChip("\(bucket.count)", tone: bucket.kind == .failed ? .danger : .purple)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open.contains(bucket.id) {
                ForEach(bucket.lines) { line in
                    VStack(alignment: .leading, spacing: 2) {
                        HXValueText(line.name, lines: 2)
                        if let reason = line.reason {
                            Text(verbatim: reason)
                                .font(.caption)
                                .foregroundStyle(Color.hx(.textSecondary))
                        }
                    }
                    .padding(.leading, 22)
                }
            }
        }
    }
}

/// The upload/create answer as its own sheet: it carries the whole point of the response, so it cannot be a
/// toast the operator misses.
///
/// Public so the DEBUG walkthrough can frame the sheet from a launch argument — the simulator takes no input,
/// so a sheet the harness cannot name is a sheet nobody can review (`App/HarnaxDebugScreens.swift`).
public struct SkillReportSheet: View {
    let report: SkillInstallReport
    @Environment(\.dismiss) private var dismiss

    public init(report: SkillInstallReport) {
        self.report = report
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    SkillInstallReportCard(report: report)
                }
                .padding(16)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("skill.install.title")))
#if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { HXText("common.close") }
                }
            }
        }
    }
}
