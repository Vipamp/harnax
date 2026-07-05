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
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useIntl } from '@umijs/max';
// @ts-ignore
import { useModel, history } from '@umijs/max';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import TestButton from '@/components/TestButton';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import ResponsiveCardGrid from '@/components/ResponsiveCardGrid';
import CardPagination from '@/components/CardPagination';
import EntityCard from '@/components/EntityCard';

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
  const endpoint = item.type === 'stdio' ? item.command : item.url;

  // 点击卡片跳转到详情页
  const handleCardClick = () => {
    history.push(`/context/mcp/detail/${item.id}`);
  };

  // 渲染描述区域（只显示 Endpoint）
  const renderDescription = () => {
    const hasDescription = item.description && item.description.trim() !== '';

    return (
      <div>
        {/* 自定义描述（如果有） */}
        {hasDescription && (
          <div style={{ marginBottom: '12px', lineHeight: 1.6 }}>
            {item.description}
          </div>
        )}

        {/* Endpoint 信息 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Text type="secondary" style={{ fontSize: '12px', fontWeight: 500, flexShrink: 0, lineHeight: 1.5 }}>
            {intl.formatMessage({ id: 'pages.mcp.endpoint', defaultMessage: 'Endpoint' })}:
          </Text>
          <span
            style={{
              fontSize: '11px',
              color: 'var(--vip-text-secondary)',
              background: 'var(--vip-bg-layout)',
              border: '1px solid var(--vip-border)',
              borderRadius: 'var(--vip-radius-sm)',
              padding: '4px 8px',
              wordBreak: 'break-all',
              lineHeight: 1.5,
              fontFamily: 'monospace',
            }}
          >
            {endpoint || '-'}
          </span>
        </div>
      </div>
    );
  };

  return (
    <EntityCard
      entity={item}
      index={index}
      icon={config.icon}
      name={item.name}
      tagLabel={config.label}
      tagColor={config.color}
      tagBgHover={config.color}
      description={renderDescription()}
      status={item.status}
      isPublic={item.isPublic}
      creator={item.creator}
      createTime={item.createTime}
      actions={{
        showTest: true,
        showEdit: hasOperationPermission(isAdmin, currentUser, item.creator),
        showDelete: hasOperationPermission(isAdmin, currentUser, item.creator),
        onTest: () => onTest(item.id!, item.name),
        onEdit: () => onEdit(item),
        onDelete: () => onDelete(item.id!),
      }}
      onToggle={(id, status) => onToggleStatus(id, status)}
      onClick={handleCardClick}
    />
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
  stdio: { color: '#4f6ef7', label: 'STDIO', icon: <CodeOutlined />, bg: 'linear-gradient(135deg, #4f6ef7 0%, #6b8aff 100%)' },
  sse: { color: '#52c41a', label: 'SSE', icon: <ApiOutlined />, bg: 'linear-gradient(135deg, #52c41a 0%, #73d13d 100%)' },
  streamablehttp: { color: '#faad14', label: 'Streamable HTTP', icon: <LinkOutlined />, bg: 'linear-gradient(135deg, #faad14 0%, #ffc53d 100%)' },
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
  const [pageSize, setPageSize] = useState<number>(8); // 默认第一个选项：4 * 2 = 8
  const [keyword, setKeyword] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);
  const [type, setType] = useState<string | undefined>(undefined);
  const [cardsPerRow, setCardsPerRow] = useState<number>(4);
  
  // 防抖定时器引用
  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  
  // 使用 ref 保存最新的筛选参数，避免闭包问题
  const filtersRef = useRef({
    keyword: '',
    status: undefined as number | undefined,
    type: undefined as string | undefined,
  });
  const [messageApi, contextHolder] = message.useMessage();
  const [testModalVisible, setTestModalVisible] = useState<boolean>(false);
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [testLoading, setTestLoading] = useState<boolean>(false);
  const [screenSize, setScreenSize] = useState<'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl'>('lg');

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);

  // 响应式屏幕尺寸检测
  useEffect(() => {
    const updateScreenSize = () => {
      const width = window.innerWidth;
      if (width < 576) {
        setScreenSize('xs');
      } else if (width < 768) {
        setScreenSize('sm');
      } else if (width < 992) {
        setScreenSize('md');
      } else if (width < 1200) {
        setScreenSize('lg');
      } else if (width < 1600) {
        setScreenSize('xl');
      } else {
        setScreenSize('xxl');
      }
    };

    updateScreenSize();
    window.addEventListener('resize', updateScreenSize);
    return () => window.removeEventListener('resize', updateScreenSize);
  }, []);

  /** 加载数据（使用 ref 中的最新筛选参数，避免闭包问题） */
  const loadDataWithFilters = async (page = 1, size = pageSize) => {
    const { keyword: kw, status: st, type: tp } = filtersRef.current;
    
    setLoading(true);
    try {
      const res = await getMcpServerPage({
        current: page,
        size: size,
        keyword: kw || undefined,
        status: st,
        type: tp,
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
    loadDataWithFilters(pageNum, pageSize);
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
  
  /** 状态筛选改变 */
  const handleStatusChange = (value: number | undefined) => {
    setStatus(value);
    filtersRef.current.status = value;
    setPageNum(1);
    loadDataWithFilters(1);
  };
  
  /** 类型筛选改变 */
  const handleTypeChange = (value: string | undefined) => {
    setType(value);
    filtersRef.current.type = value;
    setPageNum(1);
    loadDataWithFilters(1);
  };
  
  /** 重置筛选 */
  const handleReset = () => {
    setKeyword('');
    setStatus(undefined);
    setType(undefined);
    filtersRef.current = {
      keyword: '',
      status: undefined,
      type: undefined,
    };
    setPageNum(1);
    loadDataWithFilters(1);
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
            loadDataWithFilters();
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
      const response = await toggleMcpServerStatus(id, newStatus);
      if (response.code === 200) {
        messageApi.success(newStatus === 1 ? intl.formatMessage({ id: 'pages.message.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.message.disabled', defaultMessage: 'Disabled' }));
        // 只更新当前卡片状态，不重新加载整个列表
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
      <SearchFilterBar
        onSearch={() => {}}
        onReset={handleReset}
        showSearchButton={false}
        searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
        resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
        extra={
          <ActionButton
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
          >
            {intl.formatMessage({ id: 'pages.mcp.createMcp', defaultMessage: 'Create MCP' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={handleKeywordChange}
          placeholder={intl.formatMessage({ id: 'pages.mcp.searchPlaceholder', defaultMessage: 'Search MCP name or description' })}
          width="auto"
        />
        <FilterSelect
          value={status}
          onChange={handleStatusChange}
          placeholder={intl.formatMessage({ id: 'pages.mcp.statusFilter', defaultMessage: 'Status Filter' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
          ]}
        />
        <FilterSelect
          value={type}
          onChange={handleTypeChange}
          placeholder={intl.formatMessage({ id: 'pages.mcp.typeFilter', defaultMessage: 'Type Filter' })}
          width="auto"
          options={[
            { label: 'STDIO', value: 'stdio' },
            { label: 'SSE', value: 'sse' },
            { label: 'Streamable HTTP', value: 'streamablehttp' },
          ]}
        />
      </SearchFilterBar>

      {/* 卡片列表 */}
      <ResponsiveCardGrid
        data={data}
        cardHeight={260}
        minAspectRatio={1.4}
        gutter={[20, 20]}
        loading={loading}
        onCardsPerRowChange={setCardsPerRow}
        emptyText={
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={intl.formatMessage({ id: 'pages.mcp.noServices', defaultMessage: 'No MCP services' })}
            style={{ marginTop: 80 }}
          />
        }
        renderCard={(item, index) => {
          const config = MCP_TYPE_CONFIG[item.type] || { color: '#999', label: item.type, icon: <ApiOutlined />, bg: '#999' };
          return (
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
          );
        }}
      />
      
      {/* 分页组件 */}
      <CardPagination
        current={pageNum}
        pageSize={pageSize}
        total={total}
        cardsPerRow={cardsPerRow}
        onChange={(page, size) => {
          setPageNum(page);
          setPageSize(size);
          loadDataWithFilters(page, size);
        }}
      />

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
              loadDataWithFilters();
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
          messageApi.info(intl.formatMessage({ id: 'pages.mcp.testAfterSave', defaultMessage: 'Please save the MCP service before testing connectivity' }));
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
                loadDataWithFilters();
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
            const hide = message.loading(intl.formatMessage({ id: 'pages.mcp.testing', defaultMessage: 'Testing connectivity...' }));
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
        title={intl.formatMessage({ id: 'pages.mcp.connectivityTest', defaultMessage: 'Connectivity Test' })}
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
                {intl.formatMessage({ id: 'pages.mcp.testingConnection', defaultMessage: 'Testing connection, please wait...' })}
              </Text>
              <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 13 }}>
                {intl.formatMessage({ id: 'pages.mcp.timeoutHint', defaultMessage: 'If no response within 15 seconds, the test will timeout' })}
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
                {testResult.success ? intl.formatMessage({ id: 'pages.mcp.testSuccess', defaultMessage: 'Test Successful' }) : intl.formatMessage({ id: 'pages.mcp.testFailed', defaultMessage: 'Test Failed' })}
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
