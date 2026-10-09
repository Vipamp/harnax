import { stripSkillFrontmatter } from './skillMarkdown';

/**
 * The two screens that show a skill's SKILL.md — the console's detail page and the draft review page — draw the
 * document with ReactMarkdown. Its first block is not prose: `---`, the `name:`/`description:` pair, `---`. Those
 * lines are the manifest's metadata, which both pages already carry in their own header fields, so the body has to
 * start after the closing delimiter.
 *
 * The rule is a verbatim mirror of the server's own reader (`SkillFileParser.stripFrontmatter`,
 * harnax-admin/.../skill/loader/SkillFileParser.kt:138-143), including where that reader gives up: a delimiter is
 * a line whose trimmed text is exactly `---`, and a block that never closes is not a block.
 */
describe('the SKILL.md body a skill screen renders', () => {
  it('starts after the closing delimiter of the frontmatter block', () => {
    const doc = '---\nname: invoice-mail-review\ndescription: Review inbound invoices\n---\n\n# Invoice\n\nOpen the PDF first.\n';

    expect(stripSkillFrontmatter(doc)).toBe('\n# Invoice\n\nOpen the PDF first.\n');
  });

  it('leaves a document with no frontmatter alone, delimiters inside the body included', () => {
    const doc = '# Invoice\n\nRules to separate:\n\n---\n\nCheck the total.\n\n---\n\nCheck the tax.\n';

    expect(stripSkillFrontmatter(doc)).toBe(doc);
  });

  it('keeps the whole document when the block never closes', () => {
    const doc = '---\nname: broken\n\n# The opener had no closer\n';

    expect(stripSkillFrontmatter(doc)).toBe(doc);
  });

  it('strips the block from a CRLF document and keeps its body', () => {
    const body = stripSkillFrontmatter('---\r\nname: a\r\n---\r\n# Body\r\n');

    expect(body.replace(/\r\n/g, '\n')).toBe('# Body\n');
  });
});
