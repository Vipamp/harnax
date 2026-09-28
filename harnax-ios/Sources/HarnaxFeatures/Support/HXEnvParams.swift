import SwiftUI
import HarnaxCore
import HarnaxKit

/// The purple parameter-count chip a tool or server row carries, and the sheet behind it.
///
/// Web: `harnax-webui/src/components/EnvParamsPopover/index.tsx:24` takes the count from `entries.length`
/// and only falls back to a supplied number when the row carries none; `:94-103` renders the count as a
/// purple tag. A row with nothing to show gets no chip — the count would be a second way of saying
/// "none" that the row already says.
public struct HXEnvParamsChip: View {
    private let entries: [EnvParamEntry]
    private let fallbackCount: Int
    @State private var open = false

    public init(entries: [EnvParamEntry], fallbackCount: Int = 0) {
        self.entries = entries
        self.fallbackCount = fallbackCount
    }

    public var body: some View {
        if count == 0 {
            EmptyView()
        } else {
            Button {
                open = true
            } label: {
                HXChip(hxCount("env.count", count), tone: .purple)
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $open) {
                HXEnvParamsSheet(entries: entries)
            }
        }
    }

    /// Entries win; the fallback is for a row that only reports how many it has.
    private var count: Int { entries.isEmpty ? fallbackCount : entries.count }
}

/// Every declared parameter of one owner: name, purpose, the two flags, and the default when there is one.
/// Sensitive values never reach this sheet in clear — the backend masks them.
struct HXEnvParamsSheet: View {
    let entries: [EnvParamEntry]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                    card(for: entry)
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(Text(verbatim: hx("env.title")))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        dismiss()
                    } label: {
                        HXText("common.confirm")
                    }
                }
            }
            .harnaxScreen()
        }
    }

    private func card(for entry: EnvParamEntry) -> some View {
        HXGroupCard {
            HXRow(
                text: entry.envParamName,
                subtitle: entry.description,
                systemImage: "textformat.abc",
                divider: entry.defaultValue != nil
            ) {
                HStack(spacing: 6) {
                    if entry.required { HXBadge("env.required", tone: .danger) }
                    if entry.secret { HXBadge("env.sensitive", tone: .warning) }
                }
            }
            if let defaultValue = entry.defaultValue {
                HXRow(
                    text: hx("env.default"),
                    subtitle: nil,
                    systemImage: "equal.circle",
                    divider: false
                ) {
                    HXValueText(defaultValue)
                }
            }
        }
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
    }
}
