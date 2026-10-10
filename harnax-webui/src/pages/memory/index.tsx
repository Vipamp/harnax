import { PageContainer } from '@ant-design/pro-components';
import {
  AuditOutlined,
  BookOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Empty,
  message,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useState } from 'react';
import { history, useIntl } from '@umijs/max';
import DeleteButton from '@/components/DeleteButton';
import { deleteMemory, getMemoryList } from '@/services/ant-design-pro/memory';
import MemoryDetailDrawer from './components/MemoryDetailDrawer';

const { Text } = Typography;

/**
 * Self-service compliance page: which of my agents remember things, what they remember, and a way to
 * take it away.
 *
 * The list is scoped to the logged-in user by admin (`GET /api/admin/memory`), so there is no owner
 * column and no permission gate here — every account has to be able to reach its own memory. Nothing
 * is editable: the only write this page offers is the deletion, and it goes through the same
 * `DeleteButton` confirmation the rest of the destructive admin actions use.
 *
 * Only the long-term layer is ever shown as text, but every agent also keeps a memory layer per conversation,
 * so the page reports that layer twice without displaying it: how many conversations hold memory that has
 * not been approved into the long-term layer yet, and why an agent's long-term notes stop short. Approving
 * one of those merges is the sibling page's job, so the header links there rather than duplicating it.
 */
