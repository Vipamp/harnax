import React, { useCallback, useEffect, useState } from 'react';
import { Button, Drawer, Empty, List, Spin, Typography, message } from 'antd';
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import dayjs from 'dayjs';
import { pageSkillDrafts } from '@/services/ant-design-pro/skillDraft';
import { enableSessionSkill, listSessionSkills } from '@/services/ant-design-pro/sessionSkill';
import { enableGate, scopedSessionId } from './sessionSkills';

const { Text } = Typography;

export type SessionSkillEntry = {
  name: string;
  description: string | null;
  enabled: boolean;
  enabledAt: string | null;
};

/**
 * Join the two halves of the panel into one list.
 *
 * The nominations come from the review queue (what the agent drafted in this conversation) and the enabled rows
 * come from the session's own zone (what the human already let through). A draft with no enabled twin is offered
 * for one-tap enabling; a name the agent has since rewritten out of the queue is still kept, because it is live in
 * this session either way.
 */
export function sessionSkillsFor(
  drafts: { name: string; description?: string | null }[],
  enabled: { name: string; enabledAt?: string | null }[],
): SessionSkillEntry[] {
  const byName = new Map(enabled.map((row) => [row.name, row]));
  const rows: SessionSkillEntry[] = drafts.map((draft) => {
    const hit = byName.get(draft.name);
    byName.delete(draft.name);
    return {
      name: draft.name,
      description: draft.description ?? null,
      enabled: !!hit,
      enabledAt: hit?.enabledAt ?? null,
    };
  });
  byName.forEach((row, name) => {
    rows.push({ name, description: null, enabled: true, enabledAt: row.enabledAt ?? null });
  });
  return rows;
}

/**
 * What the two reads leave the panel able to say, decided apart from what an enable refusal says.
 *
 * A read fails in one of two channels: on the envelope, where the guard answers HTTP 200 with a non-200 code, or at
 * the HTTP level, where an expired JWT makes the admin queue reject outright. `load()` folds the second channel into
 * the first as a non-200 envelope, so this function only ever sees envelopes and every non-200 half counts as unread.
 * A failed read therefore never borrows a code from the refusal table: calling a draft DANGEROUS because the queue
 * could not be read sends the operator to the review queue instead of to the login screen. `unavailable` is the flag
 * that separates "this session wrote nothing" from "nothing could be read", and a half that did answer contributes its
 * rows whatever the other half did — which is why a payload riding on a failure envelope is dropped here rather than
 * trusted.
 *
 * `noSandbox` is the one state the read leg is refused *by name*: 410 says this conversation has no running
 * container, which the operator fixes by restarting it, while every other non-200 leaves the panel unable to say
 * anything more than that the read did not come back. It rides beside `unavailable` rather than replacing it,
 * because the enabled half is unreadable in both cases and the queue's rows still land either way.
 */
export function readOutcome(
  drafts: API.Result<API.SkillDraftPage>,
  enabled: API.Result<API.SessionSkillRow[]>,
): { unavailable: boolean; noSandbox: boolean; rows: SessionSkillEntry[] } {
  const draftRows = drafts.code === 200 ? (drafts.data?.records ?? []) : [];
  const enabledRows = enabled.code === 200 ? (enabled.data ?? []) : [];
  return {
    unavailable: drafts.code !== 200 || enabled.code !== 200,
    noSandbox: enabled.code === 410,
    rows: sessionSkillsFor(draftRows, enabledRows),
  };
}

/** One table over the five refusal codes: the locale id and the copy that id falls back to. */
const REFUSALS: Record<string, { id: string; en: string }> = {
  '403': {
    id: 'pages.session.skills.refusal.dangerous',
    en: 'The security scan says DANGEROUS, so this draft cannot be enabled',
  },
  '409': {
    id: 'pages.session.skills.refusal.limit',
    en: 'This session already has ten skills enabled',
  },
  '410': {
    id: 'pages.session.skills.refusal.noSandbox',
    en: 'This session has no running sandbox',
  },
  '404': {
    id: 'pages.session.skills.refusal.noDraft',
    en: 'This session has no draft with that name',
  },
  '500': {
    id: 'pages.session.skills.refusal.container',
    en: 'The copy did not complete',
  },
};

const UNKNOWN_REFUSAL = {
  id: 'pages.session.skills.refusal.unknown',
  en: 'The enable request was refused for a reason this panel does not know; the draft itself is untouched',
};

/**
 * An undocumented code must not claim the draft is gone — it may only mean the sandbox is down or the login
 * expired, so it lands on the copy that names nobody.
 */
