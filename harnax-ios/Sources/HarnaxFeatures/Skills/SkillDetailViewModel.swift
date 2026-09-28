import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// Which of the two tabs the detail screen shows. The console has the same pair
/// (`harnax-webui/src/pages/skill/detail.tsx:291-354` markdown, `:355-523` resources).
public enum SkillDetailTab: Int, CaseIterable, Identifiable, Sendable {
    case body
    case files

    public var id: Int { rawValue }
    public var titleKey: String {
        switch self {
        case .body: return "skill.detail.tab.body"
        case .files: return "skill.detail.tab.files"
        }
    }
}

/// C5 — one skill, read once.
///
/// The whole screen has a single data source: `GET /api/admin/skills/{id}` carries `skillmd` *and* the
/// `resources` blob, so there is no second fetch per file and none is modelled here
/// (`specs/04-context-domains.md` "规格修正（以代码为准）"). Everything below the parse is local.
@MainActor
public final class SkillDetailViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case ready
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var skill: SkillItem?
    @Published public private(set) var tree: [SkillFileNode] = []
    @Published public private(set) var expanded: Set<String> = []
    @Published public private(set) var selection: String?
    @Published public var tab: SkillDetailTab = .body

    private let skillID: Int64
    private let skills: any SkillCataloging
    /// The flat map behind the tree, kept so a row lookup does not re-parse the JSON blob.
    private var files: [String: String] = [:]

    public init(id: Int64, skills: any SkillCataloging) {
        self.skillID = id
        self.skills = skills
    }

    public var rows: [SkillFileRow] { SkillFileTree.flatten(tree, expanded: expanded) }
    public var hasFiles: Bool { !tree.isEmpty }
    public var fileCount: Int { files.count }

    /// The body text. Rendered as monospaced plain text this round: `AttributedString(markdown:)` only
    /// covers inline syntax, so tables, code blocks and lists come out wrong, and a renderer of our own is a
    /// separate enhancement (`specs/04-context-domains.md` "iOS 适配注意点 2").
    public var body: String? {
        guard let raw = hxPresented(skill?.skillmd) else { return nil }
        return raw
    }

    public var selectedContent: String? {
        guard let selection else { return nil }
        return SkillFileTree.content(of: selection, in: files)
    }

    public var selectedLanguage: String? {
        selection.flatMap { SkillFileLanguage.of(path: $0) }
    }

    /// Page header: source name, then address and branch — the same order as the console's
    /// `DetailPageHeader` (`detail.tsx:560-582`).
    public var sourceName: String? { hxPresented(skill?.repositoryName) }
    public var sourceAddress: String? { hxPresented(skill?.repositoryUrl) }
    public var sourceBranch: String? { hxPresented(skill?.repositoryBranch) }
    public var title: String? { skill?.title }

    public func load() async {
        phase = .loading
        switch await skills.skill(id: skillID) {
        case let .success(item):
            skill = item
            files = item.resourceFiles
            tree = SkillFileTree.build(from: files)
            // The console selects the first key after parsing (`detail.tsx:192-224`); doing the same keeps
            // the content pane from opening empty.
            selection = SkillFileTree.filePaths(tree).first
            expanded = []
            phase = .ready
        case let .failure(error):
            phase = .failed(ErrorMessage.text(for: error))
        }
    }

    /// A folder row toggles its subtree; a file row becomes the selection.
    public func select(_ row: SkillFileRow) {
        guard row.isDir else {
            selection = row.path
            return
        }
        if expanded.contains(row.path) {
            expanded.remove(row.path)
        } else {
            expanded.insert(row.path)
        }
    }

    public func isSelected(_ row: SkillFileRow) -> Bool { selection == row.path }

    public func expandAll() {
        expanded = SkillFileTree.directoryPaths(tree)
    }

    public func collapseAll() {
        expanded = []
    }

    /// The path line of the selection, with the folder prefix cut off — the row already shows the name, so
    /// the pane above the text says where it came from.
    public var selectedFolder: String? {
        guard let selection else { return nil }
        let parts = selection.split(separator: "/").dropLast()
        guard !parts.isEmpty else { return nil }
        return parts.joined(separator: "/")
    }
}

/// Extension-to-language naming, ported from the console's highlighter map
/// (`harnax-webui/src/pages/skill/detail.tsx:35-61`). iOS does not colour the text this round, so the label
/// is a chip rather than a lexer choice — but the mapping still has to agree with the web page, and a test
/// pins it.
public enum SkillFileLanguage {
    /// Label as the console spells it: a proper noun, so it stays verbatim rather than becoming a copy key.
    public static func of(path: String) -> String? {
        let name = path.split(separator: "/").last.map(String.init) ?? path
        guard let dot = name.lastIndex(of: ".") else {
            switch name.lowercased() {
            case "dockerfile": return "Dockerfile"
            case "makefile": return "Makefile"
            default: return nil
            }
        }
        let ext = String(name[name.index(after: dot)...]).lowercased()
        switch ext {
        case "md", "markdown": return "Markdown"
        case "py": return "Python"
        case "js", "mjs", "cjs": return "JavaScript"
        case "ts", "tsx": return "TypeScript"
        case "json": return "JSON"
        case "yaml", "yml": return "YAML"
        case "sh", "bash", "zsh": return "Shell"
        case "swift": return "Swift"
        case "kt", "kts": return "Kotlin"
        case "java": return "Java"
        case "go": return "Go"
        case "rs": return "Rust"
        case "toml": return "TOML"
        case "html": return "HTML"
        case "css": return "CSS"
        case "txt", "text": return "Text"
        default: return ext.uppercased()
        }
    }
}