const MyAgentMemory: React.FC = () => {
  const intl = useIntl();

  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.MemoryAgentItem[]>([]);
  const [loadFailed, setLoadFailed] = useState<boolean>(false);
  const [detailTarget, setDetailTarget] = useState<
    API.MemoryAgentItem | undefined
  >();
  const [deleting, setDeleting] = useState<string | undefined>();
  const [messageApi, contextHolder] = message.useMessage();

  /**
   * A failed read never renders as an empty one: telling a user "you have no agent memory" when the
   * request simply did not answer is the wrong compliance answer.
   */
  const loadData = async () => {
    setLoading(true);
    try {
      const res = await getMemoryList();
      if (res.code === 200) {
        setData(res.data || []);
        setLoadFailed(false);
      } else {
        setData([]);
        setLoadFailed(true);
        messageApi.error(
          res.message ||
            intl.formatMessage({
              id: 'pages.message.loadFailed',
              defaultMessage: 'Failed to load data',
            }),
        );
      }
    } catch {
      setData([]);
      setLoadFailed(true);
      messageApi.error(
        intl.formatMessage({
          id: 'pages.message.loadFailed',
          defaultMessage: 'Failed to load data',
        }),
      );
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleDelete = async (agentId: string) => {
    setDeleting(agentId);
    try {
      const res = await deleteMemory(agentId);
      // `deletedObjects` counts what actually left the bucket, and admin drops null keys from the
      // JSON — so an absent count means nothing was reported removed, exactly like 0.
      const deletedObjects = res.data?.deletedObjects ?? 0;
      if (res.code === 200) {
        if (deletedObjects > 0) {
          messageApi.success(
            intl.formatMessage(
              {
                id: 'pages.memory.deletedObjects',
                defaultMessage:
                  'Deleted successfully, {count} object(s) removed',
              },
              { count: deletedObjects },
            ),
          );
          // The drawer reads the memory that just stopped existing.
          if (detailTarget?.agentId === agentId) {
            setDetailTarget(undefined);
          }
        } else {
          // Nothing left the bucket: the memory is still there or was never ours to erase, so this
          // must not read as a completed erasure — a user who believes it would stop looking.
          messageApi.warning(
            intl.formatMessage({
              id: 'pages.memory.deleteNothingFound',
              defaultMessage:
                'No memory of this agent was found to delete, nothing was removed',
            }),
          );
        }
        await loadData();
      } else {
        messageApi.error(
          res.message ||
            intl.formatMessage({
              id: 'pages.message.deleteFailed',
              defaultMessage: 'Delete failed, please try again',
            }),
        );
      }
    } catch (error: any) {
      messageApi.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({
            id: 'pages.message.deleteFailed',
            defaultMessage: 'Delete failed, please try again',
          }),
      );
    } finally {
      setDeleting(undefined);
    }
  };

  /**
   * The one thing the daily column cannot say by itself.
   *
   * An agent that also remembers per conversation writes its days there first, and they only reach this
   * listing once its owner approves the merge, so its notes stop on the day the second layer started. Left as
   * a bare count that reads as the agent forgetting, which is the one wrong answer this page may give about
   * somebody's own memory. Every agent listed here has that second layer, so the note has nothing left to check.
   */
  const sessionLayerNote = (record?: API.MemoryAgentItem): string | undefined => {
    const last = record.dates?.[record.dates.length - 1];
    return last
      ? intl.formatMessage(
          {
            id: 'pages.memory.sessionLayerStopped',
            defaultMessage:
              'This agent also remembers per conversation, so its long-term notes stop at {date} until you approve the merge',
          },
          { date: last },
        )
      : intl.formatMessage({
          id: 'pages.memory.sessionLayerNoNotes',
          defaultMessage:
            'This agent also remembers per conversation, so nothing has reached its long-term notes yet — a merge only lands once you approve it',
        });
  };

  const columns: ColumnsType<API.MemoryAgentItem> = [
    {
      title: intl.formatMessage({
        id: 'pages.memory.agentName',
        defaultMessage: 'Agent',
      }),
      dataIndex: 'agentId',
      key: 'agentId',
      width: 220,
      ellipsis: true,
      render: (text: string, record: API.MemoryAgentItem) => (
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.memory.detailOpen',
            defaultMessage: 'Click to read what this agent remembers',
          })}
        >
          <Text
            strong
            style={{ fontFamily: 'monospace', cursor: 'pointer' }}
            onClick={() => setDetailTarget(record)}
          >
            <BookOutlined
              style={{ marginRight: 6, color: 'var(--vip-primary)' }}
            />
            {text}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.curated',
        defaultMessage: 'Curated Memory',
      }),
      dataIndex: 'content',
      key: 'content',
      ellipsis: true,
      render: (text?: string) =>
        text?.trim() ? (
          <Text>{text.trim()}</Text>
        ) : (
          <Text type="secondary">
            {intl.formatMessage({
              id: 'pages.memory.noCurated',
              defaultMessage: 'Nothing curated yet',
            })}
          </Text>
        ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.dailyCount',
        defaultMessage: 'Daily Notes',
      }),
      dataIndex: 'dates',
      key: 'dates',
      width: 120,
      align: 'center',
      render: (dates?: string[], record?: API.MemoryAgentItem) => {
        const tip = [dates?.join(', '), sessionLayerNote(record)].filter(Boolean).join('\n');
        const cell = dates?.length ? (
          <Tag color="blue">{dates.length}</Tag>
        ) : (
          <Text type="secondary">0</Text>
        );
        return tip ? <Tooltip title={tip}>{cell}</Tooltip> : cell;
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.pendingCount',
        defaultMessage: 'Unmerged',
      }),
      dataIndex: 'pendingSessionLayers',
      key: 'pendingSessionLayers',
      width: 110,
      align: 'center',
      render: (count?: number) =>
        count ? (
          <Tooltip
            title={intl.formatMessage(
              {
                id: 'pages.memory.pendingTooltip',
                defaultMessage:
                  'The memory of {count} conversation(s) is waiting to be approved into the long-term layer',
              },
              { count },
            )}
          >
            <Tag color="orange">{count}</Tag>
          </Tooltip>
        ) : (
          <Text type="secondary">0</Text>
        ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.updatedAt',
        defaultMessage: 'Last Updated',
      }),
      dataIndex: 'lastModified',
      key: 'lastModified',
      width: 180,
      render: (text?: string) => text || <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({
        id: 'pages.common.action',
        defaultMessage: 'Action',
      }),
      key: 'action',
      width: 100,
      align: 'center',
      render: (_: unknown, record: API.MemoryAgentItem) => (
        <DeleteButton
          onConfirm={() => handleDelete(record.agentId)}
          disabled={deleting === record.agentId}
          tooltip={intl.formatMessage({
            id: 'pages.memory.deleteTooltip',
            defaultMessage: 'Delete this memory',
          })}
          confirmTitle={intl.formatMessage(
            {
              id: 'pages.memory.deleteConfirm',
              defaultMessage:
                'Delete the memory of {name}? Its curated memory, all daily notes and any conversation memory that has not been merged yet go with it, and nothing can be restored.',
            },
            { name: record.agentId },
          )}
        />
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span
            style={{
              fontSize: '20px',
              fontWeight: 600,
              color: 'var(--vip-text-primary)',
            }}
          >
            <BookOutlined
              style={{ marginRight: 10, color: 'var(--vip-primary)' }}
            />
            {intl.formatMessage({
              id: 'pages.memory.title',
              defaultMessage: 'Agent Memory',
            })}
          </span>
        ),
        subTitle: (
          <span style={{ fontSize: 13, color: 'var(--vip-text-tertiary)' }}>
            {intl.formatMessage({
              id: 'pages.memory.subtitle',
              defaultMessage:
                'Only the agents owned by the signed-in account are listed here',
            })}
          </span>
        ),
        extra: [
          <Button
            key="drafts"
            icon={<AuditOutlined />}
            onClick={() => history.push('/optimization/memory/drafts')}
          >
            {intl.formatMessage({
              id: 'pages.memory.draft.title',
              defaultMessage: 'Memory merges to review',
            })}
          </Button>,
          <Button
            key="refresh"
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={loadData}
          >
            {intl.formatMessage({
              id: 'pages.common.refresh',
              defaultMessage: 'Refresh',
            })}
          </Button>,
        ],
      }}
    >
      {contextHolder}

      <Alert
        type="warning"
        showIcon
        icon={<WarningOutlined />}
        message={intl.formatMessage({
          id: 'pages.memory.deleteWarning',
          defaultMessage:
            'Deleting is final: the curated memory, every daily note and any conversation memory that has not been merged yet are removed together and cannot be restored.',
        })}
        style={{ marginBottom: 16 }}
      />

      <Table<API.MemoryAgentItem>
        columns={columns}
        dataSource={data}
        rowKey="agentId"
        loading={loading}
        scroll={{ x: 'max-content' }}
        pagination={{
          pageSize: 10,
          showSizeChanger: true,
          hideOnSinglePage: true,
        }}
        locale={{
          emptyText: loadFailed ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({
                id: 'pages.memory.loadFailed',
                defaultMessage:
                  'Failed to load your agent memory, please retry',
              })}
            >
              <Button onClick={loadData}>
                {intl.formatMessage({
                  id: 'pages.common.retry',
                  defaultMessage: 'Retry',
                })}
              </Button>
            </Empty>
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({
                id: 'pages.memory.noData',
                defaultMessage: 'None of your agents has long-term memory',
              })}
            />
          ),
        }}
      />

      <MemoryDetailDrawer
        agentId={detailTarget?.agentId}
        onClose={() => setDetailTarget(undefined)}
      />
    </PageContainer>
  );
};

export default MyAgentMemory;
