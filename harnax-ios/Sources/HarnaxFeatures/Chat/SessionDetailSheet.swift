import SwiftUI
import HarnaxCore
import HarnaxKit

/// The conversation's read-only detail sheet — this app's equivalent of the console's `DetailModal`.
///
/// Four panels, in the console's order, and only the ones with data: 基本信息, 执行者 (or 团队主管 for a team
/// conversation), 技能, 外部服务.
///
/// The console shows seven. Its tools, cli and members panels are filled from a second by-id request
/// (`getAgentById` / `getTeamById`, `harnax-webui/src/pages/session/components/DetailModal.tsx:114-144`), and
/// this app makes no by-id agent or team read at all — the page row already carries what it carries
/// (`Sources/HarnaxCore/Contract/Facades.swift:148-151`). Those three panels are therefore absent here rather
/// than present and empty, which is the honest reading of a row that has no such list.
public struct SessionDetailSheet: View {
    @StateObject private var vm: SessionDetailViewModel

    public init(session: SessionSummary) {
        _vm = StateObject(wrappedValue: SessionDetailViewModel(session: session))
    }

    public var body: some View {
        HXBindingSheet(title: hx("chat.detail.title")) {
            content
        }
    }

    @ViewBuilder
    private var content: some View {
        if vm.hasNothingToShow {
            // Reachable: every column of `SessionSummary` is optional, so a row answered with only an `id`
            // opens a sheet with nothing to say.
            HXStateView(.empty, message: hx("chat.detail.empty"))
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(Array(vm.sections.enumerated()), id: \.offset) { _, section in
                        VStack(alignment: .leading, spacing: 0) {
                            HXSectionHeader(section.titleKey)
                            HXGroupCard {
                                ForEach(Array(section.rows.enumerated()), id: \.offset) { index, row in
                                    rowView(row, divided: index < section.rows.count - 1)
                                }
                            }
                        }
                    }
                    if let updated = vm.updatedLine {
                        Text(verbatim: updated)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 20)
            }
        }
    }

    @ViewBuilder
    private func rowView(_ row: SessionDetailRow, divided: Bool) -> some View {
        switch row.layout {
        case .inlineValue:
            if let labelKey = row.labelKey {
                HXRow(labelKey, divider: divided) {
                    trailing(row)
                }
            } else {
                entry(row, divided: divided)
            }
        case .stackedValue:
            if let labelKey = row.labelKey {
                HXRow(labelKey, subtitle: row.value, divider: divided) {
                    marksView(row.marks)
                }
            } else {
                entry(row, divided: divided)
            }
        case .code:
            codeBlock(row, divided: divided)
        case let .paragraph(expandable):
            promptBlock(row, expandable: expandable, divided: divided)
        }
    }

    /// A skill or an MCP server: the server's own word as the title, its description under it.
    private func entry(_ row: SessionDetailRow, divided: Bool) -> some View {
        HXRow(
            text: row.value ?? "",
            subtitle: row.detail,
            divider: divided
        ) {
            marksView(row.marks)
        }
    }

    @ViewBuilder
    private func trailing(_ row: SessionDetailRow) -> some View {
        if row.marks.isEmpty {
            valueText(row.value)
        } else if let value = row.value {
            HStack(spacing: 8) {
                valueText(value)
                marksView(row.marks)
            }
        } else {
            marksView(row.marks)
        }
    }

    /// A field's own value. Never rendered for a row that has none — the sheet hides the line instead of
    /// printing a placeholder.
    @ViewBuilder
    private func valueText(_ value: String?) -> some View {
        if let value {
            Text(verbatim: value)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textPrimary))
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private func marksView(_ marks: [SessionDetailMark]) -> some View {
        if marks.isEmpty {
            EmptyView()
        } else {
            HXFlow(spacing: 6) {
                ForEach(Array(marks.enumerated()), id: \.offset) { _, mark in
                    switch mark {
                    case let .badge(key, tone):
                        HXBadge(key, tone: tone)
                    case let .chip(text, tone):
                        HXChip(text, tone: tone)
                    }
                }
            }
        }
    }

    /// The business key at full width: it is long, it is monospaced, and it is the one value worth taking
    /// off the screen. `HXValueText` truncates it in the middle; the button hands over the whole string.
    private func codeBlock(_ row: SessionDetailRow, divided: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(row.labelKey)
            HStack(alignment: .center, spacing: 8) {
                HXValueText(row.value ?? "", lines: 2)
                Button {
                    if let value = row.value { HXPasteboard.copy(value) }
                } label: {
                    Image(systemName: "doc.on.doc")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: hx("common.copy")))
            }
            marksView(row.marks)
        }
        .blockPadding(divided: divided)
    }

    /// The system prompt. Collapsed to four lines with the console's expand control
    /// (`DetailModal.tsx:266-278`).
    private func promptBlock(_ row: SessionDetailRow, expandable: Bool, divided: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(row.labelKey)
            Text(verbatim: row.value ?? "")
                .font(.caption.monospaced())
                .foregroundStyle(Color.hx(.textPrimary))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            if expandable {
                Button {
                    vm.togglePrompt()
                } label: {
                    HXText(vm.isPromptExpanded ? "chat.detail.prompt.collapse" : "chat.detail.prompt.expand")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.hx(.brand))
                }
                .buttonStyle(.plain)
            }
        }
        .blockPadding(divided: divided)
    }

    @ViewBuilder
    private func fieldLabel(_ key: String?) -> some View {
        if let key {
            HXText(key)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }
}

/// The padding and hairline `HXRow` gives its own rows, so a block that is not an `HXRow` still lines up
/// with the ones that are.
private extension View {
    func blockPadding(divided: Bool) -> some View {
        padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .bottom) {
                if divided {
                    Color.hx(.separator).frame(height: 1)
                }
            }
    }
}
