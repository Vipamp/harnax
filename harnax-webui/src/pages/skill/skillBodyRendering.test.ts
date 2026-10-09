import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The two skill screens hand their SKILL.md to ReactMarkdown, and the metadata block at the top of that file is
 * not prose. `stripSkillFrontmatter` (`src/utils/skillMarkdown.ts`) owns the rule; this file owns the wiring,
 * because neither page mounts in this repo's jest setup (a page needs the umi model, the intl provider and a
 * signed-in envelope around it) and the rule is worth nothing if a render site goes back to the raw field.
 *
 * Each gate is a falsifier on the exact expression that used to sit in the renderer: a rewire that drops the
 * strip fails here rather than in front of a reviewer.
 */
const sourceOf = (page: string) => readFileSync(join(__dirname, page), 'utf8');

describe('the screens that render a SKILL.md', () => {
  it('shows the detail page with its metadata block removed', () => {
    const source = sourceOf('detail.tsx');

    expect(source).toContain('skillBodyForRendering(');
    expect(source).not.toContain('{skillInfo.skillmd}');
  });

  it('shows the draft review page with its metadata block removed', () => {
    const source = sourceOf('draftDetail.tsx');

    expect(source).toContain('skillBodyForRendering(');
    expect(source).not.toContain("{draft.skillmd || ''}");
  });
});
