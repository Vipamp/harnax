import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Card,
  Col,
  Empty,
  Input,
  message,
  Modal,
  Row,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  Pagination,
} from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
// @ts-ignore
import { useModel, history } from '@umijs/max';

// MCP 卡片组件
const McpCard: React.FC<{
  item: API.McpServerItem;
  index: number;
  config: { color: string; label: string; icon: React.ReactNode; bg: string };
  isAdmin: boolean;
  currentUser: string;
  onToggleStatus: (id: number, status: number) => void;
  onEdit: (item: API.McpServerItem) => void;
  onDelete: (id: number) => void;
  hasOperationPermission: (isAdmin: boolean, currentUser: string, creator?: string) => boolean;
}> = ({ item, index, config, isAdmin, currentUser, onToggleStatus, onEdit, onDelete, hasOperationPermission }) => {
  const [isHovered, setIsHovered] = useState(false);
  const endpoint = item.type === 'stdio' ? item.command : item.url;

  // 点击卡片跳转到详情页
  const handleCardClick = () => {
    history.push(`/context/mcp/detail/${item.id}`);
  };

  return (
    <Card
      style={{
        borderRadius: '16px',
        border: 'none',
        boxShadow: isHovered 
          ? `0 12px 32px ${config.color}20` 
          : '0 4px 20px rgba(0,0,0,0.06)',
        overflow: 'hidden',
        position: 'relative',
        transition: 'all 0.4s cubic-bezier(0.4, 0, 0.2, 1)',
        transform: isHovered ? 'translateY(-6px)' : 'translateY(0)',
        animation: `vipSlideUp 0.5s ease-out ${index * 80}ms both`,
        cursor: 'pointer',
      }}
      styles={{ body: { padding: 0 } }}
      onClick={handleCardClick}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      {/* 顶部类型标识条 - 带动画 */}
      <div
        style={{
          height: '4px',
          background: config.bg,
          backgroundSize: '200% 100%',
          animation: isHovered ? 'gradientShift 2s linear infinite' : 'none',
        }}
      />

      <div style={{ padding: '20px' }}>
        {/* 头部：图标 + 名称 + 类型标签 */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
          <div
            style={{
              width: 52,
              height: 52,
              borderRadius: '14px',
              background: isHovered 
                ? config.bg 
                : `${config.color}15`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: '24px',
              color: isHovered ? '#fff' : config.color,
              flexShrink: 0,
              transition: 'all 0.3s ease',
              boxShadow: isHovered ? `0 8px 20px ${config.color}40` : 'none',
            }}
          >
            {config.icon}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
              <Text strong style={{ fontSize: '16px', color: '#1a1a2e' }}>
                {item.name}
              </Text>
            </div>
            <Tag
              style={{
                background: isHovered ? config.bg : `${config.color}12`,
                color: isHovered ? '#fff' : config.color,
                border: 'none',
                borderRadius: '6px',
                fontSize: '11px',
                fontWeight: 600,
                padding: '2px 10px',
                transition: 'all 0.3s ease',
              }}
            >
              {config.label}
            </Tag>
          </div>
          {/* 状态开关 */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }} onClick={(e) => e.stopPropagation()}>
            {hasOperationPermission(isAdmin, currentUser, item.creator) && (
              <Switch
                checked={item.status === 1}
                onChange={(checked) => onToggleStatus(item.id!, checked ? 1 : 0)}
                checkedChildren="启"
                unCheckedChildren="停"
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
          style={{ margin: '0 0 16px', color: '#666', fontSize: '13px', minHeight: 40, lineHeight: 1.6 }}
        >
          {item.description || '暂无描述'}
        </Paragraph>

        {/* 命令/地址 */}
        <div
          style={{
            background: isHovered ? `${config.color}08` : '#f7f8ff',
            borderRadius: '10px',
            padding: '12px 14px',
            marginBottom: 16,
            border: isHovered ? `1px solid ${config.color}20` : '1px solid transparent',
            transition: 'all 0.3s ease',
          }}
        >
          <Text
            code
            style={{
              fontSize: '12px',
              color: '#4a4a6a',
              display: 'block',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              background: 'transparent',
              border: 'none',
              padding: 0,
            }}
          >
            {endpoint || '-'}
          </Text>
        </div>

        {/* 是否公开、创建时间、创建人和操作按钮 */}
        <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8, borderTop: '1px solid #f0f0f0', paddingTop: '12px' }}>
          {item.isPublic === 1 && (
            <Tag color="blue">公开</Tag>
          )}
          <Text type="secondary" style={{ fontSize: '12px' }}>
            {item.createTime?.replace('T', ' ')}
          </Text>
          {item.creator && (
            <Text type="secondary" style={{ fontSize: '11px' }}>{item.creator}</Text>
          )}
          <div style={{ flex: 1 }} />
          {hasOperationPermission(isAdmin, currentUser, item.creator) && (
            <Space size={8}>
              <Tooltip title="编辑">
                <Button
                  type="text"
                  size="small"
                  icon={<EditOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    onEdit(item);
                  }}
                  style={{ color: '#1890ff' }}
                />
              </Tooltip>
              <Tooltip title="删除">
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    onDelete(item.id!);
                  }}
                />
              </Tooltip>
            </Space>
          )}
        </div>
      </div>
    </Card>
  );
};
import {
  deleteMcpServer,
  getMcpServerPage,
  toggleMcpServerStatus,
  createMcpServer,
  updateMcpServer,
  connectivityTestMcpServer,
} from '@/services/ant-design-pro/mcp';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ApiOutlined,
  SearchOutlined,
  CodeOutlined,
  LinkOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

