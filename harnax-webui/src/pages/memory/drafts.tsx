import { useIntl, history } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { AuditOutlined, QuestionCircleOutlined, SearchOutlined } from '@ant-design/icons';
import React, { useCallback, useEffect, useState } from 'react';
import dayjs from 'dayjs';
import { pageMemoryDrafts } from '@/services/ant-design-pro/memoryDraft';

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'orange',
  APPROVED: 'green',
  REJECTED: 'red',
};

/**
 * Review queue for the memory merges conversations propose (memory design §11.4).
 *
 * Rows carry neither text on purpose: a candidate is a whole rewrite of one file, so it is read side by side
 * with the layer it replaces rather than scanned in a cell. What positions a row in this work list is whose
 * layer it changes, which conversation it came out of, and whether the merge has rewritten it since it was
 * first proposed — `updateTime` later than `createTime` means the text the owner last read is stale, which is
 * exactly what the digest check at approval refuses, so it is marked here instead of left to be discovered
 * on the detail screen.
 */
const MemoryDrafts: React.FC = () => {
  const intl = useIntl();
  const [status, setStatus] = useState<API.MemoryDraftStatus>('PENDING');
  const [agentName, setAgentName] = useState<string>('');
  const [rows, setRows] = useState<API.MemoryDraftRow[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(20);
  const [loading, setLoading] = useState<boolean>(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await pageMemoryDrafts(
        { status, agentName: agentName || undefined, pageNum, pageSize },
        { skipErrorHandler: true },
      );
      // A refusal arrives on HTTP 200 with the code in the envelope, so umi's errorThrower never fires here.
      if (response.code !== 200) {
        throw new Error(response.message || '');
      }
      setRows(response.data?.records ?? []);
      setTotal(response.data?.total ?? 0);
    } catch (error: any) {
      setRows([]);
      setTotal(0);
      // An unreadable queue must say why: a silent empty table reads as "no conversation proposed a merge".
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({
            id: 'pages.memory.draft.loadFailed',
            defaultMessage: 'Failed to load the memory review queue',
          }),
      );
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, agentName, pageNum, pageSize]);

  useEffect(() => {
    load();
  }, [load]);

  const columns: ColumnsType<API.MemoryDraftRow> = [
    {
      title: intl.formatMessage({
        id: 'pages.memory.draft.agent',
        defaultMessage: 'Agent memory',
      }),
      dataIndex: 'agentName',
      key: 'agentName',
      width: 200,
      ellipsis: true,
      render: (name: string, record) => (
        <a
          onClick={() => history.push(`/agent/memory-draft/detail/${record.id}`)}
          style={{ fontWeight: 500, cursor: 'pointer' }}
        >
          <AuditOutlined style={{ marginRight: 6, color: 'var(--vip-primary)' }} />
          {name}
        </a>
      ),
    },
    {
      title: (
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.memory.draft.sessionHint',
            defaultMessage: 'The conversation whose own memory was merged into this agent',
          })}
        >
          <span>
            {intl.formatMessage({ id: 'pages.memory.draft.session', defaultMessage: 'From session' })}{' '}
            <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
          </span>
        </Tooltip>
      ),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 200,
      ellipsis: true,
      render: (sessionId: string) => (
        <Typography.Text code copyable={{ text: sessionId }}>
          {sessionId}
        </Typography.Text>
      ),
    },
    {
      title: (
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.memory.draft.baseVersionHint',
            defaultMessage: 'The version of the long-term layer this merge read. 0 means the agent had none yet, so approving gives it its first memory.',
          })}
        >
          <span>
            {intl.formatMessage({ id: 'pages.memory.draft.baseVersion', defaultMessage: 'Merged against' })}{' '}
            <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
          </span>
        </Tooltip>
      ),
      dataIndex: 'baseVersion',
      key: 'baseVersion',
      width: 140,
      align: 'center',
      render: (version: number) =>
        version > 0 ? (
          <span>{`v${version}`}</span>
        ) : (
          <Tag color="blue">
            {intl.formatMessage({ id: 'pages.memory.draft.firstLayer', defaultMessage: 'first memory' })}
          </Tag>
        ),
    },
    {
      title: (
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.memory.draft.sourcesHint',
            defaultMessage: 'How many of that conversation memory files this merge took material out of. An approval clears them once the new layer is written.',
          })}
        >
          <span>
            {intl.formatMessage({ id: 'pages.memory.draft.sourceCount', defaultMessage: 'Source files' })}{' '}
            <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
          </span>
        </Tooltip>
      ),
      dataIndex: 'sourceCount',
      key: 'sourceCount',
      width: 130,
      align: 'center',
      render: (count: number) => count ?? 0,
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.draft.mergedChars',
        defaultMessage: 'New length',
      }),
      dataIndex: 'mergedChars',
      key: 'mergedChars',
      width: 120,
      align: 'right',
      render: (chars: number) => (chars ? chars.toLocaleString() : 0),
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.draft.proposedAt',
        defaultMessage: 'First proposed',
      }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 160,
      render: (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: (
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.memory.draft.rewrittenHint',
            defaultMessage: 'The conversation keeps merging while a candidate is open, so a later time means the text changed after the first proposal',
          })}
        >
          <span>
            {intl.formatMessage({ id: 'pages.memory.draft.lastMerge', defaultMessage: 'Last merge' })}{' '}
            <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
          </span>
        </Tooltip>
      ),
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 200,
      render: (time: string | undefined, record) => {
        if (!time) return '-';
        const rewritten = !!record.createTime && dayjs(time).isAfter(dayjs(record.createTime));
        return (
          <Space size={6}>
            <span>{dayjs(time).format('YYYY-MM-DD HH:mm')}</span>
            {rewritten && (
              <Tag color="blue">
                {intl.formatMessage({ id: 'pages.memory.draft.rewritten', defaultMessage: 'rewritten' })}
              </Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (rowStatus: string) => (
        <Tag color={STATUS_COLORS[rowStatus] || 'default'}>
          {intl.formatMessage({
            id: `pages.memory.draft.status.${rowStatus}`,
            defaultMessage: rowStatus,
          })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.draft.decidedBy',
        defaultMessage: 'Decided by',
      }),
      dataIndex: 'reviewedBy',
      key: 'reviewedBy',
      width: 150,
      render: (reviewer: string | undefined, record) =>
        reviewer ? (
          <Space size={6}>
            <span>{reviewer}</span>
            {record.reviewedAt && (
              <span style={{ color: 'var(--vip-text-tertiary)' }}>
                {dayjs(record.reviewedAt).format('MM-DD HH:mm')}
              </span>
            )}
          </Space>
        ) : (
          '-'
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.action', defaultMessage: 'Action' }),
      key: 'action',
      fixed: 'right',
      width: 100,
      render: (_text, record) => (
        <Button
          type="link"
          size="small"
          onClick={() => history.push(`/agent/memory-draft/detail/${record.id}`)}
        >
          {intl.formatMessage({ id: 'pages.memory.draft.review', defaultMessage: 'Review' })}
        </Button>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <AuditOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({
              id: 'pages.memory.draft.title',
              defaultMessage: 'Memory merges to review',
            })}
            {status === 'PENDING' && total > 0 && (
              <Tag color="orange" style={{ marginLeft: 10 }}>
                {total}
              </Tag>
            )}
          </span>
        ),
        subTitle: (
          <span style={{ fontSize: 13, color: 'var(--vip-text-tertiary)' }}>
            {intl.formatMessage({
              id: 'pages.memory.draft.subtitle',
              defaultMessage: 'Only merges proposed by your own conversations are listed here',
            })}
          </span>
        ),
        extra: [
          <Button key="memory" onClick={() => history.push('/agent/memory')}>
            {intl.formatMessage({ id: 'pages.memory.title', defaultMessage: 'My Agent Memory' })}
          </Button>,
        ],
      }}
    >
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <Select
          value={status}
          onChange={(value) => {
            setStatus(value);
            setPageNum(1);
          }}
          style={{ width: 160 }}
          options={[
            {
              value: 'PENDING',
              label: intl.formatMessage({
                id: 'pages.memory.draft.status.PENDING',
                defaultMessage: 'Pending',
              }),
            },
            {
              value: 'APPROVED',
              label: intl.formatMessage({
                id: 'pages.memory.draft.status.APPROVED',
                defaultMessage: 'Approved',
              }),
            },
            {
              value: 'REJECTED',
              label: intl.formatMessage({
                id: 'pages.memory.draft.status.REJECTED',
                defaultMessage: 'Rejected',
              }),
            },
          ]}
        />
        <Input.Search
          allowClear
          placeholder={intl.formatMessage({
            id: 'pages.memory.draft.searchPlaceholder',
            defaultMessage: 'Search by agent',
          })}
          enterButton={<SearchOutlined />}
          style={{ width: 260 }}
          onSearch={(value) => {
            setAgentName(value.trim());
            setPageNum(1);
          }}
        />
      </div>

      <Table<API.MemoryDraftRow>
        className="styled-pro-table"
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
        locale={{
          emptyText: (
            <Empty
              description={
                status === 'PENDING'
                  ? intl.formatMessage({
                      id: 'pages.memory.draft.emptyPending',
                      defaultMessage: 'No conversation memory is waiting to be merged',
                    })
                  : intl.formatMessage({
                      id: 'pages.memory.draft.emptyDecided',
                      defaultMessage: 'No decided merge',
                    })
              }
            />
          ),
        }}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (page, size) => {
            setPageNum(page);
            setPageSize(size);
          },
          showTotal: (t) =>
            `${intl.formatMessage({ id: 'pages.common.total', defaultMessage: 'Total' })} ${t} ${intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}`,
        }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
        {intl.formatMessage({
          id: 'pages.memory.draft.queueHint',
          defaultMessage:
            'Nothing in this table has reached an agent yet: a merge only rewrites its long-term layer when you approve it here, and the layer replaces the whole text the agent is told in every later conversation.',
        })}
      </Typography.Paragraph>
    </PageContainer>
  );
};

export default MemoryDrafts;
