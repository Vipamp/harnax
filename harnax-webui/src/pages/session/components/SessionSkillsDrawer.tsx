import React, { useCallback, useEffect, useState } from 'react';
import { Button, Drawer, Empty, List, Spin, Typography, message } from 'antd';
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import dayjs from 'dayjs';
import { pageSkillDrafts } from '@/services/ant-design-pro/skillDraft';
import { enableSessionSkill, listSessionSkills } from '@/services/ant-design-pro/sessionSkill';

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
 * Listing can only fail at the guard (401/403) or at the transport, so a failed read never borrows a code from the
 * refusal table: calling a draft DANGEROUS because the queue could not be read sends the operator to the review
 * queue instead of to the login screen. `unavailable` is the flag that separates "this session wrote nothing" from
 * "nothing could be read". A half that did answer still contributes its rows, so one refusal does not hide the
 * other half's truth.
 */
export function readOutcome(
  drafts: API.Result<API.SkillDraftPage>,
  enabled: API.Result<API.SessionSkillRow[]>,
): { unavailable: boolean; rows: SessionSkillEntry[] } {
  const draftRows = drafts.code === 200 ? (drafts.data?.records ?? []) : [];
  const enabledRows = enabled.code === 200 ? (enabled.data ?? []) : [];
  return {
    unavailable: drafts.code !== 200 || enabled.code !== 200,
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
    en: 'The container refused the copy',
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

/** The zone stores an ISO instant; a raw one is unreadable next to a skill name. */
function formatEnabledAt(value: string | null): string {
  if (!value) return '-';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : value;
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
  const [loading, setLoading] = useState(false);
  const [busyName, setBusyName] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!sessionId) return;
    setLoading(true);
    // A retry reads afresh: the previous round's verdict must not survive into this one, not even while in flight.
    setUnavailable(false);
    try {
      const [draftResponse, enabledResponse] = await Promise.all([
        pageSkillDrafts(
          { status: 'PENDING', sessionId, pageNum: 1, pageSize: 50 },
          { skipErrorHandler: true },
        ),
        listSessionSkills(sessionId, { skipErrorHandler: true }),
      ]);
      // A refusal arrives on HTTP 200 with the code in the envelope, so umi's errorThrower never fires here.
      const outcome = readOutcome(draftResponse, enabledResponse);
      setUnavailable(outcome.unavailable);
      setRows(outcome.rows);
      // When both halves are unreadable the empty state carries the load failure; a partial read renders rows,
      // so that copy would never be seen and has to be said out loud instead.
      if (outcome.unavailable && outcome.rows.length > 0) {
        message.error(
          intl.formatMessage({
            id: 'pages.session.skills.loadFailed',
            defaultMessage: "Could not load this session's skills",
          }),
        );
      }
    } catch {
      // An unreadable panel must not read as "this session wrote nothing": that is the empty state's own claim.
      setRows([]);
      setUnavailable(true);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, intl]);

  useEffect(() => {
    if (visible && sessionId) load();
  }, [visible, sessionId, load]);

  const handleEnable = async (name: string) => {
    if (!sessionId) return;
    setBusyName(name);
    try {
      const response = await enableSessionSkill(sessionId, name, { skipErrorHandler: true });
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
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({ id: 'pages.session.skills.enableFailed', defaultMessage: 'Could not enable it' }),
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
                ? {
                    id: 'pages.session.skills.loadFailed',
                    defaultMessage: "Could not load this session's skills",
                  }
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
            renderItem={(entry) => (
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
                  disabled={entry.enabled}
                  loading={busyName === entry.name}
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
            )}
          />
        )}
      </Spin>
    </Drawer>
  );
};

export default SessionSkillsDrawer;
