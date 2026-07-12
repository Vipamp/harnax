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
  Popover,
  Row,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  Badge,
  List,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useIntl } from '@umijs/max';
import {
  createAgent,
  updateAgent,
  deleteAgent,
  getAgentPage,
  toggleAgentStatus,
} from '@/services/ant-design-pro/agent';
import {
  PlusOutlined,
  RocketOutlined,
  SearchOutlined,
  ApiOutlined,
  ToolOutlined,
  MessageOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import ResponsiveCardGrid from '@/components/ResponsiveCardGrid';
import CardPagination from '@/components/CardPagination';
import EntityCard, { TagItem } from '@/components/EntityCard';

const { Text } = Typography;

// 智能体卡片组件
const AgentCard: React.FC<{
  item: API.AgentItem;
  index: number;
  isAdmin: boolean;
  currentUser: string;
  onToggleStatus: (id: number, status: number) => void;
  onEdit: (item: API.AgentItem) => void;
  onDelete: (id: number) => void;
  hasOperationPermission: (isAdmin: boolean, currentUser: string, creator?: string) => boolean;
  // 响应式参数
  screenSize?: 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl';
}> = ({ item, index, isAdmin, currentUser, onToggleStatus, onEdit, onDelete, hasOperationPermission, screenSize = 'lg' }) => {
  const intl = useIntl();
  const locale = intl.locale;
  const mcpCount = item.mcpList?.length || 0;
  const toolCount = item.toolList?.length || 0;
  const skillCount = item.skillList?.length || 0;
  const sessionCount = item.sessionCount || 0;

  // 构建 tags：模型名称和价格
  const tags: TagItem[] = [];
  if (item.modelName) {
    tags.push({
      label: item.modelName,
      color: 'blue',
      icon: <ApiOutlined />,
    });
  }
  if (item.modelPrice !== undefined && item.modelPrice !== null) {
    tags.push({
      label: `¥${item.modelPrice}/M`,
      color: 'cyan',
    });
  }

  // 构建 stats：Tools、MCP、Skill、Session 统计（带 Popover 列表）
  const stats = [
    {
      label: 'Tools',
      value: toolCount,
      color: '#faad14',
      popoverContent: (
        <div style={{ maxWidth: 320 }}>
          {toolCount === 0 ? (
            <div style={{ padding: '8px 0', textAlign: 'center' }}>
              <Text type="secondary">{intl.formatMessage({ id: 'pages.agent.tool.noConfig', defaultMessage: 'No tool configuration' })}</Text>
            </div>
          ) : (
            <List
              size="small"
              dataSource={item.toolList || []}
              renderItem={(tool) => {
                const displayName = locale.startsWith('zh')
                  ? (tool.toolDisplayNameZh?.trim() || tool.toolDisplayName || tool.toolName)
                  : (tool.toolDisplayName || tool.toolName);
                return (
                  <List.Item
                    style={{
                      padding: '8px 12px',
                      background: 'var(--vip-bg-container)',
                      transition: 'all 0.2s ease',
                      cursor: 'pointer',
                    }}
                    onClick={() => window.open(`/context/tool`, '_blank')}
                    onMouseEnter={(e) => {
                      e.currentTarget.style.background = 'var(--vip-primary-light)';
                      e.currentTarget.style.paddingLeft = '16px';
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.background = 'var(--vip-bg-container)';
                      e.currentTarget.style.paddingLeft = '12px';
                    }}
                  >
                    <div style={{ width: '100%' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                        <div style={{
                          width: 6,
                          height: 6,
                          borderRadius: '50%',
                          background: '#faad14',
                          flexShrink: 0,
                        }} />
                        <Text strong style={{ fontSize: '12px', color: 'var(--vip-text-primary)' }}>
                          {displayName || `Tool #${tool.toolId}`}
                        </Text>
                      </div>
                      {tool.toolDescription && (
                        <div style={{ paddingLeft: 12 }}>
                          <Text style={{ fontSize: '11px', color: 'var(--vip-text-secondary)' }}>
                            {tool.toolDescription}
                          </Text>
                        </div>
                      )}
                    </div>
                  </List.Item>
                );
              }}
            />
          )}
        </div>
      ),
    },
    {
      label: 'MCPs',
      value: mcpCount,
      color: '#4f6ef7',
      popoverContent: (
        <div style={{ maxWidth: 320 }}>
          {mcpCount === 0 ? (
            <div style={{ padding: '8px 0', textAlign: 'center' }}>
              <Text type="secondary">{intl.formatMessage({ id: 'pages.agent.mcp.noConfig', defaultMessage: 'No MCP configuration' })}</Text>
            </div>
          ) : (
            <List
              size="small"
              dataSource={item.mcpList || []}
              renderItem={(mcp) => (
                <List.Item 
                  style={{ 
                    padding: '8px 12px',
                    background: 'var(--vip-bg-container)',
                    transition: 'all 0.2s ease',
                    cursor: 'pointer',
                  }}
                  onClick={() => window.open(`/context/mcp/detail/${mcp.mcpId}`, '_blank')}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.background = 'var(--vip-primary-light)';
                    e.currentTarget.style.paddingLeft = '16px';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.background = 'var(--vip-bg-container)';
                    e.currentTarget.style.paddingLeft = '12px';
                  }}
                >
                  <div style={{ width: '100%' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                      <div style={{
                        width: 6,
                        height: 6,
                        borderRadius: '50%',
                        background: 'var(--vip-primary)',
                        flexShrink: 0,
                      }} />
                      <Text strong style={{ fontSize: '12px', color: 'var(--vip-text-primary)' }}>
                        {mcp.mcpName || `MCP #${mcp.mcpId}`}
                      </Text>
                    </div>
                    {mcp.mcpDescription && (
                      <div style={{ paddingLeft: 12 }}>
                        <Text style={{ fontSize: '11px', color: 'var(--vip-text-secondary)' }}>
                          {mcp.mcpDescription}
                        </Text>
                      </div>
                    )}
                  </div>
                </List.Item>
              )}
            />
          )}
        </div>
      ),
    },
    {
      label: 'Skills',
      value: skillCount,
      color: '#52c41a',
      popoverContent: (
        <div style={{ maxWidth: 320 }}>
          {skillCount === 0 ? (
            <div style={{ padding: '8px 0', textAlign: 'center' }}>
              <Text type="secondary">{intl.formatMessage({ id: 'pages.agent.skill.noConfig', defaultMessage: 'No skill configuration' })}</Text>
            </div>
          ) : (
            <List
              size="small"
              dataSource={item.skillList || []}
              renderItem={(skill) => (
                <List.Item 
                  style={{ 
                    padding: '8px 12px',
                    background: 'var(--vip-bg-container)',
                    transition: 'all 0.2s ease',
                    cursor: 'pointer',
                  }}
                  onClick={() => window.open(`/context/skill/detail/${skill.skillId}`, '_blank')}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.background = 'var(--vip-primary-light)';
                    e.currentTarget.style.paddingLeft = '16px';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.background = 'var(--vip-bg-container)';
                    e.currentTarget.style.paddingLeft = '12px';
                  }}
                >
                  <div style={{ width: '100%' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                      <div style={{
                        width: 6,
                        height: 6,
                        borderRadius: '50%',
                        background: 'var(--vip-success)',
                        flexShrink: 0,
                      }} />
                      <Text strong style={{ fontSize: '12px', color: 'var(--vip-text-primary)' }}>
                        {skill.skillName || `Skill #${skill.skillId}`}
                      </Text>
                    </div>
                    {skill.repositoryName && (
                      <div style={{ paddingLeft: 12, marginBottom: 2 }}>
                        <Text style={{ fontSize: '11px', color: 'var(--vip-text-tertiary)' }}>
                          {intl.formatMessage({ id: 'pages.agent.skill.repository', defaultMessage: 'Repository' })}: {skill.repositoryName}
                        </Text>
                      </div>
                    )}
                    {skill.skillDescription && (
                      <div style={{ paddingLeft: 12 }}>
                        <Text style={{ fontSize: '11px', color: 'var(--vip-text-secondary)' }}>
                          {skill.skillDescription}
                        </Text>
                      </div>
                    )}
                  </div>
                </List.Item>
              )}
            />
          )}
        </div>
      ),
    },
    {
      label: 'Sessions',
      value: sessionCount,
      color: '#5c7cff',
      popoverContent: (
        <div style={{ maxWidth: 350 }}>
          {!item.sessionList || item.sessionList.length === 0 ? (
            <div style={{ padding: '8px 0', textAlign: 'center' }}>
              <Text type="secondary">{intl.formatMessage({ id: 'pages.agent.session.noSession', defaultMessage: 'No sessions' })}</Text>
            </div>
          ) : (
            <List
              size="small"
              dataSource={item.sessionList}
              renderItem={(session) => (
                <List.Item
                  style={{ 
                    padding: '8px 12px',
                    background: 'var(--vip-bg-container)',
                    cursor: 'pointer',
                    transition: 'all 0.2s ease',
                  }}
                  onClick={() => window.open(`/agent/session?id=${session.id}`, '_blank')}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.background = 'var(--vip-primary-light)';
                    e.currentTarget.style.paddingLeft = '16px';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.background = 'var(--vip-bg-container)';
                    e.currentTarget.style.paddingLeft = '12px';
                  }}
                >
                  <div style={{ width: '100%' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                      <div style={{
                        width: 6,
                        height: 6,
                        borderRadius: '50%',
                        background: 'var(--vip-info)',
                        flexShrink: 0,
                      }} />
                      <Text strong style={{ fontSize: '12px', color: 'var(--vip-text-primary)' }}>
                        {session.title || `会话 #${session.id}`}
                      </Text>
                    </div>
                    {session.sessionDescription && (
                      <div style={{ paddingLeft: 12 }}>
                        <Text style={{ fontSize: '11px', color: 'var(--vip-text-secondary)' }}>
                          {session.sessionDescription}
                        </Text>
                      </div>
                    )}
                  </div>
                </List.Item>
              )}
            />
          )}
        </div>
      ),
      popoverMaxWidth: 350,
    },
  ];

  return (
    <EntityCard
      entity={item}
      index={index}
      icon={<RocketOutlined />}
      name={item.name}
      tagLabel="" // 智能体不显示 type 标签
      tagColor="#4f6ef7"
      tags={tags}
      description={item.description}
      status={item.status ?? 1}
      isPublic={item.isPublic}
      creator={item.creator}
      createTime={item.createTime?.replace('T', ' ')}
      stats={stats}
      actions={{
        showTest: false,
        showEdit: hasOperationPermission(isAdmin, currentUser, item.creator),
        showDelete: hasOperationPermission(isAdmin, currentUser, item.creator),
        onEdit: () => onEdit(item),
        onDelete: () => onDelete(item.id!),
      }}
      onToggle={onToggleStatus}
    />
  );
};

const AgentManagement: React.FC = () => {
  const intl = useIntl();
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.AgentItem>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.AgentItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(8); // 默认第一个选项：4 * 2 = 8
  const [cardsPerRow, setCardsPerRow] = useState<number>(4);
  const [keyword, setKeyword] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);
  
  // 防抖定时器引用
  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  
  // 使用 ref 保存最新的筛选参数，避免闭包问题
  const filtersRef = useRef({
    keyword: '',
    status: undefined as number | undefined,
  });
  const [screenSize, setScreenSize] = useState<'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl'>('lg');

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);
  const [messageApi, contextHolder] = message.useMessage();

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
    const { keyword: kw, status: st } = filtersRef.current;
    
    setLoading(true);
    try {
      const res = await getAgentPage({
        pageNum: page,
        pageSize: size,
        name: kw || undefined,
        status: st,
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
  
  /** 重置筛选 */
  const handleReset = () => {
    setKeyword('');
    setStatus(undefined);
    filtersRef.current = {
      keyword: '',
      status: undefined,
    };
    setPageNum(1);
    loadDataWithFilters(1);
  };

  /** 删除智能体 */
  const handleRemove = async (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.agent.confirm.deleteTitle', defaultMessage: 'Confirm delete agent?' }),
      content: intl.formatMessage({ id: 'pages.agent.confirm.deleteContent', defaultMessage: 'This operation cannot be undone, please proceed with caution.' }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteAgent(id);
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
      await toggleAgentStatus(id, newStatus);
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

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <RocketOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({
              id: 'menu.agent.management',
              defaultMessage: 'Agent Management',
            })}
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
            {intl.formatMessage({ id: 'pages.agent.create', defaultMessage: 'Create Agent' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={handleKeywordChange}
          placeholder={intl.formatMessage({ id: 'pages.placeholder.search', defaultMessage: 'Please enter to search' }) + intl.formatMessage({ id: 'menu.agent.management', defaultMessage: 'Agent Management' })}
          width="auto"
        />
        <FilterSelect
          value={status}
          onChange={handleStatusChange}
          placeholder={intl.formatMessage({ id: 'pages.placeholder.statusFilter', defaultMessage: 'Status filter' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
          ]}
        />
      </SearchFilterBar>

      {/* 卡片列表 */}
      <ResponsiveCardGrid
        data={data}
        cardHeight={260}
        minAspectRatio={1.4}
        gutter={[16, 16]}
        loading={loading}
        onCardsPerRowChange={setCardsPerRow}
        emptyText={
          <div style={{ 
            display: 'flex', 
            flexDirection: 'column', 
            alignItems: 'center', 
            justifyContent: 'center',
            padding: '80px 20px',
            background: 'var(--vip-bg-layout)',
            borderRadius: '20px',
            border: '2px dashed var(--vip-border)',
          }}>
            <div style={{
              width: 120,
              height: 120,
              borderRadius: '50%',
              background: 'rgba(114, 46, 209, 0.08)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              marginBottom: 24,
            }}>
              <RocketOutlined style={{ fontSize: 48, color: 'var(--vip-primary)' }} />
            </div>
            <Text style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)', marginBottom: 8 }}>
              {intl.formatMessage({ id: 'pages.agent.noAgent', defaultMessage: 'No agents' })}
            </Text>
            <Text style={{ fontSize: '14px', color: 'var(--vip-text-tertiary)', marginBottom: 24 }}>
              {intl.formatMessage({ id: 'pages.agent.createFirst', defaultMessage: 'Create your first agent to start your AI journey' })}
            </Text>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setCreateModalVisible(true)}
            >
              {intl.formatMessage({ id: 'pages.agent.create', defaultMessage: 'Create Agent' })}
            </Button>
          </div>
        }
        renderCard={(item, index) => (
          <AgentCard
            item={item}
            index={index}
            isAdmin={isAdmin}
            currentUser={currentUser}
            onToggleStatus={handleToggleStatus}
            onEdit={(item) => {
              setCurrentRow(item);
              setUpdateModalVisible(true);
            }}
            onDelete={handleRemove}
            hasOperationPermission={hasOperationPermission}
            screenSize={screenSize}
          />
        )}
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

      {/* 新建智能体弹窗 */}
      <CreateForm
        visible={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            const response = await createAgent(values);
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
              const response = await updateAgent(currentRow.id!, values);
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
    </PageContainer>
  );
};

export default AgentManagement;
