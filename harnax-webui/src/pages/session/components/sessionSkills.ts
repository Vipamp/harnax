/**
 * Pure judgements behind the session's own skill panel.
 *
 * Out of the drawer for the same reason `contextUsage.ts` is: the two rules that keep this panel truthful — one
 * copy in flight at a time, and no queue read for a conversation whose id names nothing — decide what goes out on
 * the wire, and both are worth pinning without rendering a drawer.
 */

/**
 * The conversation this panel may read and write for, or null when its id is absent or only whitespace.
 *
 * Blank is not unscoped. The queue read without a `sessionId` answers the reviewer's whole tenant
 * (`SkillDraftServiceImpl.page` keeps the null case exactly that way on purpose), so sending one from a
 * conversation panel would put another conversation's pending nominations under this title. A caller that gets
 * null back must not fire the read and must not claim the session wrote nothing — it has to report that the panel
 * could not be read.
 */
export function scopedSessionId(sessionId: string | null | undefined): string | null {
  const trimmed = sessionId?.trim();
  return trimmed ? trimmed : null;
}

/**
 * What one row's enable action may do, given the row the panel is already copying.
 *
 * `maySend` is false for every press once one is in flight: the ten-skill ceiling is counted from the directory the
 * previous copy wrote, so two presses racing each other both see the old count and push the session past its ten —
 * the same reason the iOS panel greys its buttons out (`SessionSkillsSheet.swift`'s `.disabled(vm.isActing)`). It is
 * false for a row the directory already answers as enabled too, because that row has no state left to advance: the
 * route on its name only re-copies the draft over the live tree. `disabled` is the inverse of `maySend`, and `loading`
 * names the one row whose copy is actually running.
 */
export function enableGate(
  busyName: string | null,
  name: string,
  enabled: boolean,
): { maySend: boolean; disabled: boolean; loading: boolean } {
  const busy = !!busyName;
  const maySend = !busy && !enabled;
  return {
    maySend,
    disabled: !maySend,
    loading: busy && busyName === name,
  };
}
