import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Empty,
  message,
  Modal,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useRef, useState } from 'react';
import { useIntl } from '@umijs/max';
import { TeamOutlined, PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import SearchFilterBar, { SearchInput, ActionButton } from '@/components/SearchFilterBar';
import { getAgentPage } from '@/services/ant-design-pro/agent';
import {
  createTeam,
  deleteTeam,
  getTeamPage,
  getTeamRelatedSessions,
  toggleTeam,
  updateTeam,
} from '@/services/ant-design-pro/team';
import StatusSwitch from '@/components/StatusSwitch';
import AgentRefreshModal from '@/pages/agent/components/AgentRefreshModal';
import TeamWizard from './components/TeamWizard';

const { Text } = Typography;

const TeamManagement: React.FC = () => {
  const intl = useIntl();

  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [updateModalVisible, setUpdateModalVisible] = useState(false);
  const [currentRow, setCurrentRow] = useState<API.TeamItem>();
  const [refreshTeam, setRefreshTeam] = useState<API.TeamItem>();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<API.TeamItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [agents, setAgents] = useState<API.AgentItem[]>([]);

  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  const filtersRef = useRef({ keyword: '' });
  const [messageApi, contextHolder] = message.useMessage();

  const loadData = async (page = 1, size = pageSize) => {
    const { keyword: name } = filtersRef.current;
    setLoading(true);
    try {
      const res = await getTeamPage({
        current: page,
        size,
        name: name || undefined,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch {
      messageApi.error(
        intl.formatMessage({
          id: 'pages.message.operationFailed',
          defaultMessage: 'Operation failed, please try again',
        }),
      );
    } finally {
      setLoading(false);
    }
  };

  // 成员下拉框的初始候选：向导里打字时按名字搜全量（见 MembersField）
  const loadAgents = async () => {
    try {
      const res = await getAgentPage({ pageNum: 1, pageSize: 200, status: 1 });
      setAgents(res.data?.records || []);
    } catch {
      setAgents([]);
    }
  };

  useEffect(() => {
    loadData(pageNum, pageSize);
  }, [pageNum, pageSize]);

  useEffect(() => {
    loadAgents();
    return () => {
      if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    };
  }, []);

  const handleKeywordChange = (value: string) => {
    setKeyword(value);
    filtersRef.current.keyword = value;
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    searchTimerRef.current = setTimeout(() => {
      setPageNum(1);
      loadData(1);
    }, 500);
  };

  const handleReset = () => {
    setKeyword('');
    filtersRef.current = { keyword: '' };
    setPageNum(1);
    loadData(1);
  };

  const handleToggle = async (id: number, newStatus: number) => {
    try {
      const response = await toggleTeam(id, newStatus);
      if (response.code === 200) {
        messageApi.success(
          newStatus === 1
            ? intl.formatMessage({ id: 'pages.message.enableSuccess', defaultMessage: 'Enabled successfully' })
            : intl.formatMessage({ id: 'pages.message.disableSuccess', defaultMessage: 'Disabled successfully' }),
        );
        loadData(pageNum, pageSize);
      } else {
        messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
      }
    } catch (error: any) {
      messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
    }
  };

  /**
   * Sessions still binding this team, or `failed` when admin could not be asked. Only the count is read
   * here. An empty list is the answer that opens the delete dialog with the "nothing depends on it"
   * wording, so a failure must never be reported as one.
   */
  const relatedSessions = async (id: number): Promise<{ sessions: unknown[] } | { failed: true }> => {
    try {
      // skipErrorHandler: the caller shows the one error message for this read and has to tell a failure
      // apart from a genuinely empty list.
      const res = await getTeamRelatedSessions(id, { skipErrorHandler: true });
      return res.code === 200 ? { sessions: res.data || [] } : { failed: true };
    } catch {
      return { failed: true };
    }
  };

  const handleDelete = async (record: API.TeamItem) => {
    const result = await relatedSessions(record.id);
    if ('failed' in result) {
      // No dialog offered: until admin answers, the page cannot say whether the delete would even be refused.
      messageApi.error(
        intl.formatMessage({
          id: 'pages.message.loadFailedRetry',
          defaultMessage: 'Failed to load the related list, please retry',
        }),
      );
      return;
    }
    const sessions = result.sessions;
    Modal.confirm({
      title: intl.formatMessage({
        id: 'pages.team.deleteConfirm',
        defaultMessage: 'Are you sure to delete this team?',
      }),
      content: sessions.length
        ? intl.formatMessage(
            {
              id: 'pages.team.deleteBlockedContent',
              defaultMessage:
                '{count} session(s) still bind this team, so deleting it will be refused. Delete those sessions first — that is also what removes their artifacts.',
            },
            { count: sessions.length },
          )
        : intl.formatMessage({
            id: 'pages.team.deleteConfirmContent',
            defaultMessage: 'This cannot be undone. Members and the lead skill bindings are removed with it.',
          }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteTeam(record.id);
          if (response.code === 200) {
            messageApi.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
            loadData(pageNum, pageSize);
          } else {
            messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' }));
          }
        } catch (error: any) {
          messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' }));
        }
      },
    });
  };

  const columns: ColumnsType<API.TeamItem> = [
    {
      title: intl.formatMessage({ id: 'pages.team.name', defaultMessage: 'Team Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 180,
      ellipsis: true,
      render: (text: string, record) => (
        <Tooltip title={record.description}>
          <Text strong>{text}</Text>
        </Tooltip>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.team.model', defaultMessage: 'Lead Model' }),
      dataIndex: 'modelName',
      key: 'modelName',
      width: 150,
      ellipsis: true,
      render: (text: string, record) => (
        <Tag color="blue">{text || `#${record.modelId}`}</Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.team.members', defaultMessage: 'Members' }),
      dataIndex: 'memberList',
      key: 'memberList',
      render: (members: API.TeamMemberItem[] = []) =>
        members.length === 0 ? (
          <Text type="secondary">-</Text>
        ) : (
          <Space size={4} wrap>
            {members.map((member) => (
              <Tooltip
                key={member.agentId}
                title={
                  member.agentAvailable === false
                    ? intl.formatMessage({
                        id: 'pages.team.memberUnavailable',
                        defaultMessage: 'referenced agent is deleted or disabled',
                      })
                    : member.delegationDescription || member.agentDescription
                }
              >
                <Tag color={member.agentAvailable === false ? 'red' : 'default'}>
                  {member.agentName || `#${member.agentId}`}
                </Tag>
              </Tooltip>
            ))}
          </Space>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      align: 'center',
      render: (val: number, record) => (
        <StatusSwitch status={val ?? 1} onChange={(newStatus) => handleToggle(record.id, newStatus)} />
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.creator', defaultMessage: 'Creator' }),
      dataIndex: 'creator',
      key: 'creator',
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.common.createTime', defaultMessage: 'Created At' }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      render: (text: string) => text?.replace('T', ' ')?.substring(0, 19),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.operation', defaultMessage: 'Actions' }),
      key: 'actions',
      width: 120,
      align: 'center',
      render: (_: any, record) => (
        <Space>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setCurrentRow(record);
                setUpdateModalVisible(true);
              }}
            />
          </Tooltip>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
            <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(record)} />
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <TeamOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.team.title', defaultMessage: 'Teams' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      <SearchFilterBar
        onSearch={() => {}}
        onReset={handleReset}
        showSearchButton={false}
        searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
        resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
        extra={
          <ActionButton type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
            {intl.formatMessage({ id: 'pages.team.create', defaultMessage: 'Create Team' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={handleKeywordChange}
          placeholder={intl.formatMessage({
            id: 'pages.team.searchPlaceholder',
            defaultMessage: 'Search team name',
          })}
          width="auto"
        />
      </SearchFilterBar>

      <Table
        columns={columns}
        dataSource={data}
        rowKey="id"
        loading={loading}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          pageSizeOptions: ['10', '20', '50'],
          onChange: (page, size) => {
            setPageNum(page);
            setPageSize(size);
          },
        }}
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({ id: 'pages.team.noData', defaultMessage: 'No teams' })}
            />
          ),
        }}
      />

      <TeamWizard
        visible={createModalVisible}
        agents={agents}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (payload) => {
          try {
            const response = await createTeam({ ...payload, status: 1 });
            if (response.code === 200) {
              messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
              setCreateModalVisible(false);
              loadData(pageNum, pageSize);
            } else {
              messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' }));
            }
          } catch (error: any) {
            messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' }));
          }
        }}
      />

      {currentRow && (
        <TeamWizard
          visible={updateModalVisible}
          values={currentRow}
          agents={agents}
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          onSubmit={async (payload) => {
            try {
              const response = await updateTeam(currentRow.id, payload);
              if (response.code === 200) {
                messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
                setUpdateModalVisible(false);
                // 已在跑的团队会话仍持有旧成员配置，让用户自己挑要立刻刷新的那些
                setRefreshTeam(currentRow);
                setCurrentRow(undefined);
                loadData(pageNum, pageSize);
              } else {
                messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' }));
              }
            } catch (error: any) {
              messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' }));
            }
          }}
        />
      )}

      <AgentRefreshModal
        visible={!!refreshTeam}
        source="team"
        agentId={refreshTeam?.id}
        agentName={refreshTeam?.name}
        onClose={() => setRefreshTeam(undefined)}
      />
    </PageContainer>
  );
};

export default TeamManagement;
