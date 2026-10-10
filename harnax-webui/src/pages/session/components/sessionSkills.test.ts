import enPages from '@/locales/en-US/pages';
import zhPages from '@/locales/zh-CN/pages';
import { readCopy, readOutcome, refusalOf, sessionSkillsFor } from './SessionSkillsDrawer';
import { enableGate, scopedSessionId } from './sessionSkills';

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
    expect(readOutcome(queue([]), zone([]))).toEqual({ unavailable: false, noSandbox: false, rows: [] });
    expect(readOutcome(queue([{ name: 'a' }]), zone([{ name: 'a', enabledAt: 'x' }]))).toEqual({
      unavailable: false,
      noSandbox: false,
      rows: [{ name: 'a', description: null, enabled: true, enabledAt: 'x' }],
    });
  });

  it('says the read failed rather than that this session wrote nothing', () => {
    // A guard 403 on the queue is a login problem, not an empty session.
    expect(readOutcome(refused(403), zone([]))).toEqual({ unavailable: true, noSandbox: false, rows: [] });
    // A transport error answers with no code worth naming, and must not be reported as an empty session either.
    expect(readOutcome(refused(0), refused(0))).toEqual({ unavailable: true, noSandbox: false, rows: [] });
  });

  it('names a stopped sandbox apart from a session that has written nothing', () => {
    // The one code the read leg refuses with. It owns a sentence of its own because the two it sits between both
    // send the operator somewhere wrong: 「读不出来」 points at the login, 「还没有自写的技能」 denies drafts the
    // queue just listed, and only the sandbox copy names the state that a restart of this conversation fixes.
    const stopped = readOutcome(queue([{ name: 'invoice-fill', description: 'fills' }]), refused(410));
    expect(stopped.unavailable).toBe(true);
    expect(stopped.noSandbox).toBe(true);
    expect(stopped.rows).toEqual([
      { name: 'invoice-fill', description: 'fills', enabled: false, enabledAt: null },
    ]);
    // A zone that answered empty is a running container with nothing in it — the state this flag must never claim.
    expect(readOutcome(queue([]), zone([])).noSandbox).toBe(false);
    // And a code that means something else stays on the generic copy, even when it comes off the same leg.
    expect(readOutcome(queue([]), refused(500)).noSandbox).toBe(false);
    expect(readOutcome(refused(410), zone([])).noSandbox).toBe(false);
  });

  it('sends a read that found no sandbox to the sentence the enable already refuses with', () => {
    // Both render sites — the empty state's description and the partial-read toast — take their copy from here, so
    // the read leg cannot invent a second wording for the state the write leg already names.
    expect(readCopy(false).id).toBe('pages.session.skills.loadFailed');
    expect(readCopy(true).id).toBe(refusalOf(410).id);
    expect(readCopy(true).defaultMessage).toBe(refusalOf(410).en);
    for (const copy of [readCopy(false), readCopy(true)]) {
      expect(copy.defaultMessage.length).toBeGreaterThan(0);
      expect(Object.hasOwn(zhPages, copy.id)).toBe(true);
      expect(Object.hasOwn(enPages, copy.id)).toBe(true);
    }
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

    expect(readOutcome(staleQueue, zone([]))).toEqual({ unavailable: true, noSandbox: false, rows: [] });
    expect(readOutcome(queue([]), staleZone)).toEqual({ unavailable: true, noSandbox: false, rows: [] });
    expect(readOutcome(staleQueue, staleZone)).toEqual({ unavailable: true, noSandbox: false, rows: [] });

    expect(readOutcome(staleQueue, zone([{ name: 'gone', enabledAt: 'x' }])).rows).toEqual([
      { name: 'gone', description: null, enabled: true, enabledAt: 'x' },
    ]);
    expect(readOutcome(queue([{ name: 'invoice-fill', description: 'fills' }]), staleZone).rows).toEqual([
      { name: 'invoice-fill', description: 'fills', enabled: false, enabledAt: null },
    ]);

    // A half that rejected at the HTTP level reaches this decision as exactly this synthetic envelope — no code to
    // name, no payload — and it must read as "did not answer" while the other half keeps contributing its rows.
    // A leg that carries no code may not be reported as a stopped sandbox either.
    expect(readOutcome({ code: 0 }, zone([{ name: 'gone', enabledAt: 'x' }]))).toEqual({
      unavailable: true,
      noSandbox: false,
      rows: [{ name: 'gone', description: null, enabled: true, enabledAt: 'x' }],
    });
    expect(readOutcome(queue([{ name: 'invoice-fill', description: 'fills' }]), { code: 0 })).toEqual({
      unavailable: true,
      noSandbox: false,
      rows: [{ name: 'invoice-fill', description: 'fills', enabled: false, enabledAt: null }],
    });
  });

  it('hands the read path no copy of its own, so it cannot render a refusal code', () => {
    // Listing is refused only by the guard or the transport; saying "the scan calls it DANGEROUS" here would
    // send the operator to the review queue. The read half therefore reports flags, never a message — `noSandbox`
    // names which state the drawer has to render, and the copy still comes from the drawer's own table.
    expect(Object.keys(readOutcome(refused(403), refused(500))).sort()).toEqual([
      'noSandbox',
      'rows',
      'unavailable',
    ]);
    expect(Object.keys(readOutcome(queue([]), zone([]))).sort()).toEqual(['noSandbox', 'rows', 'unavailable']);
  });

  it('sends no second enable while the first copy is still in flight', () => {
    // The ten-skill ceiling is counted from the directory the previous copy wrote, so two presses in flight both
    // read the old count and can push the session past its ten. The first press goes out; the second does not.
    expect(enableGate(null, 'invoice-fill', false).maySend).toBe(true);
    expect(enableGate('invoice-fill', 'other', false).maySend).toBe(false);
    expect(enableGate('invoice-fill', 'invoice-fill', false).maySend).toBe(false);
    // Greying out follows the same rule, on every pending row rather than only the one being copied — the shape
    // the iOS panel already holds with `.disabled(vm.isActing)`.
    const other = enableGate('invoice-fill', 'other', false);
    expect(other.disabled).toBe(true);
    expect(other.loading).toBe(false);
    const copying = enableGate('invoice-fill', 'invoice-fill', false);
    expect(copying.disabled).toBe(true);
    expect(copying.loading).toBe(true);
    // An enabled row stays disabled whatever the busy state is, and an idle panel still offers its pending rows.
    expect(enableGate(null, 'invoice-fill', true)).toEqual({ maySend: false, disabled: true, loading: false });
    expect(enableGate(null, 'other', false)).toEqual({ maySend: true, disabled: false, loading: false });
  });

  it('leaves a blank conversation id with no scope to query, and trims one that has it', () => {
    // Blank is not unscoped: the queue read without a `sessionId` answers the reviewer's whole tenant, so an id
    // that is present but whitespace must resolve to nothing the drawer may send rather than to that query.
    expect(scopedSessionId('   ')).toBeNull();
    expect(scopedSessionId('')).toBeNull();
    expect(scopedSessionId(undefined)).toBeNull();
    expect(scopedSessionId(null)).toBeNull();
    expect(scopedSessionId(' ses-1 ')).toBe('ses-1');
    expect(scopedSessionId('ses-1')).toBe('ses-1');
  });
});
