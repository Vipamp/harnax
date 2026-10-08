import React, { useEffect, useState } from 'react';
import { Alert, Descriptions, Drawer, Spin, Tag } from 'antd';
import { useIntl } from '@umijs/max';
import dayjs from 'dayjs';
import { formatLastSeen } from './lastSeen';
import { fetchProfile, profileTargetOf } from './subjectProfile';
import type { Profile } from './subjectProfile';

interface SubjectDrawerProps {
  row: API.CallMetricsRow | null;
  groupBy: string;
  onClose: () => void;
}

const labelStyle = { width: 168, background: 'var(--vip-bg-layout)' };

type Item = { label: string; node: React.ReactNode };

/**
 * What sits behind the subject the reader clicked: the agent, MCP server, CLI package, session or registry
 * tool row, read from the table that owns it (see `profileTargetOf`).
 *
 * A drawer rather than a page, so the metrics row stays on screen while its subject is being read. The row's
 * own numbers head the drawer because the subject can have no profile at all - shell and framework calls, or a
 * tool deregistered since, still have a call count worth reading. The registry rows are compact here on
 * purpose: each subject has its own management page for the full record.
 */
const SubjectDrawer: React.FC<SubjectDrawerProps> = ({ row, groupBy, onClose }) => {
  const intl = useIntl();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!row) {
      setProfile(null);
      setFailed(false);
      return;
    }
    const target = profileTargetOf(row, groupBy);
    // An absent profile is decided without a read: a shell or framework call has no registry table at all.
    if (target.source === 'none') {
      setProfile({ kind: 'none' });
      setFailed(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setProfile(null);
    setFailed(false);
    fetchProfile(target)
      .then((res) => {
        if (!cancelled) setProfile(res);
      })
      .catch(() => {
        // A read that failed must not answer the same drawer as a subject nobody registered.
        if (!cancelled) setFailed(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [row, groupBy]);

  const t = (id: string, defaultMessage: string) => intl.formatMessage({ id, defaultMessage });
  const dash = (value?: string | number) => (value === undefined || value === null || value === '' ? '-' : value);
  // The registry DTOs carry java.time instants, which Jackson serialises in ISO form rather than the
  // `yyyy-MM-dd HH:mm:ss` the metrics service formats its own columns into.
  const stamp = (value?: string) => {
    if (!value) return '-';
    const parsed = dayjs(value);
    return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
  };
  const statusTag = (status?: number) =>
    status === undefined || status === null ? (
      '-'
    ) : (
      <Tag color={status === 1 ? 'green' : 'default'}>
        {status === 1 ? t('pages.common.enabled', 'Enabled') : t('pages.common.disabled', 'Disabled')}
      </Tag>
    );
  const yesNo = (flag?: number) => (flag === 1 ? t('pages.common.yes', 'Yes') : t('pages.common.no', 'No'));

  const itemsOf = (p: Profile): Item[] => {
    if (p.kind === 'agent')
      return [
        { label: t('pages.agent.name', 'Agent name'), node: dash(p.data.name) },
        { label: t('pages.common.status', 'Status'), node: statusTag(p.data.status) },
        { label: t('pages.agent.model', 'Model'), node: dash(p.data.modelName) },
        { label: t('pages.agent.owner', 'Owner'), node: dash(p.data.owner) },
        { label: t('pages.agent.isPublic', 'Public'), node: yesNo(p.data.isPublic) },
        { label: t('pages.common.description', 'Description'), node: dash(p.data.description) },
        { label: t('pages.common.createTime', 'Create Time'), node: stamp(p.data.createTime) },
      ];
    if (p.kind === 'mcp') {
      const items: Item[] = [
        { label: t('pages.mcp.name', 'MCP name'), node: dash(p.data.name) },
        { label: t('pages.mcp.type', 'Type'), node: dash(p.data.type) },
        { label: t('pages.common.status', 'Status'), node: statusTag(p.data.status) },
      ];
      // stdio answers with a command, sse and streamable http with a URL; showing the empty one is noise.
      if (p.data.command) items.push({ label: t('pages.mcp.command', 'Command'), node: p.data.command });
      if (p.data.url) items.push({ label: t('pages.mcp.url', 'URL'), node: p.data.url });
      items.push({ label: t('pages.common.description', 'Description'), node: dash(p.data.description) });
      items.push({ label: t('pages.common.createTime', 'Create Time'), node: stamp(p.data.createTime) });
      return items;
    }
    if (p.kind === 'cli')
      return [
        { label: t('pages.common.name', 'Name'), node: dash(p.data.name) },
        { label: t('pages.cli.version', 'Version'), node: dash(p.data.version) },
        { label: t('pages.common.status', 'Status'), node: statusTag(p.data.status) },
        { label: t('pages.common.description', 'Description'), node: dash(p.data.description) },
        { label: t('pages.common.createTime', 'Create Time'), node: stamp(p.data.createTime) },
      ];
    if (p.kind === 'session')
      return [
        { label: t('pages.session.sessionName', 'Session name'), node: dash(p.data.title) },
        { label: t('pages.session.sessionId', 'Session ID'), node: dash(p.data.sessionId) },
        { label: t('pages.session.agentName', 'Agent name'), node: dash(p.data.name) },
        { label: t('pages.session.model', 'Model'), node: dash(p.data.modelName) },
        { label: t('pages.common.status', 'Status'), node: statusTag(p.data.status) },
        { label: t('pages.common.createTime', 'Create Time'), node: stamp(p.data.createTime) },
      ];
    if (p.kind === 'tool')
      return [
        { label: t('pages.tool.name', 'Tool name'), node: dash(p.data.name) },
        { label: t('pages.common.status', 'Status'), node: statusTag(p.data.status) },
        { label: t('pages.tool.needConfirm', 'Need Confirm'), node: yesNo(p.data.needConfirm) },
        { label: t('pages.common.description', 'Description'), node: dash(p.data.description) },
        { label: t('pages.common.createTime', 'Create Time'), node: stamp(p.data.createTime) },
      ];
    return [];
  };

  const emptyNote = failed
    ? t('pages.callMetrics.loadFailed', 'Failed to load call metrics')
    : t('pages.callMetrics.profile.empty', 'This subject has no registered profile');

  // The numbers the reader clicked: same range, same row. The page closes this drawer on any range, origin or
  // dimension change, which is what keeps that "same" true rather than this component's own reading.
  const metricItems: Item[] = row
    ? [
        { label: t('pages.callMetrics.col.calls', 'Calls'), node: row.calls },
        {
          label: t('pages.callMetrics.col.successRate', 'Success rate'),
          node: `${(row.successRate * 100).toFixed(1)}%`,
        },
        { label: t('pages.callMetrics.col.p95', 'P95'), node: `${row.p95Operator}${row.p95Ms} ms` },
        {
          label: t('pages.callMetrics.col.lastSeen', 'Last call'),
          node: formatLastSeen(row.lastSeenAt, groupBy),
        },
      ]
    : [];

  const renderItems = (items: Item[]) => (
    <Descriptions bordered size="small" column={1} styles={{ label: labelStyle }}>
      {items.map((item) => (
        <Descriptions.Item key={item.label} label={item.label}>
          {item.node}
        </Descriptions.Item>
      ))}
    </Descriptions>
  );

  let body: React.ReactNode;
  if (failed) {
    body = <Alert type="error" showIcon message={emptyNote} />;
  } else if (profile === null) {
    body = null;
  } else if (profile.kind === 'none') {
    body = <Alert type="info" showIcon message={emptyNote} />;
  } else {
    body = renderItems(itemsOf(profile));
  }

  return (
    <Drawer
      width={560}
      open={!!row}
      onClose={onClose}
      title={`${t('pages.callMetrics.profile.title', 'Registered info')}${
        row ? ` — ${row.subjectName || row.subjectKey}` : ''
      }`}
    >
      <Spin spinning={loading}>
        {row ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {renderItems(metricItems)}
            {body}
          </div>
        ) : null}
      </Spin>
    </Drawer>
  );
};

export default SubjectDrawer;
