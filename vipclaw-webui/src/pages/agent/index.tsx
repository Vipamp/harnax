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
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import {
  createAgent,
  updateAgent,
  deleteAgent,
  getAgentPage,
  toggleAgentStatus,
} from '@/services/ant-design-pro/agent';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined,
  ApiOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

const { Text, Paragraph } = Typography;

const AgentManagement: React.FC = () => {
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.AgentItem>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.AgentItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [current, setCurrent] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(12);
  const [keyword, setKeyword] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);
  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = current, size = pageSize) => {
    setLoading(true);
    try {
      const res = await getAgentPage({
        pageNum: page,
        pageSize: size,
        name: keyword || undefined,
        status: status,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      messageApi.error('获取数据失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [current, pageSize, status]);

  /** 搜索 */
  const handleSearch = () => {
    setCurrent(1);
    loadData(1);
  };

  /** 删除智能体 */
  const handleRemove = async (id: number) => {
    Modal.confirm({
      title: '确认删除该智能体吗？',
      content: '此操作不可恢复，请谨慎操作',
      okText: '确定',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteAgent(id);
          messageApi.success('删除成功');
          loadData();
        } catch (error) {
          messageApi.error('删除失败，请重试');
        }
      },
    });
  };

  /** 切换启用状态 */
  const handleToggleStatus = async (id: number, newStatus: number) => {
    try {
      await toggleAgentStatus(id, newStatus);
      messageApi.success(newStatus === 1 ? '已启用' : '已禁用');
      // 只更新当前卡片状态，不重新加载整个列表
      setData((prevData) =>
        prevData.map((item) =>
          item.id === id ? { ...item, status: newStatus } : item
        )
      );
    } catch (error) {
      messageApi.error('操作失败，请重试');
    }
  };

  /** 渲染单个智能体卡片 */
  const renderCard = (item: API.AgentItem) => {
    const mcpCount = item.mcpList?.length || 0;
    const skillCount = item.skillList?.length || 0;

    return (
      <Card
        key={item.id}
        hoverable
        style={{
          borderRadius: '16px',
          border: 'none',
          boxShadow: '0 4px 20px rgba(0,0,0,0.06)',
          overflow: 'hidden',
          position: 'relative',
          transition: 'all 0.3s ease',
          transform: 'translateY(0)',
        }}
        styles={{ body: { padding: 0 } }}
        onMouseEnter={(e) => {
          e.currentTarget.style.transform = 'translateY(-4px)';
          e.currentTarget.style.boxShadow = '0 8px 24px rgba(0, 0, 0, 0.12)';
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.transform = 'translateY(0)';
          e.currentTarget.style.boxShadow = '0 4px 20px rgba(0,0,0,0.06)';
        }}
      >
        {/* 顶部类型标识条 */}
        <div
          style={{
            height: '4px',
            background: `linear-gradient(135deg, #722ed1 0%, #b37feb 100%)`,
          }}
        />

        <div style={{ padding: '20px' }}>
          {/* 头部：图标 + 名称 + 状态 + 标签 */}
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
            <div
              style={{
                width: 48,
                height: 48,
                borderRadius: '12px',
                background: '#722ed115',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '22px',
                color: '#722ed1',
                flexShrink: 0,
              }}
            >
              <RobotOutlined />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4, flexWrap: 'wrap' }}>
                <Text strong style={{ fontSize: '16px', color: '#1a1a2e' }}>
                  {item.name}
                </Text>
                {/* 对话模型标签 */}
                {item.modelName && (
                  <Tag color="blue" icon={<ApiOutlined />} style={{ fontSize: '12px' }}>
                    {item.modelName}
                  </Tag>
                )}
              </div>
              {/* MCP 和 Skill 统计标签 */}
              <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
                <Tag color="purple" icon={<ApiOutlined />} style={{ fontSize: '12px' }}>
                  MCP: {mcpCount}
                </Tag>
                <Tag color="green" icon={<ToolOutlined />} style={{ fontSize: '12px' }}>
                  Skill：{skillCount}
                </Tag>
              </div>
            </div>
            {/* 状态开关 */}
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              {hasOperationPermission(isAdmin, currentUser, item.creator) && (
                <Switch
                  checked={item.status === 1}
                  onChange={(checked) => handleToggleStatus(item.id!, checked ? 1 : 0)}
                  checkedChildren="启用"
                  unCheckedChildren="禁用"
                  style={{
                    backgroundColor: item.status === 1 ? '#4f6ef7' : '#d9d9d9',
                  }}
                />
              )}
            </div>
          </div>

          {/* 描述 */}
          <Paragraph
            ellipsis={{ rows: 2 }}
            style={{ margin: '0 0 12px', color: '#666', fontSize: '13px', minHeight: 40 }}
          >
            {item.description || '暂无描述'}
          </Paragraph>

          {/* 是否公开、创建时间、所有者、创建人和操作按钮 */}
          <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8, borderTop: '1px solid #f0f0f0', paddingTop: '12px' }}>
            {item.isPublic === 1 && (
              <Tag color="blue" style={{ marginRight: 4 }}>公开</Tag>
            )}
            <Text type="secondary" style={{ fontSize: '12px' }}>
              {item.createTime?.replace('T', ' ')}
            </Text>
            {item.owner && (
              <Text type="secondary" style={{ fontSize: '11px' }}>
                {item.owner}
              </Text>
            )}
            {item.creator && (
              <Text type="secondary" style={{ fontSize: '11px' }}>{item.creator}</Text>
            )}
            <div style={{ flex: 1 }} />
            {hasOperationPermission(isAdmin, currentUser, item.creator) && (
              <Space size={8}>
                <Tooltip title="编辑">
                  <Button
                    type="link"
                    size="small"
                    icon={<EditOutlined />}
                    onClick={() => {
                      setCurrentRow(item);
                      setUpdateModalVisible(true);
                    }}
                    style={{ padding: '4px', color: '#1890ff' }}
                  />
                </Tooltip>
                <Tooltip title="删除">
                  <Button
                    type="link"
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() => handleRemove(item.id!)}
                    style={{ padding: '4px' }}
                  />
                </Tooltip>
              </Space>
            )}
          </div>
        </div>
      </Card>
    );
  };

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <RobotOutlined style={{ marginRight: 10, color: '#722ed1' }} />
            智能体管理
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <Card
        style={{ marginBottom: 24, borderRadius: '12px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
        styles={{ body: { padding: '16px 20px' } }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <Input
            placeholder="搜索智能体名称"
            prefix={<SearchOutlined />}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={handleSearch}
            style={{ width: 280, borderRadius: '8px' }}
            allowClear
          />
          <Select
            placeholder="状态筛选"
            value={status}
            onChange={(val) => setStatus(val)}
            style={{ width: 140, borderRadius: '8px' }}
            allowClear
            options={[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]}
          />
          <Button type="primary" onClick={handleSearch} style={{ borderRadius: '8px' }}>
            查询
          </Button>
          <Button onClick={() => { setKeyword(''); setStatus(undefined); setCurrent(1); loadData(1); }} style={{ borderRadius: '8px' }}>
            重置
          </Button>
          <div style={{ flex: 1 }} />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{ borderRadius: '8px', fontWeight: 600 }}
          >
            新建智能体
          </Button>
        </div>
      </Card>

      {/* 卡片列表 */}
      {data.length > 0 ? (
        <>
          <Row gutter={[20, 20]}>
            {data.map((item) => (
              <Col xs={24} sm={12} lg={8} xl={6} key={item.id}>
                {renderCard(item)}
              </Col>
            ))}
          </Row>

          {/* 分页 */}
          <div style={{ marginTop: 32, display: 'flex', justifyContent: 'center' }}>
            <Pagination
              current={current}
              pageSize={pageSize}
              total={total}
              showSizeChanger
              showQuickJumper
              showTotal={(t) => `共 ${t} 条`}
              onChange={(page, size) => {
                setCurrent(page);
                if (size) setPageSize(size);
              }}
              style={{ padding: '12px 24px', background: '#fff', borderRadius: '10px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
            />
          </div>
        </>
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="暂无智能体"
          style={{ marginTop: 80 }}
        />
      )}

      {/* 新建智能体弹窗 */}
      <CreateForm
        visible={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            await createAgent(values);
            messageApi.success('创建成功');
            setCreateModalVisible(false);
            loadData();
          } catch (error) {
            messageApi.error('创建失败，请重试');
          }
        }}
      />

      {/* 编辑智能体弹窗 */}
      {currentRow && (
        <UpdateForm
          visible={updateModalVisible}
          values={currentRow}
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          onSubmit={async (values) => {
            try {
              await updateAgent(currentRow.id!, values);
              messageApi.success('更新成功');
              setUpdateModalVisible(false);
              setCurrentRow(undefined);
              loadData();
            } catch (error) {
              messageApi.error('更新失败，请重试');
            }
          }}
        />
      )}
    </PageContainer>
  );
};

export default AgentManagement;
