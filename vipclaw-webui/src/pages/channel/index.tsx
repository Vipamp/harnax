import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Card,
  Col,
  Empty,
  Input,
  message,
  Modal,
  Pagination,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  Badge,
  Popover,
} from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import {
  createChannel,
  updateChannel,
  deleteChannel,
  getChannelPage,
  toggleChannelStatus,
  getAgentList,
} from '@/services/ant-design-pro/channel';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ApiOutlined,
  SearchOutlined,
  CopyOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

const { Text, Paragraph } = Typography;

// Channel 类型选项
const CHANNEL_TYPES = [
  { label: '企业微信', value: 'wecom' },
  { label: '飞书', value: 'feishu' },
  { label: '钉钉', value: 'dingtalk' },
  { label: 'HTTP接口', value: 'http' },
];

const ChannelManagement: React.FC = () => {
  const intl = useIntl();
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
      title: '确认删除该 Channel 吗？',
      content: '此操作不可恢复，请谨慎操作',
      okText: '确定',
      cancelText: '取消',
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
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: '通道名称',
      dataIndex: 'name',
      key: 'name',
      width: 150,
      render: (text: string) => <Text strong>{text}</Text>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 100,
      render: (type: string) => (
        <Tag color={getTypeColor(type)}>
          {CHANNEL_TYPES.find(t => t.value === type)?.label || type}
        </Tag>
      ),
    },
    {
      title: '关联智能体',
      dataIndex: 'agentName',
      key: 'agentName',
      width: 150,
      render: (text: string) => text || '-',
    },
    {
      title: '回调 URL',
      dataIndex: 'callbackUrl',
      key: 'callbackUrl',
      width: 250,
      render: (url: string, record: API.ChannelItem) => (
        url ? (
          <Space>
            <Paragraph 
              copyable={{ text: url }} 
              style={{ margin: 0, maxWidth: 200 }}
              ellipsis
            >
              {url}
            </Paragraph>
          </Space>
        ) : '-'
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: number, record: API.ChannelItem) => (
        <Switch
          checked={status === 1}
          onChange={(checked) => handleToggleStatus(record.id!, checked ? 1 : 0)}
          checkedChildren="启用"
          unCheckedChildren="禁用"
          style={{ backgroundColor: status === 1 ? '#4f6ef7' : '#d9d9d9' }}
        />
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      width: 180,
      render: (text: string) => text?.replace('T', ' ') || '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: any, record: API.ChannelItem) => (
        <Space size={4}>
          <Tooltip title="编辑">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setCurrentRow(record);
                setUpdateModalVisible(true);
              }}
              style={{ color: '#4f6ef7' }}
            />
          </Tooltip>
          <Tooltip title="删除">
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => handleRemove(record.id!)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <ApiOutlined style={{ marginRight: 10, color: '#1890ff' }} />
            Channel 管理
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <Card
        style={{
          marginBottom: 24,
          borderRadius: '16px',
          boxShadow: '0 2px 12px rgba(0,0,0,0.04)',
          border: '1px solid #f0f0f8',
        }}
        styles={{ body: { padding: '20px 24px' } }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 300 }}>
            <Input
              placeholder="搜索通道名称"
              prefix={<SearchOutlined style={{ color: '#8c8c9a' }} />}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={handleSearch}
              style={{
                width: 200,
                borderRadius: '10px',
                height: '40px',
              }}
              allowClear
            />
            <Select
              placeholder="类型筛选"
              value={typeFilter}
              onChange={(val) => setTypeFilter(val)}
              style={{ width: 120 }}
              allowClear
              options={CHANNEL_TYPES}
            />
            <Select
              placeholder="状态筛选"
              value={statusFilter}
              onChange={(val) => setStatusFilter(val)}
              style={{ width: 100 }}
              allowClear
              options={[
                { label: '启用', value: 1 },
                { label: '禁用', value: 0 },
              ]}
            />
            <Button
              type="primary"
              onClick={handleSearch}
              style={{
                borderRadius: '10px',
                height: '40px',
                padding: '0 20px',
              }}
            >
              查询
            </Button>
            <Button
              onClick={() => { setKeyword(''); setTypeFilter(undefined); setStatusFilter(undefined); setPageNum(1); loadData(1); }}
              style={{
                borderRadius: '10px',
                height: '40px',
                padding: '0 20px',
              }}
            >
              重置
            </Button>
          </div>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{
              borderRadius: '10px',
              height: '44px',
              padding: '0 24px',
              fontWeight: 600,
              fontSize: '14px',
              boxShadow: '0 4px 16px rgba(79, 110, 247, 0.3)',
            }}
          >
            新建 Channel
          </Button>
        </div>
      </Card>

      {/* 数据表格 */}
      <Card
        style={{
          borderRadius: '16px',
          boxShadow: '0 2px 12px rgba(0,0,0,0.04)',
          border: '1px solid #f0f0f8',
        }}
      >
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          pagination={false}
          scroll={{ x: 1200 }}
        />

        {/* 分页 */}
        <div style={{ marginTop: 24, display: 'flex', justifyContent: 'flex-end' }}>
          <Pagination
            current={pageNum}
            pageSize={pageSize}
            total={total}
            showSizeChanger
            showQuickJumper
            showTotal={(t) => `共 ${t} 条`}
            onChange={(page, size) => {
              setPageNum(page);
              if (size) setPageSize(size);
            }}
            style={{ padding: '12px 24px', background: '#fff', borderRadius: '10px' }}
          />
        </div>
      </Card>

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
