import SwiftUI
import HarnaxCore
import HarnaxKit

/// One line of a drill-down, already resolved to the words the current language shows.
///
/// Agents and teams both expand a row into named bindings with a subtitle and marks, so the shape lives
/// once here and each domain keeps only its own naming rules.
struct HXBindingRow: Equatable {
    let title: String
    let subtitle: String?
    let badges: [String]
}

struct HXBindingSection: Equatable {
    let titleKey: String
    let rows: [HXBindingRow]
}

/// The sections as a scrolling column of group cards.
///
/// A presenter is expected to drop a dimension it has no rows for, so an empty section never reaches here.
struct HXBindingListView: View {
    let sections: [HXBindingSection]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                    VStack(alignment: .leading, spacing: 0) {
                        HXSectionHeader(section.titleKey)
                        HXGroupCard {
                            ForEach(Array(section.rows.enumerated()), id: \.offset) { index, row in
                                HXRow(
                                    text: row.title,
                                    subtitle: row.subtitle,
                                    divider: index < section.rows.count - 1
                                ) {
                                    if row.badges.isEmpty {
                                        EmptyView()
                                    } else {
                                        HXFlow(spacing: 6) {
                                            ForEach(Array(row.badges.enumerated()), id: \.offset) { _, mark in
                                                HXChip(mark, tone: .purple)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 20)
        }
    }
}

/// Sheet chrome both drill-downs share: a title that is the row's own name and one close button.
struct HXBindingSheet<Content: View>: View {
    private let title: String
    private let content: Content
    @Environment(\.dismiss) private var dismiss

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        NavigationStack {
            content
                .harnaxScreen()
                .navigationTitle(Text(verbatim: title))
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
