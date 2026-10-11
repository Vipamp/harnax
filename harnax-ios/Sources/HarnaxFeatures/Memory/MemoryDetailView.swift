import SwiftUI
import HarnaxCore
import HarnaxKit

/// One agent's long-term memory, whole: the curated `MEMORY.md` and every day of the append-only ledger behind it.
///
/// The two layers are shown apart rather than merged, because that is how the runtime keeps them
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDetailResponse.kt:6-9`: a consolidator
/// rewrites the curated text, a per-call flush only appends a day), and it is how the console draws them
/// (`harnax-webui/src/pages/memory/components/MemoryDetailDrawer.tsx`, sections「整理后的记忆」and「每日记录」).
///
/// Nothing here reads, links or offers a conversation's own memory layer: the read side has no route that
/// addresses one (`prod_doc/ios-memory-domain.zh-CN.md` §2), so the only thing this domain says about that layer
/// is the list row's count of what still owes a merge.
public struct MemoryDetailView: View {
    @StateObject private var vm: MemoryDetailViewModel

    public init(agentName: String, catalog: any MemoryCataloging) {
        _vm = StateObject(wrappedValue: MemoryDetailViewModel(agentName: agentName, catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: vm.agentName))
            .task {
                if vm.phase == .loading { await vm.load() }
            }
            .refreshable { await vm.load() }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(
                .empty,
                message: hx(MemoryDetailViewModel.emptyKey),
                retry: { Task { await vm.load() } }
            )
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.load() } })
        case .content:
            screen
        }
    }

    private var screen: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                curated
                daily
                // The ledger stops where the conversation layer took over, which is the one thing a count of
                // days cannot say by itself (`harnax-webui/src/pages/memory/index.tsx:151-175`).
                Text(verbatim: hx("memory.detail.note"))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    @ViewBuilder
    private var curated: some View {
        HXSectionHeader("memory.detail.curated")
        HXCard {
            if let text = vm.curated {
                HXMarkdownText(text, on: .surface)
            } else {
                HXText("memory.detail.noCurated")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        }
        .overlay(alignment: .topTrailing) {
            if let day = vm.updatedDay {
                Text(verbatim: hx("memory.row.updated") + " " + day)
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .padding(14)
            }
        }
    }

    @ViewBuilder
    private var daily: some View {
        HXSectionHeader("memory.detail.daily")
        if vm.entries.isEmpty {
            HXCard {
                HXText("memory.detail.noDaily")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        } else {
            ForEach(vm.entries) { entry in
                MemoryDailyCard(entry: entry)
            }
        }
    }
}

/// One day of the ledger.
///
/// The date is the entry's identity — the bucket keys these `memory/<date>.md` and the object key stays
/// server-side (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDailyEntryResponse.kt:6-11`) —
/// so it is the title, and the stamp is when that day was last written.
struct MemoryDailyCard: View {
    let entry: MemoryDailyEntry

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 6) {
                    Text(verbatim: entry.date)
                        .font(.subheadline.weight(.semibold).monospaced())
                        .foregroundStyle(Color.hx(.textPrimary))
                    Spacer(minLength: 0)
                    if let day = MemoryCopy.day(entry.lastModified) {
                        Text(verbatim: day)
                            .font(.caption2)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                }
                if let text = hxPresented(entry.content) {
                    HXMarkdownText(text, on: .surface)
                } else {
                    // A day that is in the bucket is a day the owner lived through, so the line stays even when
                    // its body could not be read (`MemoryStoreGateway.kt:128-133`).
                    HXText("memory.detail.noEntry")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
            }
        }
    }
}
