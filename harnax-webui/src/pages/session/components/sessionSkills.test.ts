import { sessionSkillsFor, refusalMessage } from './SessionSkillsDrawer';

describe('the session skill panel', () => {
  it('offers a draft that is not enabled yet and marks one that is', () => {
    const rows = sessionSkillsFor(
      [
        { name: 'invoice-fill', description: 'fills' },
        { name: 'other', description: null },
      ],
      [{ name: 'invoice-fill', enabledAt: '2026-10-08T10:00:00Z' }],
    );
    expect(rows).toEqual([
      { name: 'invoice-fill', description: 'fills', enabled: true, enabledAt: '2026-10-08T10:00:00Z' },
      { name: 'other', description: null, enabled: false, enabledAt: null },
    ]);
  });

  it('keeps an enabled skill whose draft the agent has already rewritten out of the queue', () => {
    const rows = sessionSkillsFor([], [{ name: 'gone', enabledAt: 'x' }]);
    expect(rows).toEqual([{ name: 'gone', description: null, enabled: true, enabledAt: 'x' }]);
  });

  it('names the four refusals apart', () => {
    expect(refusalMessage(403)).toContain('DANGEROUS');
    expect(refusalMessage(409)).toContain('already');
    expect(refusalMessage(410)).toContain('sandbox');
    expect(refusalMessage(500)).toContain('refused');
    expect(refusalMessage(404)).toContain('no draft');
  });
});
