import React, { useState, useEffect, useMemo } from 'react';
import { useIntl } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import {
  message,
  Modal,
  Space,
  Tag,
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
  PlusOutlined,
  ApiOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import StatusSwitch from '@/components/StatusSwitch';
import StyledProTable from '@/components/StyledProTable';

const { Text, Paragraph } = Typography;

const ChannelManagement: React.FC = () => {
  const intl = useIntl();
  // Channel 类型选项
  const CHANNEL_TYPES = [
    { label: intl.formatMessage({ id: 'pages.channel.type.wecom', defaultMessage: 'WeCom' }), value: 'wecom' },
    { label: intl.formatMessage({ id: 'pages.channel.type.feishu', defaultMessage: 'Feishu' }), value: 'feishu' },
    { label: intl.formatMessage({ id: 'pages.channel.type.dingtalk', defaultMessage: 'DingTalk' }), value: 'dingtalk' },
    { label: intl.formatMessage({ id: 'pages.channel.type.http', defaultMessage: 'HTTP' }), value: 'http' },
  ];
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
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

  /** 加载数据 */
  const loadData = async (page = pageNum, size = pageSize) => {
    setLoading(true);
    try {
      const res = await getChannelPage({
        pageNum: page,
        pageSize: size,
        keyword: keyword || undefined,
        type: typeFilter,
        status: statusFilter,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    loadAgents();
  }, [pageNum, pageSize, typeFilter, statusFilter]);

  /** 搜索 */
  const handleSearch = () => {
    setPageNum(1);
    loadData(1);
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
            loadData();
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
      await toggleChannelStatus(id, newStatus);
      messageApi.success(newStatus === 1 ? intl.formatMessage({ id: 'pages.message.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.message.disabled', defaultMessage: 'Disabled' }));
      setData((prevData) =>
        prevData.map((item) =>
          item.id === id ? { ...item, status: newStatus } : item
        )
      );
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
      title: intl.formatMessage({ id: 'pages.channel.table.callbackUrl', defaultMessage: 'Callback URL' }),
      dataIndex: 'callbackUrl',
      key: 'callbackUrl',
      width: 280,
      align: 'center' as const,
      render: (url: string, record: API.ChannelItem) => (
        url ? (
          <Space>
            <Paragraph 
              copyable={{ text: url }} 
              style={{ margin: 0, maxWidth: 240 }}
              ellipsis
            >
              {url}
            </Paragraph>
          </Space>
        ) : '-'
      ),
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
      width: 200,
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
            <ApiOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.channel.title', defaultMessage: 'Channel Management' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <SearchFilterBar
        onSearch={handleSearch}
        onReset={() => { setKeyword(''); setTypeFilter(undefined); setStatusFilter(undefined); setPageNum(1); loadData(1); }}
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
          onChange={setKeyword}
          onSearch={handleSearch}
          placeholder={intl.formatMessage({ id: 'pages.channel.search.placeholder.name', defaultMessage: 'Search channel name' })}
          width="auto"
        />
        <FilterSelect
          value={typeFilter}
          onChange={setTypeFilter}
          placeholder={intl.formatMessage({ id: 'pages.channel.filter.placeholder.type', defaultMessage: 'Filter by type' })}
          width="auto"
          options={CHANNEL_TYPES}
        />
        <FilterSelect
          value={statusFilter}
          onChange={setStatusFilter}
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
        scroll={{ x: 1200 }}
      />

      {/* 新建 Channel 弹窗 */}
      <CreateForm
        visible={createModalVisible}
        agents={agents}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            await createChannel(values);
            messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
            setCreateModalVisible(false);
            loadData();
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
              await updateChannel(currentRow.id!, values);
              messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
              setUpdateModalVisible(false);
              setCurrentRow(undefined);
              loadData();
            } catch (error) {
              messageApi.error(intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed, please try again' }));
            }
          }}
        />
      )}
    </PageContainer>
  );
};

export default ChannelManagement;
