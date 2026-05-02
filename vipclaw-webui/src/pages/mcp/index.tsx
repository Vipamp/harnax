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
import { useIntl } from '@umijs/max';
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
  onTest: (id: number, name: string) => void;
  hasOperationPermission: (isAdmin: boolean, currentUser: string, creator?: string) => boolean;
}> = ({ item, index, config, isAdmin, currentUser, onToggleStatus, onEdit, onDelete, onTest, hasOperationPermission }) => {
  const intl = useIntl();
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
              <Text strong style={{ fontSize: '16px', color: 'var(--vip-text-primary)' }}>
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
          style={{ margin: '0 0 16px', color: 'var(--vip-text-secondary)', fontSize: '13px', minHeight: 40, lineHeight: 1.6 }}
        >
          {item.description || '暂无描述'}
        </Paragraph>

        {/* 命令/地址 */}
        <div
          style={{
            background: 'var(--vip-bg-layout)',
            borderRadius: '10px',
            padding: '12px 14px',
            marginBottom: 16,
            border: '1px solid var(--vip-border)',
            transition: 'all 0.3s ease',
          }}
        >
          <Text
            code
            style={{
              fontSize: '12px',
              color: 'var(--vip-text-secondary)',
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
        <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8, borderTop: '1px solid var(--vip-border)', paddingTop: '12px' }}>
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
              <Tooltip title="连通测试">
                <Button
                  type="text"
                  size="small"
                  icon={<ThunderboltOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    onTest(item.id!, item.name);
                  }}
                  style={{ color: '#fa8c16' }}
                />
              </Tooltip>
              <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
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
              <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
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
  stdio: { color: 'var(--vip-primary)', label: 'STDIO', icon: <CodeOutlined />, bg: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-hover) 100%)' },
  sse: { color: 'var(--vip-success)', label: 'SSE', icon: <ApiOutlined />, bg: 'linear-gradient(135deg, var(--vip-success) 0%, var(--vip-success-hover) 100%)' },
  streamablehttp: { color: 'var(--vip-warning)', label: 'Streamable HTTP', icon: <LinkOutlined />, bg: 'linear-gradient(135deg, var(--vip-warning) 0%, var(--vip-warning-hover) 100%)' },
};

