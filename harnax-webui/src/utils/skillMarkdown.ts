/**
 * The frontmatter delimiter `SKILL.md` opens and closes its metadata block with.
 *
 * A line counts as a delimiter when its trimmed text is exactly these three characters, which is the same
 * test the server's own reader makes.
 */
const FRONTMATTER_DELIMITER = '---';

/**
 * The Markdown body of a `SKILL.md`, with its leading metadata block removed.
 *
 * A skill manifest starts with `---`, a `name:`/`description:` pair, `---`; that block is the manifest's
 * header, not prose, and the screens that show a skill already carry both fields in their own header row.
 * Rendering the block as Markdown is what turns a `description:` line into a heading and eats the rest.
 *
 * The judgement is a verbatim mirror of the server's reader (`SkillFileParser.stripFrontmatter`,
 * harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillFileParser.kt:138-143) so a
 * screen and a loader never disagree about where the body starts: no opener, or an opener that is never
 * closed, means the document is shown exactly as stored — an unclosed block is a broken skill, not a
 * licence to drop the whole page.
 */
export function stripSkillFrontmatter(markdown: string): string {
  const lines = markdown.split('\n');
  if (lines[0].trim() !== FRONTMATTER_DELIMITER) return markdown;
  const endIndex = lines.findIndex((line, index) => index > 0 && line.trim() === FRONTMATTER_DELIMITER);
  if (endIndex < 0) return markdown;
  return lines.slice(endIndex + 1).join('\n');
}

/**
 * What a skill screen hands its Markdown renderer.
 *
 * Absent content renders as nothing rather than as `undefined`, which ReactMarkdown would reject.
 */
export function skillBodyForRendering(skillmd?: string | null): string {
  return stripSkillFrontmatter(skillmd ?? '');
}
