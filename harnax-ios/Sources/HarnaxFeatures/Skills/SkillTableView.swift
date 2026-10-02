import SwiftUI
import HarnaxCore
import HarnaxKit

/// The right column: the skills one source has stored. Six Web columns fold into title, subtitle, marks and
/// a menu, which is the same reduction the console's own card grid makes on a phone.
///
/// There is no delete here on purpose (`SkillList.tsx:27-138`); the only write this table has is the switch,
/// and that one is gated by the binding counts on the row.
public struct SkillTableView: View {
    @StateObject private var vm: SkillTableViewModel
    private let sourceID: Int64?
    private let sourceName: String?
    private let skills: any SkillCataloging

    public init(sourceID: Int64?, sourceName: String? = nil, skills: any SkillCataloging) {
        self.sourceID = sourceID
        self.sourceName = sourceName
        self.skills = skills
        _vm = StateObject(wrappedValue: SkillTableViewModel(skills: skills))
    }

    public var body: some View {
        List {
            if let error = vm.inlineError {
                HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                    .listRowBackground(Color.hx(.surface))
            }
            if let notice = vm.boundNotice {
                HXBanner("skill.bound.title", message: notice, systemImage: "link", tone: .warning)
                    .listRowBackground(Color.hx(.surface))
            }
            switch vm.phase {
            case .loading:
                HXStateView(.loading).listRowBackground(Color.clear)
            case let .failed(message):
                HXStateView(.error, message: message, retry: { Task { await vm.refresh() } })
                    .listRowBackground(Color.clear)
            case .empty:
                HXStateView(.empty, message: hx(vm.isFiltered ? "skill.table.emptyFiltered" : "skill.table.empty"))
                    .listRowBackground(Color.clear)
            case .content:
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, skill in
                    row(skill)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .onAppear {
                            if index == vm.items.count - 1 { Task { await vm.loadMore() } }
                        }
                }
                if vm.isAppending {
                    HXStateView(.loading).listRowBackground(Color.clear)
                }
            }
        }
        .listStyle(.plain)
        .harnaxScreen()
        // Named by its source: this screen and the list it pushed from would otherwise carry the same
        // "Skills" title, and a stacked pair with one title cannot be told apart.
        .navigationTitle(Text(verbatim: sourceName ?? hx("skill.table.title")))
        .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("skill.table.search")))
        .toolbar {
            ToolbarItem(placement: .primaryAction) { filterMenu }
        }
        .refreshable { await vm.refresh() }
        .task { await vm.show(id: sourceID) }
        .onChange(of: sourceID) { next in
            Task { await vm.show(id: next) }
        }
        .navigationDestination(for: SkillDetailRoute.self) { route in
            SkillDetailView(id: route.id, skills: skills)
        }
    }

    /// The skill detail's address, as its own type. `SkillItem.id` is nullable (`SkillResponse.kt:13`), and a
    /// link that sends an `Optional` to a stack holding one destination per type would match nothing: the row
    /// looks tappable and the tap is swallowed. Wrapping the unwrapped id is what makes that mismatch a
    /// compile error instead of a dead control, and it lets a row without an id drop the link entirely.
    public struct SkillDetailRoute: Hashable {
        public let id: Int64
    }

    /// The route a row offers, or `nil` when the row has no address to send.
    static func detailRoute(for skill: SkillItem) -> SkillDetailRoute? {
        skill.id.map { SkillDetailRoute(id: $0) }
    }

    /// The status filter. It used to be a header row, and a header row scrolls away; the source this table
    /// belongs to is named by the navigation title instead, because the pushed skill detail below has to agree
    /// with it about whose skills are on screen.
    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: vm.filter != .all,
            accessibilityLabel: hx("state.filter.status"),
            choices: statusFilterChoices(vm.filter) { vm.filter = $0 }
        )
    }

    private func row(_ skill: SkillItem) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    if let route = Self.detailRoute(for: skill) {
                        NavigationLink(value: route) {
                            detailTitle(skill)
                        }
                    } else {
                        detailTitle(skill)
                    }
                    if let detail = skill.detail {
                        Text(verbatim: detail)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .lineLimit(2)
                    }
                    HStack(spacing: 6) {
                        if skill.boundAgentCount > 0 {
                            HXChip(hx("skill.bound.agents", skill.boundAgentCount), tone: .indigo)
                        }
                        if skill.boundTeamCount > 0 {
                            HXChip(hx("skill.bound.teams", skill.boundTeamCount), tone: .purple)
                        }
                        if skill.isShared {
                            HXBadge("state.badge.shared", tone: .teal)
                        }
                    }
                    Text(verbatim: byline(skill))
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                Spacer(minLength: 0)
                statusMenu(skill)
            }
        }
        .padding(12)
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .disabled(skill.id == nil)
    }

    /// The row's first line. Shared by the linked and the unlinked case so an addressless row looks the same
    /// as every other one except for the arrow.
    @ViewBuilder
    private func detailTitle(_ skill: SkillItem) -> some View {
        Text(verbatim: skill.title ?? hx("skill.table.unnamed"))
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.hx(.textPrimary))
            .lineLimit(1)
    }

    /// The table's one write. The disable direction is gated on the row's own binding counts, so the menu
    /// refuses before the stack does and the banner underneath the header can say why
    /// (`SkillList.tsx:94-134`).
    private func statusMenu(_ skill: SkillItem) -> some View {
        let on = vm.status(of: skill)
        let pending = skill.id.map { vm.pendingIDs.contains($0) } ?? false
        return Menu {
            Button {
                Task { await vm.setStatus(!on, for: skill) }
            } label: {
                HXText(on ? "state.action.disable" : "state.action.enable")
            }
            .disabled(on && skill.isBound)
        } label: {
            HStack(spacing: 4) {
                HXStatusDot(
                    tone: on ? .success : .textTertiary,
                    label: hx(on ? "state.badge.enabled" : "state.badge.disabled")
                )
                Image(systemName: pending ? "hourglass" : "ellipsis")
                    .foregroundStyle(Color.hx(.textSecondary))
                    .frame(width: 22, height: 22)
            }
        }
        .accessibilityLabel(hx(on ? "state.action.disable" : "state.action.enable"))
        .disabled(pending || skill.id == nil)
    }

    /// `updateTime` on this table is titled 'Sync time' in the console, not 'Modified'
    /// (`SkillList.tsx:27-138`) — it is when the source last wrote the row.
    private func byline(_ skill: SkillItem) -> String {
        var pieces: [String] = []
        if let creator = hxPresented(skill.creator) { pieces.append(creator) }
        if let day = hxMonthDay(skill.updateTime) {
            pieces.append(hx("skill.table.syncTime") + " " + day)
        }
        return pieces.joined(separator: " · ")
    }
}