const McpManagement: React.FC = () => {
  const intl = useIntl();
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
  const [testModalVisible, setTestModalVisible] = useState<boolean>(false);
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [testLoading, setTestLoading] = useState<boolean>(false);

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
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
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
      title: intl.formatMessage({ id: 'pages.message.mcpDeleteConfirm', defaultMessage: 'Are you sure to delete this MCP service?' }),
      content: intl.formatMessage({ id: 'pages.message.irreversibleOperation', defaultMessage: 'This operation cannot be undone, please proceed with caution' }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteMcpServer(id);
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
      await toggleMcpServerStatus(id, newStatus);
      messageApi.success(newStatus === 1 ? intl.formatMessage({ id: 'pages.message.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.message.disabled', defaultMessage: 'Disabled' }));
      // 只更新当前卡片状态，不重新加载整个列表
      setData((prevData) =>
        prevData.map((item) =>
          item.id === id ? { ...item, status: newStatus } : item
        )
      );
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    }
  };

  /** 连通性测试 */
  const handleConnectivityTest = async (id: number, name: string) => {
    // 显示测试弹窗
    setTestModalVisible(true);
    setTestResult(null);
    setTestLoading(true);

    // 创建超时Promise（15秒）
    const timeoutPromise = new Promise<never>((_, reject) => {
      setTimeout(() => {
        reject(new Error('TIMEOUT'));
      }, 15000);
    });

    try {
      //  race between API call and timeout
      const res = await Promise.race([
        connectivityTestMcpServer(id),
        timeoutPromise,
      ]);

      // API返回成功
      setTestLoading(false);
      if (res.data === true) {
        setTestResult({
          success: true,
          message: intl.formatMessage({ id: 'pages.message.mcpTestSuccess', defaultMessage: 'MCP connectivity test passed, service connection is normal' }, { name }),
        });
      } else {
        setTestResult({
          success: false,
          message: intl.formatMessage({ id: 'pages.message.mcpTestFailed', defaultMessage: 'MCP connectivity test failed, service unreachable' }, { name }),
        });
      }
    } catch (error: any) {
      setTestLoading(false);
      if (error.message === 'TIMEOUT') {
        // 超时错误
        setTestResult({
          success: false,
          message: intl.formatMessage({ id: 'pages.message.mcpTestTimeout', defaultMessage: 'MCP connectivity test timed out (15s no response), please check if the service is running' }, { name }),
        });
      } else {
        // 其他错误
        const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.connectionFailed', defaultMessage: 'Connection failed' });
        setTestResult({
          success: false,
          message: intl.formatMessage({ id: 'pages.message.mcpTestFailedWithMsg', defaultMessage: 'MCP connectivity test failed: {errorMsg}' }, { name, errorMsg }),
        });
      }
    }
  };

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ApiOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.mcp.title', defaultMessage: 'MCP Service Management' })}
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
          <Button type="primary" onClick={handleSearch} style={{ borderRadius: '8px', background: 'var(--vip-primary)', borderColor: 'var(--vip-primary)' }}>
            查询
          </Button>
          <Button onClick={() => { setKeyword(''); setStatus(undefined); setTypes([]); setPageNum(1); loadData(1); }} style={{ borderRadius: '8px', color: 'var(--vip-text-primary)', borderColor: 'var(--vip-border)', background: 'var(--vip-bg-container)' }}>
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
                    onTest={handleConnectivityTest}
                    hasOperationPermission={hasOperationPermission}
                  />
                </Col>
              );
            })}
          </Row>

          {/* 分页 */}
          <div style={{ marginTop: 32, display: 'flex', justifyContent: 'flex-end' }}>
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
              style={{ padding: '12px 24px', background: 'var(--vip-bg-container)', borderRadius: '10px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
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
            const response = await createMcpServer(values);
            if (response.code === 200) {
              messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
              setCreateModalVisible(false);
              loadData();
            } else {
              // 显示后端返回的错误信息
              const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed, please try again' });
              messageApi.error(errorMsg);
            }
          } catch (error: any) {
            // 显示错误信息给用户
            const errorMsg = error?.message || error?.info?.errorMessage || '创建失败，请重试';
            messageApi.error(errorMsg);
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
              const response = await updateMcpServer(currentRow.id!, values);
              if (response.code === 200) {
                messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
                setUpdateModalVisible(false);
                setCurrentRow(undefined);
                loadData();
              } else {
                // 显示后端返回的错误信息
                const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed, please try again' });
                messageApi.error(errorMsg);
              }
            } catch (error: any) {
              // 显示错误信息给用户
              const errorMsg = error?.message || error?.info?.errorMessage || '更新失败，请重试';
              messageApi.error(errorMsg);
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

      {/* 连通性测试弹窗 */}
      <Modal
        title="连通性测试"
        open={testModalVisible}
        onCancel={() => {
          setTestModalVisible(false);
          setTestResult(null);
          setTestLoading(false);
        }}
        footer={[
          <Button
            key="close"
            type="primary"
            onClick={() => {
              setTestModalVisible(false);
              setTestResult(null);
              setTestLoading(false);
            }}
          >
            关闭
          </Button>,
        ]}
        maskClosable={false}
        closable={!testLoading}
      >
        <div style={{ padding: '20px 0', textAlign: 'center' }}>
          {testLoading ? (
            <div>
              <div style={{ marginBottom: 16 }}>
                <svg
                  className="animate-spin"
                  style={{
                    width: 48,
                    height: 48,
                    animation: 'spin 1s linear infinite',
                  }}
                  viewBox="0 0 24 24"
                  fill="none"
                >
                  <circle
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="#e0e0e0"
                    strokeWidth="4"
                  />
                  <path
                    d="M12 2a10 10 0 0 1 10 10"
                    stroke="#4f6ef7"
                    strokeWidth="4"
                    strokeLinecap="round"
                  />
                </svg>
              </div>
              <Text style={{ fontSize: 16, color: '#666' }}>
                正在测试连接，请稍候...
              </Text>
              <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 13 }}>
                如果超过 15 秒未响应，测试将自动超时
              </Text>
            </div>
          ) : testResult ? (
            <div>
              <div
                style={{
                  width: 64,
                  height: 64,
                  borderRadius: '50%',
                  background: testResult.success ? '#f6ffed' : '#fff2f0',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  margin: '0 auto 16px',
                  border: `2px solid ${testResult.success ? '#52c41a' : '#ff4d4f'}`,
                }}
              >
                {testResult.success ? (
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
                    <path
                      d="M5 13l4 4L19 7"
                      stroke="#52c41a"
                      strokeWidth="3"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                ) : (
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
                    <path
                      d="M18 6L6 18M6 6l12 12"
                      stroke="#ff4d4f"
                      strokeWidth="3"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                )}
              </div>
              <Text
                style={{
                  fontSize: 16,
                  color: testResult.success ? '#52c41a' : '#ff4d4f',
                  fontWeight: 500,
                  display: 'block',
                  marginBottom: 8,
                }}
              >
                {testResult.success ? '测试成功' : '测试失败'}
              </Text>
              <Text type="secondary" style={{ fontSize: 14, display: 'block' }}>
                {testResult.message}
              </Text>
            </div>
          ) : null}
        </div>
      </Modal>

      {/* 添加旋转动画 */}
      <style>{`
        @keyframes spin {
          from {
            transform: rotate(0deg);
          }
          to {
            transform: rotate(360deg);
          }
        }
      `}</style>
    </PageContainer>
  );
};

export default McpManagement;
