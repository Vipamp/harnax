import SwiftUI
import HarnaxCore
import HarnaxKit

/// The drill-down: the calls one subject made inside the window the card that pushed it was counted over.
///
/// The console keeps this list in a drawer beside the table (`harnax-webui/src/pages/call-metrics/index.tsx:514-561`);
/// on a handset the drawer becomes a pushed second screen, because there is no beside — and the pushed value
/// carries the window and the dimension precisely so that this list cannot outlive the query it came from
/// (`CallMetricsSubjectRef`).
///
/// What the screen does not show is the subject's profile: that panel reads the agent, MCP, CLI and session
/// registries, and this catalog answers the three call-metrics routes and nothing else
/// (`Sources/HarnaxCore/Contract/ToolMetrics.swift:296-315`). The subject's own numbers stayed on the card.
public struct CallInvocationsView: View {
    @StateObject private var vm: CallInvocationsViewModel

    public init(subject: CallMetricsSubjectRef, catalog: any ToolMetricsReading) {
        _vm = StateObject(wrappedValue: CallInvocationsViewModel(subject: subject, catalog: catalog))
    }

    public var body: some View {
        VStack(spacing: 0) {
            filterBar
            content
        }
        .harnaxScreen()
        .navigationTitle(Text(verbatim: vm.subject.title))
        .task {
            if vm.phase == .loading { await vm.refresh() }
        }
        .refreshable { await vm.refresh() }
    }

    /// The outcome arms, pinned above the list.
    ///
    /// Five arms for four codes: 「all」is the absence of the `outcome` parameter rather than a fifth token the
    /// server would have to recognise (`ToolMetricsController.kt:101`), which is the same rule the draft queues
    /// follow.
    private var filterBar: some View {
        HXSegmented(
            CallInvocationsView.arms.enumerated().map { index, arm in
                HXSegmentOption(id: index, arm.map(\.titleKey) ?? "state.filter.all")
            },
            selection: outcomeBinding
        )
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    private static var arms: [CallInvocationOutcome?] {
        [nil] + CallInvocationOutcome.allCases
    }

    private var outcomeBinding: Binding<Int> {
        Binding(
            get: { CallInvocationsView.arms.firstIndex(of: vm.outcome) ?? 0 },
            set: { vm.outcome = $0 < CallInvocationsView.arms.count ? CallInvocationsView.arms[$0] : nil }
        )
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(
                .empty,
                message: hx(vm.emptyKey),
                retry: { Task { await vm.refresh() } }
            )
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.refresh() } })
        case .content:
            list
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                if let inline = vm.inlineError {
                    HXBanner(
                        "state.error.title",
                        message: inline,
                        systemImage: "exclamationmark.triangle",
                        tone: .danger
                    )
                }
                header
                // Row identity is the index, as in the draft queue: a page shifted underneath an append would
                // otherwise put two records under one `ForEach` identity and drop one.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, row in
                    CallInvocationCard(row: row)
                        .onAppear {
                            if index == vm.items.count - 1 { Task { await vm.loadMore() } }
                        }
                }
                if vm.isAppending {
                    HXStateView(.loading)
                }
                if vm.showsRetentionNote {
                    Text(verbatim: hx("monitor.calls.retention.note"))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 20)
        }
    }

    /// Which subject and which interval this is a page of, how many calls answered, and which origin the list is
    /// filtered to — three sentences the row that pushed already decided, quoted back rather than re-derived.
    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: vm.subjectNote)
                .font(.caption)
                .foregroundStyle(Color.hx(.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
            if let origin = vm.originNote {
                Text(verbatim: origin)
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(verbatim: vm.totalNote)
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One recorded call.
///
/// The console's four detail columns and its expandable payload pane (`index.tsx:293-331,534-559`) become one
/// card. The one place this screen is deliberately not the console: the drawer hides its expander when every
/// optional payload column came back empty (`index.tsx:535`), which leaves「capture is off on this deployment」
/// (`ToolMetrics.swift:126-127`) indistinguishable from「this call carried nothing」. The row says which.
struct CallInvocationCard: View {
    let row: CallInvocationDisplayRow
    @State private var expanded = false

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                headline
                bylines
                if let failure = row.failure {
                    Text(verbatim: hx("monitor.calls.row.failure", failure))
                        .font(.caption)
                        .foregroundStyle(Color.hx(.danger))
                        .fixedSize(horizontal: false, vertical: true)
                }
                payload
            }
        }
    }

    private var headline: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            HXStatusDot(tone: row.outcomeTone)
            Text(verbatim: row.stamp)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.hx(.textPrimary))
            HXChip(row.outcome, tone: row.outcomeTone)
            Spacer(minLength: 8)
            Text(verbatim: row.duration)
                .font(.caption)
                .foregroundStyle(Color.hx(.textSecondary))
        }
    }

    /// The four columns behind the outcome, each with its own word in front: a bare id or name under no heading
    /// could be any of them.
    @ViewBuilder
    private var bylines: some View {
        VStack(alignment: .leading, spacing: 3) {
            byline("monitor.calls.row.tool", row.tool)
            byline("monitor.calls.row.origin", row.origin)
            byline("monitor.calls.row.session", row.session)
            byline("monitor.calls.row.agent", row.agent)
        }
    }

    @ViewBuilder
    private func byline(_ key: String, _ value: String?) -> some View {
        if let value {
            Text(verbatim: hx(key, value))
                .font(.caption)
                .foregroundStyle(Color.hx(.textSecondary))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private var payload: some View {
        if row.carriesPayload {
            Button {
                expanded.toggle()
            } label: {
                HXText(expanded ? "monitor.calls.row.collapse" : "monitor.calls.row.expand")
                    .font(.caption)
                    .foregroundStyle(Color.hx(.brand))
            }
            .buttonStyle(.plain)
            if expanded {
                ForEach(row.payloadLines) { line in
                    VStack(alignment: .leading, spacing: 3) {
                        HXText(line.titleKey)
                            .font(.caption2)
                            .foregroundStyle(Color.hx(.textTertiary))
                        Text(verbatim: line.value)
                            .font(line.isCode ? .system(.caption, design: .monospaced) : .caption)
                            .foregroundStyle(Color.hx(.textPrimary))
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        } else {
            Text(verbatim: hx("monitor.calls.row.payload.off"))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