export function refusalOf(code: number): { id: string; en: string } {
  return REFUSALS[String(code)] ?? UNKNOWN_REFUSAL;
}

/**
 * The sentence a read that did not answer is entitled to, chosen from the flags alone.
 *
 * The drawer renders it in two places — the empty state's description, and the toast that goes out when the other
 * half did answer and left rows on screen — so it is decided once here rather than as a ternary at each site. The
 * sandbox copy is the refusal table's own entry, so the read leg and the enable leg cannot drift on what a 410 says.
 */
export function readCopy(noSandbox: boolean): { id: string; defaultMessage: string } {
  if (!noSandbox) {
    return {
      id: 'pages.session.skills.loadFailed',
      defaultMessage: "Could not load this session's skills",
    };
  }
  const refusal = refusalOf(410);
  return { id: refusal.id, defaultMessage: refusal.en };
}

/** The zone stores an ISO instant; a raw one is unreadable next to a skill name. */
function formatEnabledAt(value: string | null): string {
  if (!value) return '-';
  const parsed = dayjs(value);
  // The value is the container's own string, so anything dayjs cannot read is not a time this panel may print as one.
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : '-';
}

interface SessionSkillsDrawerProps {
  visible: boolean;
  sessionId?: string;
  onClose: () => void;
}

/**
 * Skills this conversation wrote, and the one confirmation that makes one of them usable in it.
 *
 * Enabling is session-scoped: the skill is copied into this session's own enabled zone and the agent sees it from
 * the next turn. It does not skip the review queue — the draft stays pending there until a reviewer decides.
 */
