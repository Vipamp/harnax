import SwiftUI
import HarnaxCore
import HarnaxKit
import UniformTypeIdentifiers

/// The sandbox workspace drawer, in a sheet.
///
/// The console keeps this drawer closed unless the sandbox is running, and opens it from a toolbar button
/// (`WorkspaceDrawer.tsx:186-190`). iOS cannot decide that for the caller — the parent that owns the button is
/// the one that knows the conversation — so the drawer is opened by the parent and renders the
/// "no running sandbox" state *inside* itself, as an empty state with the reason spelled out. It is never an
/// error, and the file listing is never asked for in that state.
///
/// What the row can do: descend into a directory, climb back with the breadcrumb or the up button, read a file
/// into the preview block, hand a file to the share sheet, and put a picked document into the directory on
/// screen. A file read that fails writes its sentence into the preview block and leaves the list alone
/// (`:98-101`); an upload that fails says why above the rows, which stay.
public struct WorkspaceSheet: View {
    @StateObject private var vm: WorkspaceViewModel
    @State private var isPicking = false
    /// A document the device itself refused to open — not a server answer, so it does not go through the view
    /// model's banner.
    @State private var pickFailure: String?

    public init(workspace: any SessionWorkspaceReading, sessionId: String) {
        _vm = StateObject(wrappedValue: WorkspaceViewModel(workspace: workspace, sessionId: sessionId))
    }

