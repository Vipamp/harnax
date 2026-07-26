import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useIntl } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  message,
  Modal,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  createChannel,
  updateChannel,
  deleteChannel,
  getChannelPage,
  toggleChannelStatus,
  getAgentList,
} from '@/services/ant-design-pro/channel';
import {
  batchGetWorkspaceStatus,
} from '@/services/ant-design-pro/workspace';
import {
  PlusOutlined,
  LinkOutlined,
  CloudServerOutlined,
  WechatOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import WechatLoginModal from './components/WechatLoginModal';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import StatusSwitch from '@/components/StatusSwitch';
import StyledProTable from '@/components/StyledProTable';
import WorkspaceDrawer from '@/pages/session/components/WorkspaceDrawer';

const { Text, Paragraph } = Typography;

const ChannelManagement: React.FC = () => {
  const intl = useIntl();
  // Channel 类型选项
  const CHANNEL_TYPES = [
    { label: intl.formatMessage({ id: 'pages.channel.type.wecom', defaultMessage: 'WeCom' }), value: 'wecom' },
    { label: intl.formatMessage({ id: 'pages.channel.type.wechat', defaultMessage: 'WeChat' }), value: 'wechat' },
    { label: intl.formatMessage({ id: 'pages.channel.type.feishu', defaultMessage: 'Feishu' }), value: 'feishu' },
    { label: intl.formatMessage({ id: 'pages.channel.type.dingtalk', defaultMessage: 'DingTalk' }), value: 'dingtalk' },
    { label: intl.formatMessage({ id: 'pages.channel.type.http', defaultMessage: 'HTTP' }), value: 'http' },
  ];
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [wechatLoginVisible, setWechatLoginVisible] = useState<boolean>(false);
  const [wechatLoginChannelId, setWechatLoginChannelId] = useState<number>();
  const [currentRow, setCurrentRow] = useState<API.ChannelItem>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.ChannelItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [statusFilter, setStatusFilter] = useState<number | undefined>(undefined);
  const [agents, setAgents] = useState<API.AgentItem[]>([]);
  const [workspaceVisible, setWorkspaceVisible] = useState(false);
  const [workspaceSessionId, setWorkspaceSessionId] = useState<string>();
  const [sandboxStatusMap, setSandboxStatusMap] = useState<Record<string, boolean>>({});
  
  // 防抖定时器引用
  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  
  // 使用 ref 保存最新的筛选参数，避免闭包问题
  const filtersRef = useRef({
    keyword: '',
    typeFilter: undefined as string | undefined,
    statusFilter: undefined as number | undefined,
  });

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);
  const [messageApi, contextHolder] = message.useMessage();

  /** 加载智能体列表 */
  const loadAgents = async () => {
    try {
      const res = await getAgentList({ pageNum: 1, pageSize: 100 });
      const enabledAgents = (res.data?.records || []).filter((item: any) => item.status === 1);
      setAgents(enabledAgents);
    } catch (error) {
      console.error('加载智能体列表失败', error);
    }
  };

  /** 加载数据（使用 ref 中的最新筛选参数，避免闭包问题） */
  const loadDataWithFilters = async (page = 1, size = pageSize) => {
    const { keyword: kw, typeFilter: tf, statusFilter: sf } = filtersRef.current;
    
    setLoading(true);
    try {
      const res = await getChannelPage({
        pageNum: page,
        pageSize: size,
        keyword: kw || undefined,
        type: tf,
        status: sf,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
      // Batch fetch sandbox status for channels with sessionId
      const sessionsWithId = (res.data?.records || [])
        .filter((r: API.ChannelItem) => r.sessionId)
        .map((r: API.ChannelItem) => r.sessionId as string);
      if (sessionsWithId.length > 0) {
        try {
          const statusRes = await batchGetWorkspaceStatus(sessionsWithId);
          if (statusRes.code === 200 && statusRes.data) {
            const map: Record<string, boolean> = {};
            Object.entries(statusRes.data).forEach(([sid, info]) => {
              map[sid] = info.active;
            });
            setSandboxStatusMap(map);
          }
        } catch {
          // ignore sandbox status fetch errors
        }
      }
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDataWithFilters(pageNum, pageSize);
    loadAgents();
  }, [pageNum, pageSize]);
  
  // 组件卸载时清理定时器
  useEffect(() => {
    return () => {
      if (searchTimerRef.current) {
        clearTimeout(searchTimerRef.current);
      }
    };
  }, []);

  /** 关键词变化（带防抖） */
  const handleKeywordChange = (value: string) => {
    setKeyword(value);
    filtersRef.current.keyword = value;
    
    if (searchTimerRef.current) {
      clearTimeout(searchTimerRef.current);
    }
    
    searchTimerRef.current = setTimeout(() => {
      setPageNum(1);
      loadDataWithFilters(1);
    }, 500);
  };
  
  /** 类型筛选改变 */
  const handleTypeChange = (value: string | undefined) => {
    setTypeFilter(value);
    filtersRef.current.typeFilter = value;
    setPageNum(1);
    loadDataWithFilters(1);
  };
  
  /** 状态筛选改变 */
  const handleStatusChange = (value: number | undefined) => {
    setStatusFilter(value);
    filtersRef.current.statusFilter = value;
    setPageNum(1);
    loadDataWithFilters(1);
  };
  
  /** 重置筛选 */
  const handleReset = () => {
    setKeyword('');
    setTypeFilter(undefined);
    setStatusFilter(undefined);
    filtersRef.current = {
      keyword: '',
      typeFilter: undefined,
      statusFilter: undefined,
    };
    setPageNum(1);
    loadDataWithFilters(1);
  };

  /** 删除 Channel */
  const handleRemove = async (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.channel.delete.confirm.title', defaultMessage: 'Confirm deletion?' }),
      content: intl.formatMessage({ id: 'pages.channel.delete.confirm.content', defaultMessage: 'This operation cannot be undone. Please proceed with caution.' }),
      okText: intl.formatMessage({ id: 'pages.channel.delete.confirm.ok', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.channel.delete.confirm.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteChannel(id);
          if (response.code === 200) {
            messageApi.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
            loadDataWithFilters()
          } else {
            const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
            messageApi.error(errorMsg);
          }
        } catch (error: any) {
          const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
          messageApi.error(errorMsg);
        }
      },
    });
  };

  /** 切换启用状态 */
  const handleToggleStatus = async (id: number, newStatus: number) => {
    try {
      const response = await toggleChannelStatus(id, newStatus);
      if (response.code === 200) {
        messageApi.success(newStatus === 1 ? intl.formatMessage({ id: 'pages.message.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.message.disabled', defaultMessage: 'Disabled' }));
        setData((prevData) =>
          prevData.map((item) =>
            item.id === id ? { ...item, status: newStatus } : item
          )
        );
      } else {
        messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
      }
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    }
  };

  /** 复制回调 URL */
  const copyCallbackUrl = (url: string) => {
    navigator.clipboard.writeText(url);
    messageApi.success(intl.formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }));
  };

  /** 获取类型标签颜色 */
  const getTypeColor = (type: string) => {
    const colorMap: Record<string, string> = {
      wecom: 'green',
      wechat: 'lime',
      feishu: 'blue',
      dingtalk: 'cyan',
      http: 'orange',
    };
    return colorMap[type] || 'default';
  };

  // 表格列定义
  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.common.id', defaultMessage: 'ID' }),
      dataIndex: 'id',
      key: 'id',
      width: 70,
      align: 'center' as const,
    },
    {
      title: intl.formatMessage({ id: 'pages.channel.table.name', defaultMessage: 'Channel Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 180,
      align: 'center' as const,
      render: (text: string) => <Text strong>{text}</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.channel.table.type', defaultMessage: 'Type' }),
      dataIndex: 'type',
      key: 'type',
      width: 120,
      align: 'center' as const,
      render: (type: string) => (
        <Tag color={getTypeColor(type)}>
          {CHANNEL_TYPES.find(t => t.value === type)?.label || type}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.channel.table.agent', defaultMessage: 'Associated Agent' }),
      dataIndex: 'agentName',
      key: 'agentName',
      width: 150,
      align: 'center' as const,
      render: (text: string) => text || '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.channel.table.sessionId', defaultMessage: 'Session ID' }),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 200,
      align: 'center' as const,
      render: (sessionId: string) => sessionId ? (
        <Paragraph
          copyable={{ text: sessionId }}
          style={{ margin: 0, maxWidth: 180, fontSize: 12, fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace' }}
          ellipsis
        >
          {sessionId}
        </Paragraph>
      ) : '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.channel.table.sandbox', defaultMessage: 'Sandbox' }),
      dataIndex: 'sessionId',
      key: 'sandbox',
      width: 160,
      align: 'center' as const,
      render: (sessionId: string) => {
        if (!sessionId) return '-';
        const active = sandboxStatusMap[sessionId];
        if (active === undefined) return <Tag>-</Tag>;
        return (
          <Space size={4}>
            <Tag color={active ? 'green' : 'default'}>
              {active
                ? intl.formatMessage({ id: 'pages.channel.sandbox.running', defaultMessage: 'Running' })
                : intl.formatMessage({ id: 'pages.channel.sandbox.inactive', defaultMessage: 'Inactive' })}
            </Tag>
            <Tooltip title={active ? 'Workspace' : 'Sandbox not running'}>
              <Button
                type="text"
                size="small"
                icon={<CloudServerOutlined />}
                disabled={!active}
                onClick={() => {
                  setWorkspaceSessionId(sessionId);
                  setWorkspaceVisible(true);
                }}
              />
            </Tooltip>
          </Space>
        );
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.channel.table.createTime', defaultMessage: 'Creation Time' }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      align: 'center' as const,
      render: (text: string) => text?.replace('T', ' ') || '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.common.operation', defaultMessage: 'Action' }),
      key: 'action',
      width: 180,
      align: 'center' as const,
      render: (_: any, record: API.ChannelItem) => {
        const canOperate = hasOperationPermission(isAdmin, currentUser, record.creator);
        return canOperate ? (
          <Space size={8}>
            <StatusSwitch
              status={record.status}
              onChange={(newStatus) => handleToggleStatus(record.id!, newStatus)}
              disabled={!canOperate}
            />
            {record.type === 'wechat' && (
              <Tooltip title={intl.formatMessage({ id: 'pages.channel.wechat.title', defaultMessage: 'WeChat Scan Login' })}>
                <Button
                  type="text"
                  size="small"
                  icon={<WechatOutlined style={{ color: '#07C160' }} />}
                  onClick={() => {
                    setWechatLoginChannelId(record.id!);
                    setWechatLoginVisible(true);
                  }}
                />
              </Tooltip>
            )}
            <EditButton onClick={() => {
              setCurrentRow(record);
              setUpdateModalVisible(true);
            }} />
            <DeleteButton 
              onConfirm={() => handleRemove(record.id!)}
              confirmTitle={intl.formatMessage({ id: 'pages.channel.deleteConfirm', defaultMessage: 'Are you sure to delete this channel?' })}
            />
          </Space>
        ) : null;
      },
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <LinkOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.channel.title', defaultMessage: 'Channel Management' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <SearchFilterBar
        onSearch={() => {}}
        onReset={handleReset}
        showSearchButton={false}
        searchText={intl.formatMessage({ id: 'pages.channel.button.search', defaultMessage: 'Search' })}
        resetText={intl.formatMessage({ id: 'pages.channel.button.reset', defaultMessage: 'Reset' })}
        extra={
          <ActionButton
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
          >
            {intl.formatMessage({ id: 'pages.channel.button.create', defaultMessage: 'Create Channel' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={handleKeywordChange}
          placeholder={intl.formatMessage({ id: 'pages.channel.search.placeholder.name', defaultMessage: 'Search channel name' })}
          width="auto"
        />
        <FilterSelect
          value={typeFilter}
          onChange={handleTypeChange}
          placeholder={intl.formatMessage({ id: 'pages.channel.filter.placeholder.type', defaultMessage: 'Filter by type' })}
          width="auto"
          options={CHANNEL_TYPES}
        />
        <FilterSelect
          value={statusFilter}
          onChange={handleStatusChange}
          placeholder={intl.formatMessage({ id: 'pages.channel.filter.placeholder.status', defaultMessage: 'Filter by status' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.channel.status.enabled', defaultMessage: 'Enabled' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.channel.status.disabled', defaultMessage: 'Disabled' }), value: 0 },
          ]}
        />
      </SearchFilterBar>

      {/* 数据表格 */}
      <StyledProTable<API.ChannelItem>
        headerTitle={undefined}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => intl.formatMessage(
            { id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' },
            { total: t }
          ),
          onChange: (page, size) => {
            setPageNum(page);
            if (size) setPageSize(size);
          },
        }}
        dataSource={data}
        search={false}
        toolBarRender={false}
        columns={columns}
        scroll={{ x: 1500 }}
      />

      {/* 新建 Channel 弹窗 */}
      <CreateForm
        visible={createModalVisible}
        agents={agents}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            const response = await createChannel(values);
            if (response.code === 200) {
              messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
              setCreateModalVisible(false);
              loadDataWithFilters()
            } else {
              messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed, please try again' }));
            }
          } catch (error) {
            messageApi.error(intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed, please try again' }));
          }
        }}
      />

      {/* 编辑 Channel 弹窗 */}
      {currentRow && (
        <UpdateForm
          visible={updateModalVisible}
          values={currentRow}
          agents={agents}
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          onSubmit={async (values) => {
            try {
              const response = await updateChannel(currentRow.id!, values);
              if (response.code === 200) {
                messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
                setUpdateModalVisible(false);
                setCurrentRow(undefined);
                loadDataWithFilters()
              } else {
                messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed, please try again' }));
              }
            } catch (error) {
              messageApi.error(intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed, please try again' }));
            }
          }}
        />
      )}
      {/* WeChat 扫码登录弹窗 */}
      <WechatLoginModal
        visible={wechatLoginVisible}
        channelId={wechatLoginChannelId}
        onCancel={() => {
          setWechatLoginVisible(false);
          setWechatLoginChannelId(undefined);
        }}
        onSuccess={() => {
          setWechatLoginVisible(false);
          setWechatLoginChannelId(undefined);
          loadDataWithFilters();
        }}
      />
      {/* Workspace Drawer */}
      <WorkspaceDrawer
        visible={workspaceVisible}
        sessionId={workspaceSessionId}
        onClose={() => setWorkspaceVisible(false)}
      />
    </PageContainer>
  );
};

export default ChannelManagement;