const SessionSkillsDrawer: React.FC<SessionSkillsDrawerProps> = ({ visible, sessionId, onClose }) => {
  const intl = useIntl();
  const [rows, setRows] = useState<SessionSkillEntry[]>([]);
  const [unavailable, setUnavailable] = useState(false);
  const [noSandbox, setNoSandbox] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busyName, setBusyName] = useState<string | null>(null);

  const load = useCallback(async () => {
    // Blank is not unscoped: a queue read with no `sessionId` answers the reviewer's whole tenant, and its rows
    // would land under this conversation's title. Reporting "could not read" is the only thing this panel may say
    // for an id that names nothing — not "this session has not written a skill yet".
    const scope = scopedSessionId(sessionId);
    if (!scope) {
      setRows([]);
      setUnavailable(true);
      // Nothing went out, so nothing was told: a leg that never reached a server cannot name a stopped sandbox.
      setNoSandbox(false);
      return;
    }
    setLoading(true);
    // A retry reads afresh: the previous round's verdict must not survive into this one, not even while in flight.
    setUnavailable(false);
    setNoSandbox(false);
    try {
      // Both reads go out together, but neither may take the other's payload down with it. The admin queue answers an
      // expired JWT with an HTTP status rather than an envelope, so `Promise.all` would throw away the enabled rows the
      // router half had already returned; a rejection is a read that did not answer, and says nothing about the other.
      const [draftSettled, enabledSettled] = await Promise.allSettled([
        pageSkillDrafts(
          { status: 'PENDING', sessionId: scope, pageNum: 1, pageSize: 50 },
          { skipErrorHandler: true },
        ),
        listSessionSkills(scope, { skipErrorHandler: true }),
      ]);
      // A refusal on the envelope already arrives on HTTP 200 with the code in it; a rejection gets a code that names
      // nothing, which is all the read path is allowed to say about either.
      const draftResponse: API.Result<API.SkillDraftPage> =
        draftSettled.status === 'fulfilled' ? draftSettled.value : { code: 0 };
      const enabledResponse: API.Result<API.SessionSkillRow[]> =
        enabledSettled.status === 'fulfilled' ? enabledSettled.value : { code: 0 };
      const outcome = readOutcome(draftResponse, enabledResponse);
      setUnavailable(outcome.unavailable);
      setNoSandbox(outcome.noSandbox);
      setRows(outcome.rows);
      // When both halves are unreadable the empty state carries the load failure; a partial read renders rows,
      // so that copy would never be seen and has to be said out loud instead.
      if (outcome.unavailable && outcome.rows.length > 0) {
        message.error(intl.formatMessage(readCopy(outcome.noSandbox)));
      }
    } catch {
      // Both reads are settled by here, so reaching this line means the panel itself broke. It still must not read as
      // "this session wrote nothing": that is the empty state's own claim.
      setRows([]);
      setUnavailable(true);
      setNoSandbox(false);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, intl]);

  useEffect(() => {
    if (visible && sessionId) load();
  }, [visible, sessionId, load]);

  const handleEnable = async (name: string) => {
    const scope = scopedSessionId(sessionId);
    if (!scope) return;
    // One copy at a time: the ten-skill ceiling is counted from the directory the previous copy wrote, so two
    // presses in flight both read the old count and can push this session past its ten. The buttons grey out on
    // the same judgement (`enableGate`), so a row that cannot start a copy does not accept the finger either.
    const pressed = rows.find((row) => row.name === name);
    if (!enableGate(busyName, name, !!pressed?.enabled).maySend) return;
    setBusyName(name);
    try {
      const response = await enableSessionSkill(scope, name, { skipErrorHandler: true });
      if (response.code !== 200) {
        message.error(
          intl.formatMessage({ id: refusalOf(response.code).id, defaultMessage: refusalOf(response.code).en }),
        );
      } else if (response.data && response.data.ok === false) {
        // The envelope said yes while the payload said no: never claim the skill is usable.
        message.error(
          intl.formatMessage({ id: 'pages.session.skills.enableFailed', defaultMessage: 'Could not enable it' }),
        );
      } else {
        message.success(
          intl.formatMessage({
            id: 'pages.session.skills.enabled',
            defaultMessage: 'Enabled in this session',
          }),
        );
      }
    } catch (error: any) {
      // A server-supplied message is not translatable, so this toast comes out of the locale files only: an envelope
      // code carried by a BizError gets named by the same table the 200 path uses, and everything else — a transport
      // failure, an expired login, a refusal with no code behind it — is just an enable that did not succeed.
      const code = error?.info?.errorCode;
      const refusal = typeof code === 'number' ? refusalOf(code) : null;
      message.error(
        intl.formatMessage(
          refusal
            ? { id: refusal.id, defaultMessage: refusal.en }
            : { id: 'pages.session.skills.enableFailed', defaultMessage: 'Could not enable it' },
        ),
      );
    } finally {
      setBusyName(null);
      // Both halves are the panel's whole truth, so a refusal is re-read the same way a success is.
      load();
    }
  };

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <ThunderboltOutlined />
          <span>
            {intl.formatMessage({
              id: 'pages.session.skills.title',
              defaultMessage: 'Skills written in this session',
            })}
          </span>
        </div>
      }
      open={visible}
      onClose={onClose}
      width={520}
      extra={
        <Button icon={<ReloadOutlined />} size="small" onClick={load} loading={loading}>
          {intl.formatMessage({ id: 'pages.session.skills.refresh', defaultMessage: 'Refresh' })}
        </Button>
      }
    >
      <Spin spinning={loading}>
        {rows.length === 0 && !loading ? (
          <Empty
            description={intl.formatMessage(
              unavailable
                ? readCopy(noSandbox)
                : {
                    id: 'pages.session.skills.empty',
                    defaultMessage: 'This session has not written a skill yet',
                  },
            )}
            style={{ marginTop: 60 }}
          />
        ) : (
          <List
            dataSource={rows}
            split={false}
            renderItem={(entry) => {
              const gate = enableGate(busyName, entry.name, entry.enabled);
              return (
                <List.Item key={entry.name} style={{ padding: '10px 0' }}>
                  <List.Item.Meta
                    title={<Text style={{ fontWeight: 500 }}>{entry.name}</Text>}
                    description={
                      <div>
                        {entry.description ? (
                          <div>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {entry.description}
                            </Text>
                          </div>
                        ) : null}
                        {entry.enabled ? (
                          <div>
                            <Text type="secondary" style={{ fontSize: 11 }}>
                              {intl.formatMessage({
                                id: 'pages.session.skills.enabledAt',
                                defaultMessage: 'Enabled at',
                              })}{' '}
                              · {formatEnabledAt(entry.enabledAt)}
                            </Text>
                          </div>
                        ) : null}
                      </div>
                    }
                  />
                  <Button
                    type={entry.enabled ? 'default' : 'primary'}
                    size="small"
                    disabled={gate.disabled}
                    loading={gate.loading}
                    onClick={() => handleEnable(entry.name)}
                  >
                    {entry.enabled
                      ? intl.formatMessage({
                          id: 'pages.session.skills.enabled',
                          defaultMessage: 'Enabled in this session',
                        })
                      : intl.formatMessage({
                          id: 'pages.session.skills.enable',
                          defaultMessage: 'Enable in this session',
                        })}
                  </Button>
                </List.Item>
              );
            }}
          />
        )}
      </Spin>
    </Drawer>
  );
};

export default SessionSkillsDrawer;
