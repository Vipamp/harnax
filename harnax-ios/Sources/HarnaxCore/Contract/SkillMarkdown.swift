import Foundation

/// The Markdown body of a `SKILL.md`, with its leading metadata block taken off.
///
/// A skill manifest opens with `---`, a `name:`/`description:` pair, `---`. Those lines are the manifest's own
/// header and both skill screens already show each field in its place, so drawing the block as Markdown turns
/// `description:` into prose and puts a rule above the title.
///
/// The judgement is a verbatim mirror of the server's reader (`SkillFileParser.stripFrontmatter`,
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillFileParser.kt:138-143`): a
/// delimiter is a line whose trimmed text is exactly `---`, the block ends at the first one after the opener,
/// and a document whose opener is never closed is returned as stored — an unreadable manifest still has to show
/// what it holds. Keeping the two readers in step is what stops a screen and a loader disagreeing about where
/// the body starts.
public enum SkillMarkdown {

    private static let delimiter = "---"

    public static func body(_ markdown: String) -> String {
        let lines = markdown.components(separatedBy: "\n")
        guard isDelimiter(lines[0]) else { return markdown }
        guard let end = (1..<lines.count).first(where: { isDelimiter(lines[$0]) }) else { return markdown }
        return lines[(end + 1)...].joined(separator: "\n")
    }

    private static func isDelimiter(_ line: String) -> Bool {
        line.trimmingCharacters(in: .whitespacesAndNewlines) == delimiter
    }
}
