import SwiftUI
import HarnaxCore
import HarnaxKit

/// 技能监控 — the console's `harnax-webui/src/pages/skill/usage.tsx` on a handset: one window, five numbers,
/// one table.
///
/// The blocks are the console's, in its order: the window choice, the headline cards, the per-skill table, the
/// window sentence under it (`usage.tsx:152-232`). What a handset changes is only the width — the console puts
/// four cards across a row and a seven-column table under them; here the cards wrap and every column of that
/// table becomes a line of one card, because a horizontally scrolling table on a phone hides the one column the
/// page exists to show.
///
/// A leaf. The console's skill name is a link into `/context/skill/detail/{id}` (`usage.tsx:84-89`), and this
/// screen pushes nothing: the counts on a row are the whole answer, and the detail already has its own entry in
/// the skill domain.
public struct SkillUsageView: View {
    @StateObject private var vm: SkillUsageViewModel

    public init(catalog: any SkillUsageReading) {
        _vm = StateObject(wrappedValue: SkillUsageViewModel(catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .harnaxTitle(Text(verbatim: hx("skill.usage.title")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await vm.refresh() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(hx("state.action.reload"))
                }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            //「No skill to report」(`usage.tsx:217-223`) — the tenant has nothing to count. A refusal is the
            // `.failed` case below and says so in its own words; the two must not borrow each other's sentence.
            HXStateView(.empty, message: hx("skill.usage.empty"), retry: { Task { await vm.refresh() } })
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.refresh() } })
        case .content:
            screen
        }
    }

    private var screen: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                if let inline = vm.inlineError {
                    HXBanner(
                        "state.error.title",
                        message: inline,
                        systemImage: "exclamationmark.triangle",
                        tone: .danger
                    )
                }
                controls
                metricsBlock
                tableBlock
                windowNote
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            // Pushed from 「我的」, so no tab pill floats over the last row and the list needs no clearance.
            .padding(.bottom, 24)
        }
    }

    // MARK: - the window and the one filter

    /// The window as three presses, never as a field to type into. `days` is the only parameter this route
    /// takes (`SkillUsageController.kt:36-39`), and the three options are the three the console's `Select`
    /// offers (`usage.tsx:195-199`).
    private var controls: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 12) {
                windowPicker
                Toggle(isOn: $vm.unusedOnly) {
                    HXText("skill.usage.unusedOnly")
                }
                .toggleStyle(.switch)
                .tint(Color.hx(.brand))
                if vm.showsNoUnusedNote {
                    // The filter matched nothing — every skill here got loaded at least once. Not the same
                    // sentence as「no skill to report」, which is the `.empty` phase.
                    HXText("skill.usage.unusedOnly.none")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                }
            }
        }
    }

    /// Drawn inline rather than handed down by a tab shell: this screen is a pushed leaf and owns no column of
    /// its own (`SegmentBarGateTests.swift:62-79`).
    private var windowPicker: some View {
        HXSegmented(
            SkillUsageViewModel.Window.allCases.map { HXSegmentOption(id: $0.days, $0.titleKey) },
            selection: Binding(
                get: { vm.window.days },
                set: { vm.window = SkillUsageViewModel.Window.for($0) }
            )
        )
    }

    // MARK: - the five numbers

    private var metricsBlock: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
            ForEach(vm.metrics) { metric in
                SkillUsageMetricTile(metric: metric)
            }
        }
    }

    // MARK: - the table

    private var tableBlock: some View {
        VStack(spacing: 12) {
            HXSectionHeader("skill.usage.section")
            ForEach(vm.entries) { entry in
                SkillUsageEntryCard(entry: entry)
            }
        }
    }

    /// The console's paragraph under the table (`usage.tsx:230-232`), printed with the window the server
    /// answered rather than the one this screen asked for — the echo is the only way a wrong span can be seen.
    @ViewBuilder
    private var windowNote: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let days = vm.answeredDays {
                HXText("skill.usage.window", days)
            }
            if let since = vm.since {
                HXText("skill.usage.since", since)
            }
        }
        .font(.caption)
        .foregroundStyle(Color.hx(.textTertiary))
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One headline number: its word, its figure, and the console's tooltip where it has one.
private struct SkillUsageMetricTile: View {
    let metric: SkillUsageMetric

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Image(systemName: glyph)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.hx(metric.tone))
                    HXText(metric.titleKey)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .lineLimit(2)
                }
                Text(verbatim: metric.value)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if let captionKey = metric.captionKey {
                    HXText(captionKey)
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
            }
        }
        .overlay(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: 3)
                .fill(Color.hx(metric.tone))
                .frame(width: 18, height: 3)
                .padding(10)
        }
    }

    /// Hues alone carry nothing for a screen reader, so the figure's own word is the caption's job — the tile
    /// above already reads title then value.
    private var glyph: String {
        switch metric.kind {
        case .skills: return "square.stack"
        case .loads: return "eye"
        case .uses: return "bolt"
        case .unused: return "moon"
        case .used: return "checkmark.circle"
        }
    }
}

/// One skill: its name, its two counts, what it is, and when the window last saw it.
private struct SkillUsageEntryCard: View {
    let entry: SkillUsageEntry

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Text(verbatim: entry.name.isEmpty ? hx("skill.table.unnamed") : entry.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.hx(.textPrimary))
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    figure(.loads, entry.views)
                    figure(.uses, entry.uses)
                }
                HStack(spacing: 6) {
                    ForEach(entry.badges) { badge in
                        HXBadge(badge.titleKey, tone: badge.tone)
                    }
                    if let repository = entry.repository {
                        HXChip(repository)
                    }
                }
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    HXText("skill.usage.lastEvent")
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textSecondary))
                    if let event = entry.lastEvent {
                        Text(verbatim: event)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textPrimary))
                    } else {
                        HXText("skill.usage.lastEvent.none")
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                }
            }
        }
    }

    /// A column of the console's table, labelled by the same word its card above uses, so `Loads` means one
    /// thing on the screen.
    private func figure(_ kind: SkillUsageMetric.Kind, _ value: String) -> some View {
        VStack(alignment: .trailing, spacing: 2) {
            HXText(kind.titleKey)
                .font(.caption2)
                .foregroundStyle(Color.hx(.textSecondary))
            Text(verbatim: value)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.hx(.textPrimary))
                .monospacedDigit()
        }
        .fixedSize()
    }
}