const { Text, Paragraph, Title } = Typography;

const MCP_TYPE_CONFIG: Record<
  string,
  { color: string; label: string; icon: React.ReactNode; bg: string }
> = {
  stdio: { color: '#4f6ef7', label: 'STDIO', icon: <CodeOutlined />, bg: 'linear-gradient(135deg, #4f6ef7 0%, #667eea 100%)' },
  sse: { color: '#52c41a', label: 'SSE', icon: <ApiOutlined />, bg: 'linear-gradient(135deg, #52c41a 0%, #73d13d 100%)' },
  streamablehttp: { color: '#fa8c16', label: 'Streamable HTTP', icon: <LinkOutlined />, bg: 'linear-gradient(135deg, #fa8c16 0%, #ffc53d 100%)' },
};

const McpManagement: React.FC = () => {
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.McpServerItem>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.McpServerItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(12);
  const [keyword, setKeyword] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);
  const [types, setTypes] = useState<string[]>([]);
  const [messageApi, contextHolder] = message.useMessage();

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);

  /** 加载数据 */
  const loadData = async (page = pageNum, size = pageSize) => {
    setLoading(true);
    try {
      const res = await getMcpServerPage({
        current: page,
        size: size,
        keyword: keyword || undefined,
        status: status,
        types: types.length > 0 ? types.join(',') : undefined,
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
  }, [pageNum, pageSize, status, types]);

  /** 搜索 */
  const handleSearch = () => {
    setPageNum(1);
    loadData(1);
  };

  /** 删除 MCP */
  const handleRemove = async (id: number) => {
    Modal.confirm({
      title: '确认删除该 MCP 服务吗？',
      content: '此操作不可恢复，请谨慎操作',
      okText: '确定',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteMcpServer(id);
          if (response.code === 200) {
            messageApi.success('删除成功');
            loadData();
          } else {
            const errorMsg = response.message || '删除失败，请重试';
            messageApi.error(errorMsg);
          }
        } catch (error: any) {
          const errorMsg = error?.message || error?.info?.errorMessage || '删除失败，请重试';
          messageApi.error(errorMsg);
        }
      },
    });
  };

  /** 切换启用状态 */
  const handleToggleStatus = async (id: number, newStatus: number) => {
    try {
      await toggleMcpServerStatus(id, newStatus);
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

  /** 连通性测试 */
  const handleConnectivityTest = async (id: number, name: string) => {
    const hide = message.loading(`正在测试 ${name} 的连通性...`);
    try {
      const res = await connectivityTestMcpServer(id);
      hide();
      if (res.data === true) {
        messageApi.success(`${name} 连通性测试通过`);
      } else {
        messageApi.error(`${name} 连通性测试失败`);
      }
    } catch (error) {
      hide();
      messageApi.error(`${name} 连通性测试失败`);
    }
  };

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <ApiOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
            MCP 服务管理
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
            placeholder="搜索 MCP 名称或描述"
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
          <Select
            mode="multiple"
            placeholder="类型筛选"
            value={types}
            onChange={(val) => {
              setTypes(val);
              setPageNum(1);
            }}
            style={{ width: 200, borderRadius: '8px' }}
            allowClear
            options={[
              { label: 'STDIO', value: 'stdio' },
              { label: 'SSE', value: 'sse' },
              { label: 'Streamable HTTP', value: 'streamablehttp' },
            ]}
          />
          <Button type="primary" onClick={handleSearch} style={{ borderRadius: '8px' }}>
            查询
          </Button>
          <Button onClick={() => { setKeyword(''); setStatus(undefined); setTypes([]); setPageNum(1); loadData(1); }} style={{ borderRadius: '8px' }}>
            重置
          </Button>
          <div style={{ flex: 1 }} />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{ borderRadius: '8px', fontWeight: 600 }}
          >
            新建 MCP
          </Button>
        </div>
      </Card>

      {/* 卡片列表 */}
      {data.length > 0 ? (
        <>
          <Row gutter={[20, 20]}>
            {data.map((item, index) => {
              const config = MCP_TYPE_CONFIG[item.type] || { color: '#999', label: item.type, icon: <ApiOutlined />, bg: '#999' };
              return (
                <Col xs={24} sm={12} lg={8} xl={6} key={item.id}>
                  <McpCard
                    item={item}
                    index={index}
                    config={config}
                    isAdmin={isAdmin}
                    currentUser={currentUser}
                    onToggleStatus={handleToggleStatus}
                    onEdit={(item) => {
                      setCurrentRow(item);
                      setUpdateModalVisible(true);
                    }}
                    onDelete={handleRemove}
                    hasOperationPermission={hasOperationPermission}
                  />
                </Col>
              );
            })}
          </Row>

          {/* 分页 */}
          <div style={{ marginTop: 32, display: 'flex', justifyContent: 'center' }}>
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
              style={{ padding: '12px 24px', background: '#fff', borderRadius: '10px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
            />
          </div>
        </>
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="暂无 MCP 服务"
          style={{ marginTop: 80 }}
        />
      )}

      {/* 新建 MCP 弹窗 */}
      <CreateForm
        visible={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            await createMcpServer(values);
            messageApi.success('创建成功');
            setCreateModalVisible(false);
            loadData();
          } catch (error) {
            messageApi.error('创建失败，请重试');
          }
        }}
        onConnectivityTest={async () => {
          messageApi.info('请先保存 MCP 服务后再进行连通测试');
          return false;
        }}
      />

      {/* 编辑 MCP 弹窗 */}
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
              await updateMcpServer(currentRow.id!, values);
              messageApi.success('更新成功');
              setUpdateModalVisible(false);
              setCurrentRow(undefined);
              loadData();
            } catch (error) {
              messageApi.error('更新失败，请重试');
            }
          }}
          onConnectivityTest={async (id) => {
            const hide = message.loading('正在测试连通性...');
            try {
              const res = await connectivityTestMcpServer(id);
              hide();
              return res.data === true;
            } catch (error) {
              hide();
              return false;
            }
          }}
        />
      )}
    </PageContainer>
  );
};

export default McpManagement;