    /// For a parent that already owns the view model.
    public init(vm: WorkspaceViewModel) {
        _vm = StateObject(wrappedValue: vm)
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    banners
                    if vm.sandboxStatus == .running { navigation }
                    content
                    if let preview = vm.preview { previewBlock(preview) }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("chat.workspace.title")))
            .toolbar { controls }
        }
        .task { await vm.load() }
        .refreshable { await vm.reload() }
        .fileImporter(
            isPresented: $isPicking,
            allowedContentTypes: [.data],
            allowsMultipleSelection: false
        ) { outcome in
            pick(outcome)
        }
    }

    // MARK: - chrome

    @ToolbarContentBuilder
    private var controls: some ToolbarContent {
        ToolbarItem(placement: .primaryAction) {
            Button {
                isPicking = true
            } label: {
                Image(systemName: "square.and.arrow.up")
            }
            .accessibilityLabel(Text(verbatim: hx("chat.workspace.upload")))
            .disabled(vm.sandboxStatus != .running || vm.isUploading)
        }
        ToolbarItem(placement: .secondaryAction) {
            Button {
                Task { await vm.reload() }
            } label: {
                HXText("common.refresh")
            }
            .disabled(vm.isListing)
        }
    }

    @ViewBuilder
    private var banners: some View {
        if let error = vm.inlineError {
            HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
        }
        if let error = pickFailure {
            HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
        }
        if let notice = vm.notice {
            HXBanner("chat.workspace.upload.done.title", message: notice, systemImage: "checkmark.circle", tone: .success)
        }
    }

    /// Up one level plus the trail. Both stop at the root: `WorkspacePath.parent` returns the root for the
    /// root, and the button is only drawn once the drawer is actually below it.
    private var navigation: some View {
        HStack(spacing: 8) {
            if vm.canGoUp {
                Button {
                    Task { await vm.goUp() }
                } label: {
                    Image(systemName: "arrow.up")
                }
                .buttonStyle(.hxInline)
                .accessibilityLabel(Text(verbatim: hx("chat.workspace.up")))
            }
            breadcrumb
        }
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            ForEach(Array(vm.breadcrumbs.enumerated()), id: \.element.id) { index, crumb in
                if index > 0 {
                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                Button {
                    Task { await vm.goTo(path: crumb.path) }
                } label: {
                    Text(verbatim: crumb.label)
                }
                .buttonStyle(.plain)
                .foregroundStyle(index == vm.breadcrumbs.count - 1 ? Color.hx(.textPrimary) : Color.hx(.brand))
            }
        }
        .font(.footnote)
    }

    // MARK: - list

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .sandboxClosed:
            // The console's `Empty description="No active sandbox for this session"`.
            HXStateView(.empty, message: hx("chat.workspace.sandbox.closed"))
        case .emptyDirectory:
            HXStateView(.empty, message: hx("chat.workspace.directory.empty"))
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.load() } })
        case .content:
            rows
        }
    }

    private var rows: some View {
        HXGroupCard {
            ForEach(vm.files) { file in
                row(file)
            }
        }
        .overlay(alignment: .topTrailing) {
            if vm.isListing {
                ProgressView().padding(10).tint(Color.hx(.brand))
            }
        }
    }

    private func row(_ file: WorkspaceFile) -> some View {
        HXRow(
            text: file.name,
            subtitle: detail(for: file),
            systemImage: symbol(for: file.type),
            tone: tone(for: file.type),
            divider: file.id != vm.files.last?.id
        ) {
            trailing(for: file)
        }
        .contentShape(Rectangle())
        .onTapGesture { tap(file) }
        .contextMenu {
            if file.type != .directory {
                Button {
                    Task { await vm.download(file) }
                } label: {
                    HXText("chat.workspace.download")
                }
                .disabled(vm.pendingDownloads.contains(WorkspacePath.joined(vm.currentPath, file.name)))
            }
        }
    }

    @ViewBuilder
    private func trailing(for file: WorkspaceFile) -> some View {
        if file.type.isDirectory {
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
        } else if vm.pendingDownloads.contains(WorkspacePath.joined(vm.currentPath, file.name)) {
            ProgressView().tint(Color.hx(.brand))
        } else {
            Button {
                Task { await vm.download(file) }
            } label: {
                Image(systemName: "arrow.down.circle")
            }
            .buttonStyle(.hxInline)
            .accessibilityLabel(Text(verbatim: hx("chat.workspace.download")))
        }
    }

    private func tap(_ file: WorkspaceFile) {
        if file.type.isDirectory {
            Task { await vm.enter(file) }
        } else {
            Task { await vm.read(file) }
        }
    }

    /// The console draws a size for every non-directory row (`:273-277`); `find` answers `0` for a symlink,
    /// which is still what the entry's own stat says, so it is shown rather than dropped.
    private func detail(for file: WorkspaceFile) -> String? {
        guard !file.type.isDirectory else { return nil }
        guard let size = file.size else { return typeLabel(for: file.type) }
        return "\(typeLabel(for: file.type)) · \(hxFileSize(size))"
    }

    private func symbol(for kind: WorkspaceFileKind) -> String {
        switch kind {
        case .directory: "folder"
        case .file: "doc"
        case .symlink: "link"
        case .unknown: "questionmark.folder"
        }
    }

    private func tone(for kind: WorkspaceFileKind) -> PaletteSlot {
        switch kind {
        case .directory: .warning
        case .file: .textSecondary
        case .symlink: .teal
        case .unknown: .textTertiary
        }
    }

    /// The kind in the user's words. `unknown` is what the agent answers for a type code it has never seen
    /// (`SandboxWorkspaceController.kt:114-119`), so it gets a label rather than a guess.
    private func typeLabel(for kind: WorkspaceFileKind) -> String {
        switch kind {
        case .directory: hx("chat.workspace.kind.directory")
        case .file: hx("chat.workspace.kind.file")
        case .symlink: hx("chat.workspace.kind.symlink")
        case .unknown: hx("chat.workspace.kind.unknown")
        }
    }

    // MARK: - preview

    private func previewBlock(_ preview: WorkspaceViewModel.Preview) -> some View {
        HXCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    Text(verbatim: preview.name)
                        .font(.headline)
                        .foregroundStyle(Color.hx(.textPrimary))
                    Spacer(minLength: 0)
                    if preview.isTruncated {
                        HXBadge("chat.workspace.truncated", tone: .warning)
                    }
                }
                HXValueText(preview.path)
                if preview.isLoading {
                    HXStateView(.loading)
                } else {
                    Text(verbatim: preview.text.isEmpty ? hx("chat.workspace.preview.empty") : preview.text)
                        .font(.caption.monospaced())
                        .foregroundStyle(preview.isFailure ? Color.hx(.danger) : Color.hx(.textSecondary))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    // MARK: - picking

    private func pick(_ outcome: Result<[URL], Error>) {
        pickFailure = nil
        switch outcome {
        case .failure:
            // The user cancelling is not news; the importer reports it the same way it reports a real fault,
            // so nothing is said and the listing stays as it was.
            return
        case let .success(urls):
            guard let url = urls.first else { return }
            let secured = url.startAccessingSecurityScopedResource()
            defer { if secured { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                pickFailure = hx("chat.workspace.upload.unreadable")
                return
            }
            Task { await vm.upload(fileName: url.lastPathComponent, mimeType: Self.mimeType(of: url), payload: data) }
        }
    }

    private static func mimeType(of url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }
}
