import enPages from '@/locales/en-US/pages';
import zhPages from '@/locales/zh-CN/pages';
import { readOutcome, refusalOf, sessionSkillsFor } from './SessionSkillsDrawer';

const queue = (records: { name: string; description?: string | null }[]) => ({
  code: 200,
  message: 'ok',
  data: { pageNum: 1, pageSize: 50, total: records.length, records },
});

const zone = (rows: { name: string; enabledAt?: string | null }[]) => ({ code: 200, message: 'ok', data: rows });

/**
 * A refusal on the envelope, which is how both a guard rejection and a business failure reach the panel. `data` stays
 * null for the plain cases, but a failure envelope can also carry a payload — the last page the server had read — and
 * that half still owns no row in the merge.
 */
const refused = (code: number, data: any = null): any => ({ code, message: 'refused', data });

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

  it('names the five refusals apart by the locale id the drawer renders', () => {
    expect(refusalOf(403).id).toBe('pages.session.skills.refusal.dangerous');
    expect(refusalOf(409).id).toBe('pages.session.skills.refusal.limit');
    expect(refusalOf(410).id).toBe('pages.session.skills.refusal.noSandbox');
    expect(refusalOf(404).id).toBe('pages.session.skills.refusal.noDraft');
    expect(refusalOf(500).id).toBe('pages.session.skills.refusal.container');
  });

  it('lands a code it does not know on the copy that blames nobody', () => {
    expect(refusalOf(0).id).toBe('pages.session.skills.refusal.unknown');
    expect(refusalOf(429).id).toBe('pages.session.skills.refusal.unknown');
    // An undocumented code must not claim the draft is gone — it may only mean no sandbox or an expired login.
    expect(refusalOf(429).id).not.toBe(refusalOf(404).id);
    expect(refusalOf(0).id).not.toBe(refusalOf(410).id);
  });

  it('supplies both halves of the message for every code, known or not', () => {
    for (const code of [403, 409, 410, 404, 500, 0, 429]) {
      const refusal = refusalOf(code);
      // The drawer renders id + defaultMessage from one table entry; either half missing renders an empty toast.
      expect(refusal.id.startsWith('pages.session.skills.')).toBe(true);
      expect(refusal.en.length).toBeGreaterThan(0);
      expect(Object.hasOwn(zhPages, refusal.id)).toBe(true);
      expect(Object.hasOwn(enPages, refusal.id)).toBe(true);
    }
  });

  it('calls the session empty only when both reads answered', () => {
    expect(readOutcome(queue([]), zone([]))).toEqual({ unavailable: false, rows: [] });
    expect(readOutcome(queue([{ name: 'a' }]), zone([{ name: 'a', enabledAt: 'x' }]))).toEqual({
      unavailable: false,
      rows: [{ name: 'a', description: null, enabled: true, enabledAt: 'x' }],
    });
  });

  it('says the read failed rather than that this session wrote nothing', () => {
    // A guard 403 on the queue is a login problem, not an empty session.
    expect(readOutcome(refused(403), zone([]))).toEqual({ unavailable: true, rows: [] });
    // A transport error answers with no code worth naming, and must not be reported as an empty session either.
    expect(readOutcome(refused(0), refused(0))).toEqual({ unavailable: true, rows: [] });
  });

  it('keeps the half that answered when the other one refuses', () => {
    const nominations = readOutcome(queue([{ name: 'invoice-fill', description: 'fills' }]), refused(500));
    expect(nominations.unavailable).toBe(true);
    expect(nominations.rows).toEqual([
      { name: 'invoice-fill', description: 'fills', enabled: false, enabledAt: null },
    ]);

    const enabled = readOutcome(refused(401), zone([{ name: 'gone', enabledAt: 'x' }]));
    expect(enabled.unavailable).toBe(true);
    expect(enabled.rows).toEqual([{ name: 'gone', description: null, enabled: true, enabledAt: 'x' }]);
  });

  it('drops a refused half that still carries a payload, on both sides of the merge', () => {
    // Trusting `data` whatever the code says is the defect this pins: a failed read is not an answer, so the rows
    // riding on its envelope are stale and must not reach the panel next to the half that did answer.
    const staleQueue = refused(500, {
      pageNum: 1,
      pageSize: 50,
      total: 1,
      records: [{ name: 'ghost', description: 'stale' }],
    });
    const staleZone = refused(401, [{ name: 'phantom', enabledAt: 'x' }]);

    expect(readOutcome(staleQueue, zone([]))).toEqual({ unavailable: true, rows: [] });
    expect(readOutcome(queue([]), staleZone)).toEqual({ unavailable: true, rows: [] });
    expect(readOutcome(staleQueue, staleZone)).toEqual({ unavailable: true, rows: [] });

    expect(readOutcome(staleQueue, zone([{ name: 'gone', enabledAt: 'x' }])).rows).toEqual([
      { name: 'gone', description: null, enabled: true, enabledAt: 'x' },
    ]);
    expect(readOutcome(queue([{ name: 'invoice-fill', description: 'fills' }]), staleZone).rows).toEqual([
      { name: 'invoice-fill', description: 'fills', enabled: false, enabledAt: null },
    ]);

    // A half that rejected at the HTTP level reaches this decision as exactly this synthetic envelope — no code to
    // name, no payload — and it must read as "did not answer" while the other half keeps contributing its rows.
    expect(readOutcome({ code: 0 }, zone([{ name: 'gone', enabledAt: 'x' }]))).toEqual({
      unavailable: true,
      rows: [{ name: 'gone', description: null, enabled: true, enabledAt: 'x' }],
    });
    expect(readOutcome(queue([{ name: 'invoice-fill', description: 'fills' }]), { code: 0 })).toEqual({
      unavailable: true,
      rows: [{ name: 'invoice-fill', description: 'fills', enabled: false, enabledAt: null }],
    });
  });

  it('hands the read path no copy of its own, so it cannot render a refusal code', () => {
    // Listing is refused only by the guard or the transport; saying "the scan calls it DANGEROUS" here would
    // send the operator to the review queue. The read half therefore reports a flag, never a message.
    expect(Object.keys(readOutcome(refused(403), refused(500))).sort()).toEqual(['rows', 'unavailable']);
    expect(Object.keys(readOutcome(queue([]), zone([]))).sort()).toEqual(['rows', 'unavailable']);
  });
});
