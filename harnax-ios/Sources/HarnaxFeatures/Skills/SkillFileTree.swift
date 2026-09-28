import Foundation
import HarnaxCore

/// A node of the resource tree built from a skill's flat `path -> content` map.
///
/// There is no tree on the wire: `resources` is a JSON object whose keys carry the directories, and the
/// console builds the structure itself by splitting on `/`
/// (`harnax-webui/src/pages/skill/detail.tsx:231-280`). Building it here as a pure function is what makes
/// the geometry testable without a view.
public struct SkillFileNode: Equatable, Identifiable, Sendable {
    public let name: String
    /// The full key for a file, the joined prefix for a folder.
    public let path: String
    public let isDir: Bool
    public var children: [SkillFileNode]

    public var id: String { path }

    public init(name: String, path: String, isDir: Bool, children: [SkillFileNode] = []) {
        self.name = name
        self.path = path
        self.isDir = isDir
        self.children = children
    }
}

/// One visible line of the outline, with the indent it renders at.
public struct SkillFileRow: Equatable, Identifiable, Sendable {
    public let node: SkillFileNode
    public let depth: Int
    /// Folders carry this so the disclosure chip can say how much sits behind them.
    public let childCount: Int

    public var id: String { node.id }
    public var isDir: Bool { node.isDir }
    public var name: String { node.name }
    public var path: String { node.path }
}

public enum SkillFileTree {
    /// Builds the tree from the flat map. Empty and `.` segments are dropped. A key that is both a file and
    /// a directory prefix becomes the folder row, and its own body stays reachable through `content(of:)`,
    /// which looks the path up in the flat map rather than in the tree.
    public static func build(from files: [String: String]) -> [SkillFileNode] {
        build(from: files, prefix: "")
    }

    /// The rows to render, given which folders are open. A collapsed folder still yields its own row — it
    /// just does not descend — which is the whole point of keeping this out of the view.
    public static func flatten(_ nodes: [SkillFileNode], expanded: Set<String> = []) -> [SkillFileRow] {
        var rows: [SkillFileRow] = []
        walk(nodes, depth: 0, expanded: expanded, into: &rows)
        return rows
    }

    /// Every folder path in the tree: what "expand all" hands to `flatten`.
    public static func directoryPaths(_ nodes: [SkillFileNode]) -> Set<String> {
        var out = Set<String>()
        for node in nodes where node.isDir {
            out.insert(node.id)
            out.formUnion(directoryPaths(node.children))
        }
        return out
    }

    /// The file paths, in render order — enough to pick the first entry the way the console does
    /// (`detail.tsx:192-224` selects the first key after parsing).
    public static func filePaths(_ nodes: [SkillFileNode]) -> [String] {
        var out: [String] = []
        for node in nodes {
            if node.isDir { out.append(contentsOf: filePaths(node.children)) } else { out.append(node.id) }
        }
        return out
    }

    /// A single file's content by path, so the detail view never re-parses the JSON blob per row.
    public static func content(of path: String, in files: [String: String]) -> String? {
        if let direct = files[path] { return direct }
        // A folder row that also names a file has no content of its own; the lookup stays path-exact.
        return nil
    }

    // MARK: - building

    private static func build(from files: [String: String], prefix: String) -> [SkillFileNode] {
        struct Bucket {
            var children: [String: String] = [:]
        }
        var buckets: [String: Bucket] = [:]
        for (key, content) in files {
            let segments = segments(of: key)
            guard let first = segments.first else { continue }
            if buckets[first] == nil { buckets[first] = Bucket() }
            guard segments.count > 1 else { continue }
            var bucket = buckets[first] ?? Bucket()
            bucket.children[join(segments.dropFirst())] = content
            buckets[first] = bucket
        }

        return buckets.keys.sorted(by: sortsBefore).map { name in
            let bucket = buckets[name] ?? Bucket()
            let path = prefix.isEmpty ? name : "\(prefix)/\(name)"
            guard !bucket.children.isEmpty else {
                return SkillFileNode(name: name, path: path, isDir: false)
            }
            return SkillFileNode(
                name: name,
                path: path,
                isDir: true,
                children: build(from: bucket.children, prefix: path)
            )
        }
    }

    private static func segments(of key: String) -> [String] {
        key.split(separator: "/").map(String.init).filter { !$0.isEmpty && $0 != "." }
    }

    private static func join(_ segments: ArraySlice<String>) -> String {
        segments.joined(separator: "/")
    }

    /// Directories first, then case-insensitive name order, with the raw name as the tiebreak so the
    /// ordering is total and a test can pin it.
    private static func sortsBefore(_ lhs: String, _ rhs: String) -> Bool {
        let lowered = lhs.lowercased()
        let other = rhs.lowercased()
        if lowered != other { return lowered < other }
        return lhs < rhs
    }

    private static func walk(
        _ nodes: [SkillFileNode],
        depth: Int,
        expanded: Set<String>,
        into rows: inout [SkillFileRow]
    ) {
        for node in nodes {
            rows.append(SkillFileRow(node: node, depth: depth, childCount: node.children.count))
            guard node.isDir, expanded.contains(node.id) else { continue }
            walk(node.children, depth: depth + 1, expanded: expanded, into: &rows)
        }
    }
}
