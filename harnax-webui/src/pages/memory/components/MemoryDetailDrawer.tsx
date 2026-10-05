import React, { useCallback, useEffect, useState } from 'react';
import { BookOutlined } from '@ant-design/icons';
import {
  Collapse,
  Descriptions,
  Drawer,
  Empty,
  Spin,
  Typography,
  message,
} from 'antd';
import { useIntl } from '@umijs/max';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { getMemoryDetail } from '@/services/ant-design-pro/memory';

const { Text, Title } = Typography;

interface MemoryDetailDrawerProps {
  /** The agent's name, which is what admin keys memory by. Undefined until a row is clicked. */
  agentId?: string;
  onClose: () => void;
}

const labelStyle = { width: 168, background: 'var(--vip-bg-layout)' };
const markdownStyle: React.CSSProperties = {
  border: '1px solid var(--vip-border)',
  borderRadius: 8,
  padding: 16,
  background: 'var(--vip-bg-container)',
  maxHeight: 320,
  overflow: 'auto',
};

/**
 * What one agent actually remembers: the curated MEMORY.md plus every daily note.
 *
 * Read-only by design — the compliance promise is that the user can see it and wipe it, not rewrite
 * it (a rewritten memory would be a memory the agent never formed). The list row carries the curated
 * text but none of the daily bodies, so this drawer is the only reader of `GET /api/admin/memory/{agentId}`.
 */
const MemoryDetailDrawer: React.FC<MemoryDetailDrawerProps> = ({
  agentId,
  onClose,
}) => {
  const intl = useIntl();
  const [detail, setDetail] = useState<API.MemoryDetail>();
  const [loading, setLoading] = useState(false);

  const loadDetail = useCallback(async () => {
    if (!agentId) return;
    setLoading(true);
    try {
      const res = await getMemoryDetail(agentId);
      if (res.code === 200) {
        setDetail(res.data as API.MemoryDetail);
      } else if (res.code === 404) {
        // Admin answers 404 inside the envelope when this agent has no memory object at all. That
        // is an empty state, not a failure: the Empty below is the right UI and no toast is due.
        setDetail(undefined);
      } else {
        setDetail(undefined);
        message.error(
          res.message ||
            intl.formatMessage({
              id: 'pages.message.loadFailed',
              defaultMessage: 'Failed to load data',
            }),
        );
      }
    } catch {
      setDetail(undefined);
      message.error(
        intl.formatMessage({
          id: 'pages.message.loadFailed',
          defaultMessage: 'Failed to load data',
        }),
      );
    } finally {
      setLoading(false);
    }
  }, [agentId, intl]);

  useEffect(() => {
    if (agentId) {
      loadDetail();
    } else {
      setDetail(undefined);
    }
  }, [agentId, loadDetail]);

  const entries = detail?.entries || [];
  const curated = (detail?.content || '').trim();

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <BookOutlined style={{ color: 'var(--vip-primary)' }} />
          <span style={{ fontFamily: 'monospace' }}>{agentId}</span>
        </div>
      }
      open={!!agentId}
      onClose={onClose}
      width={640}
    >
      <Spin spinning={loading}>
        {!detail && !loading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={intl.formatMessage({
              id: 'pages.memory.detail.empty',
              defaultMessage: 'No memory detail available',
            })}
            style={{ marginTop: 60 }}
          />
        ) : (
          <>
            <Descriptions
              bordered
              size="small"
              column={1}
              styles={{ label: labelStyle }}
            >
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.memory.updatedAt',
                  defaultMessage: 'Last Updated',
                })}
              >
                {detail?.lastModified || '-'}
              </Descriptions.Item>
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.memory.dailyCount',
                  defaultMessage: 'Daily Notes',
                })}
              >
                {entries.length}
              </Descriptions.Item>
            </Descriptions>

            <Title level={5} style={{ marginTop: 24, marginBottom: 8 }}>
              {intl.formatMessage({
                id: 'pages.memory.curatedSection',
                defaultMessage: 'Curated memory (MEMORY.md)',
              })}
            </Title>
            {curated ? (
              <div className="markdown-body" style={markdownStyle}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {curated}
                </ReactMarkdown>
              </div>
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={intl.formatMessage({
                  id: 'pages.memory.emptyCurated',
                  defaultMessage:
                    'This agent has not curated any long-term memory yet',
                })}
                style={{ margin: '16px 0' }}
              />
            )}

            <Title level={5} style={{ marginTop: 24, marginBottom: 8 }}>
              {intl.formatMessage({
                id: 'pages.memory.dailySection',
                defaultMessage: 'Daily notes',
              })}
            </Title>
            {entries.length ? (
              <Collapse
                size="small"
                items={entries.map((entry) => ({
                  key: entry.date,
                  label: (
                    <Text strong style={{ fontFamily: 'monospace' }}>
                      {entry.date}
                    </Text>
                  ),
                  children: (
                    <div
                      className="markdown-body"
                      style={{ maxHeight: 320, overflow: 'auto' }}
                    >
                      {entry.content?.trim() ? (
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {entry.content}
                        </ReactMarkdown>
                      ) : (
                        <Text type="secondary">
                          {intl.formatMessage({
                            id: 'pages.memory.emptyDailyEntry',
                            defaultMessage: 'This day has an empty note',
                          })}
                        </Text>
                      )}
                    </div>
                  ),
                }))}
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={intl.formatMessage({
                  id: 'pages.memory.emptyDaily',
                  defaultMessage: 'No daily notes',
                })}
              />
            )}
          </>
        )}
      </Spin>
    </Drawer>
  );
};

export default MemoryDetailDrawer;
