import SwiftUI
import HarnaxCore
import HarnaxKit

/// C5 — the skill detail: one read, two tabs, no writes.
///
/// Both tabs come out of the same `GET /api/admin/skills/{id}` answer, so nothing here refetches per file
/// (`detail.tsx:192-224`). The body is Markdown and is drawn as a document by `HXMarkdownText`, which reads
/// it through `HXMarkdownParser`; the files under the tree keep the monospaced pane, because a fetched source
/// file loses its shape when read as prose.
public struct SkillDetailView: View {
    @StateObject private var vm: SkillDetailViewModel

    public init(id: Int64, skills: any SkillCataloging) {
        _vm = StateObject(wrappedValue: SkillDetailViewModel(id: id, skills: skills))
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                switch vm.phase {
                case .loading:
                    HXStateView(.loading)
                case let .failed(message):
                    HXStateView(.error, message: message, retry: { Task { await vm.load() } })
                case .ready:
                    head
                    HXSegmented(
                        SkillDetailTab.allCases.map { HXSegmentOption(id: $0.rawValue, $0.titleKey) },
                        selection: Binding(
                            get: { vm.tab.rawValue },
                            set: { vm.tab = SkillDetailTab(rawValue: $0) ?? .body }
                        )
                    )
                    if vm.tab == .body { body_ } else { files }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
        .harnaxScreen()
        .navigationTitle(Text(verbatim: vm.title ?? ""))
#if canImport(UIKit)
        .navigationBarTitleDisplayMode(.inline)
#endif
        .task { await vm.load() }
    }

    // MARK: - head

    private var head: some View {
        HXGroupCard {
            if let description = hxPresented(vm.skill?.description) {
                HXRow(text: description, divider: true)
            }
            if let source = vm.sourceName {
                HXRow("skill.detail.source", subtitle: source, systemImage: "books.vertical", divider: vm.sourceAddress != nil)
            }
            if let address = vm.sourceAddress {
                HXRow(text: address, subtitle: vm.sourceBranch, systemImage: "network", divider: false)
            }
        }
    }

    // MARK: - body tab

    @ViewBuilder
    private var body_: some View {
        if let text = vm.body {
            // SKILL.md is prose with headings, fences, task lists and tables in it; the console renders it
            // with ReactMarkdown (`detail.tsx:291-354`) and this is the same document, not a source file.
            HXMarkdownText(text)
        } else {
            HXStateView(.empty, message: hx("skill.detail.noBody"))
        }
    }

    // MARK: - files tab

    @ViewBuilder
    private var files: some View {
        if !vm.hasFiles {
            HXStateView(.empty, message: hx("skill.detail.noFiles"))
        } else {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    HXChip(hx("skill.detail.fileCount", vm.fileCount), tone: .teal)
                    Spacer(minLength: 0)
                    Button { vm.collapseAll() } label: { HXText("skill.detail.collapse") }
                        .buttonStyle(.hxInline)
                    Button { vm.expandAll() } label: { HXText("skill.detail.expand") }
                        .buttonStyle(.hxInline)
                }
                HXGroupCard {
                    ForEach(vm.rows) { row in
                        treeRow(row)
                    }
                }
                // The body opens under the tree rather than behind a navigation push: coming back out of a
                // file must not cost the operator the expansion they just built.
                if let selection = vm.selection {
                    SkillFilePane(
                        path: selection,
                        folder: vm.selectedFolder,
                        content: vm.selectedContent ?? "",
                        language: vm.selectedLanguage
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func treeRow(_ row: SkillFileRow) -> some View {
        Button { vm.select(row) } label: {
            HStack(spacing: 8) {
                // The indent is the geometry the pure builder computed; nothing here re-derives depth.
                Spacer(minLength: CGFloat(row.depth) * 14)
                Image(systemName: glyph(for: row))
                    .foregroundStyle(Color.hx(row.isDir ? .indigo : .textTertiary))
                Text(verbatim: row.name)
                    .font(.subheadline)
                    .foregroundStyle(Color.hx(vm.isSelected(row) ? .brand : .textPrimary))
                    .lineLimit(1)
                if row.isDir {
                    HXChip("\(row.childCount)", tone: .purple)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func glyph(for row: SkillFileRow) -> String {
        guard row.isDir else { return "doc.text" }
        return vm.expanded.contains(row.path) ? "folder.fill" : "folder"
    }
}

/// A file body: monospaced, horizontally scrollable so a long line keeps its shape, and selectable so the
/// whole text can be copied out.
struct SkillCodeText: View {
    let text: String

    var body: some View {
        ScrollView(.horizontal) {
            Text(verbatim: text)
                .font(.callout.monospaced())
                .foregroundStyle(Color.hx(.textPrimary))
                .textSelection(.enabled)
                .padding(12)
        }
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// The selected resource file, shown inline under the tree.
///
/// Kept out of the navigation stack on purpose: the tree is the operator's working set, and pushing a page
/// over it would throw the expansion state away every time they peek at one file
/// (`specs/04-context-domains.md` §2).
struct SkillFilePane: View {
    let path: String
    let folder: String?
    let content: String
    let language: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    HXValueText((path as NSString).lastPathComponent, lines: 2)
                    if let folder {
                        HXValueText(folder, lines: 1)
                    }
                }
                if let language {
                    HXChip(language, tone: .indigo)
                }
                Spacer(minLength: 0)
            }
            SkillCodeText(text: content)
        }
        .padding(12)
        .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
