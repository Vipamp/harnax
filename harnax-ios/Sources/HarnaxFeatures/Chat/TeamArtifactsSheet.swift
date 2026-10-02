import SwiftUI
import HarnaxCore
import HarnaxKit

/// The team artifacts drawer, in a sheet.
///
/// Both routes this drawer reads live behind one deployment switch: `TeamArtifactController` is registered
/// only when the server runs with `minio.enabled=true` (`TeamArtifactController.kt:42`), and the artifact
/// bytes come out of that bucket (`:87`, `:147-157`). A deployment without object storage therefore answers the
/// list as an unknown route, and `DESIGN.md` O8 gives that its own state here — neither the error glyph nor
/// "no member has published anything" describes a switched-off store, and the two have different remedies.
/// A refused *download* stays the plain status it arrived with, because by then the controller is registered.
///
/// The row shows what the console's four columns show (`TeamArtifactsDrawer.tsx:68-122`): the name, the
/// `mimeType · size` subtitle the DTO already renders, a copyable short reference, and the publication time.
/// `fileId` is the only handle the server gives out — deliberately, because the object key would let a client
/// address the bucket directly (`TeamArtifactResponse.kt:9-12`) — which is why it is the value worth copying
/// back to a member.
///
/// There is no search, no sort and no pagination, matching both the route (which answers the whole list,
/// newest first) and `§iOS 适配注意点 14`.
public struct TeamArtifactsSheet: View {
    @StateObject private var vm: TeamArtifactsViewModel

    public init(reading: any TeamArtifactReading, sessionId: String) {
        _vm = StateObject(wrappedValue: TeamArtifactsViewModel(reading: reading, sessionId: sessionId))
    }

    /// For a parent that already owns the view model.
    public init(vm: TeamArtifactsViewModel) {
        _vm = StateObject(wrappedValue: vm)
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let error = vm.inlineError {
                        HXBanner(
                            "state.error.title",
                            message: error,
                            systemImage: "exclamationmark.triangle",
                            tone: .danger
                        )
                    }
                    content
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("chat.artifacts.title")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await vm.load() }
                    } label: {
                        HXText("common.refresh")
                    }
                }
            }
        }
        .task { await vm.load() }
        .refreshable { await vm.load() }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(.empty, message: hx("chat.artifacts.empty"), retry: { Task { await vm.load() } })
        case .objectStoreDisabled:
            HXStateView(.empty, message: hx("error.objectStoreDisabled"), retry: { Task { await vm.load() } })
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.load() } })
        case .content:
            rows
        }
    }

    private var rows: some View {
        HXGroupCard {
            ForEach(vm.artifacts) { artifact in
                row(artifact)
                if artifact.id != vm.artifacts.last?.id { Divider() }
            }
        }
    }

    private func row(_ artifact: TeamArtifact) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HXRow(
                text: artifact.displayName ?? artifact.fileId,
                subtitle: artifact.subtitle,
                systemImage: "doc",
                tone: .brand,
                divider: false
            ) {
                downloadButton(artifact)
            }
            // The reference under the name: eight characters plus the ellipsis the DTO itself renders
            // (`TeamArtifact.shortFileId`), long-press copies the whole UUID.
            HXValueText(artifact.shortFileId)
            if let published = hxPresented(artifact.createTime) {
                Text(verbatim: hx("chat.artifacts.published", published))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        }
        .padding(14)
    }

    @ViewBuilder
    private func downloadButton(_ artifact: TeamArtifact) -> some View {
        if vm.isDownloading(artifact) {
            ProgressView().tint(Color.hx(.brand))
        } else {
            Button {
                Task { await vm.download(artifact) }
            } label: {
                Image(systemName: "arrow.down.circle")
            }
            .buttonStyle(.hxInline)
            .accessibilityLabel(Text(verbatim: hx("chat.artifacts.download")))
        }
    }
}
