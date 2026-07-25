import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { Modal, Checkbox, Tag, Typography, Empty, Spin, message } from 'antd';
import { SyncOutlined } from '@ant-design/icons';
import { getAgentRelatedSessions, refreshAgentSessions } from '@/services/ant-design-pro/agent';

const { Text } = Typography;

interface RelatedSession {
  sessionId: string;
  sourceType: 'channel' | 'session';
  sourceName: string;
}

interface AgentRefreshModalProps {
  visible: boolean;
  agentId?: number;
  agentName?: string;
  onClose: () => void;
}

/**
 * After saving an agent, list its related sessions (channels + web sessions)
 * and let the user pick which ones to refresh. Checked sessions receive a
 * REFRESH command so their next message uses the new configuration;
 * unchecked ones keep the old cached agent until it naturally expires.
 */
const AgentRefreshModal: React.FC<AgentRefreshModalProps> = ({ visible, agentId, agentName, onClose }) => {
  const intl = useIntl();
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [sessions, setSessions] = useState<RelatedSession[]>([]);
  const [checked, setChecked] = useState<string[]>([]);
  const [messageApi, contextHolder] = message.useMessage();

  useEffect(() => {
    if (visible && agentId) {
      setLoading(true);
      setSessions([]);
      setChecked([]);
      getAgentRelatedSessions(agentId)
        .then((res) => {
          if (res.code === 200 && res.data) {
            setSessions(res.data);
            // 默认全选，用户可取消不需要刷新的
            setChecked(res.data.map((s: RelatedSession) => s.sessionId));
          }
        })
        .catch(() => {})
        .finally(() => setLoading(false));
    }
  }, [visible, agentId]);

  const handleRefresh = async () => {
    if (checked.length === 0) {
      onClose();
      return;
    }
    setRefreshing(true);
    try {
      const res = await refreshAgentSessions(checked);
      if (res.code === 200) {
        const results = res.data || [];
        const failed = results.filter((r: any) => !r.success);
        if (failed.length === 0) {
          messageApi.success(
            intl.formatMessage(
              { id: 'pages.agent.refresh.success', defaultMessage: 'Refreshed {count} session(s)' },
              { count: checked.length },
            ),
          );
        } else {
          messageApi.warning(
            intl.formatMessage(
              { id: 'pages.agent.refresh.partial', defaultMessage: '{ok} refreshed, {fail} failed' },
              { ok: results.length - failed.length, fail: failed.length },
            ),
          );
        }
        setTimeout(() => onClose(), 500);
      } else {
        messageApi.error(res.message || intl.formatMessage({ id: 'pages.agent.refresh.failed', defaultMessage: 'Refresh failed' }));
      }
    } catch {
      messageApi.error(intl.formatMessage({ id: 'pages.agent.refresh.failed', defaultMessage: 'Refresh failed' }));
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <Modal
      open={visible}
      onCancel={onClose}
      onOk={handleRefresh}
      confirmLoading={refreshing}
      okText={
        checked.length > 0
          ? intl.formatMessage(
              { id: 'pages.agent.refresh.ok', defaultMessage: 'Refresh selected ({count})' },
              { count: checked.length },
            )
          : intl.formatMessage({ id: 'pages.agent.refresh.skip', defaultMessage: 'Skip' })
      }
      cancelText={intl.formatMessage({ id: 'pages.agent.refresh.later', defaultMessage: 'Later' })}
      width={520}
      title={
        <span>
          <SyncOutlined style={{ color: 'var(--vip-primary)', marginRight: 8 }} />
          {intl.formatMessage({ id: 'pages.agent.refresh.title', defaultMessage: 'Refresh related sessions' })}
        </span>
      }
    >
      {contextHolder}
      <Text type="secondary">
        {intl.formatMessage(
          {
            id: 'pages.agent.refresh.hint',
            defaultMessage:
              'Agent "{name}" was saved. Select the sessions to apply the new configuration immediately; unselected sessions pick it up within ~30 minutes.',
          },
          { name: agentName || '' },
        )}
      </Text>
      <div style={{ marginTop: 16, maxHeight: 320, overflowY: 'auto' }}>
        {loading ? (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <Spin />
          </div>
        ) : sessions.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={intl.formatMessage({ id: 'pages.agent.refresh.empty', defaultMessage: 'No related sessions' })}
          />
        ) : (
          <Checkbox.Group
            style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
            value={checked}
            onChange={(vals) => setChecked(vals as string[])}
          >
            {sessions.map((s) => (
              <Checkbox key={s.sessionId} value={s.sessionId}>
                <Tag color={s.sourceType === 'channel' ? 'blue' : 'purple'}>
                  {s.sourceType === 'channel'
                    ? intl.formatMessage({ id: 'pages.agent.refresh.typeChannel', defaultMessage: 'Channel' })
                    : intl.formatMessage({ id: 'pages.agent.refresh.typeSession', defaultMessage: 'Session' })}
                </Tag>
                {s.sourceName}
                <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                  {s.sessionId.length > 24 ? s.sessionId.slice(0, 24) + '…' : s.sessionId}
                </Text>
              </Checkbox>
            ))}
          </Checkbox.Group>
        )}
      </div>
    </Modal>
  );
};

export default AgentRefreshModal;
