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
import React, { useEffect, useMemo, useState } from 'react';
import { useIntl } from '@umijs/max';
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
  MessageOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

const { Text, Paragraph } = Typography;

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
}> = ({ item, index, isAdmin, currentUser, onToggleStatus, onEdit, onDelete, hasOperationPermission }) => {
  const intl = useIntl();
  const [isHovered, setIsHovered] = useState(false);
  const mcpCount = item.mcpList?.length || 0;
  const skillCount = item.skillList?.length || 0;
  const sessionCount = item.sessionCount || 0;

  return (
    <Card
      style={{
        borderRadius: '16px',
        border: 'none',
        boxShadow: isHovered 
          ? '0 12px 32px rgba(114, 46, 209, 0.15)' 
          : '0 4px 20px rgba(0,0,0,0.06)',
        overflow: 'hidden',
        position: 'relative',
        transition: 'all 0.4s cubic-bezier(0.4, 0, 0.2, 1)',
        transform: isHovered ? 'translateY(-6px)' : 'translateY(0)',
        animation: `vipSlideUp 0.5s ease-out ${index * 80}ms both`,
      }}
      styles={{ body: { padding: 0 } }}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      {/* 顶部类型标识条 - 带渐变动画 */}
      <div
        style={{
          height: '4px',
          background: `linear-gradient(90deg, var(--vip-primary) 0%, var(--vip-primary-hover) 50%, var(--vip-primary) 100%)`,
          backgroundSize: '200% 100%',
          animation: isHovered ? 'gradientShift 2s linear infinite' : 'none',
        }}
      />

      <div style={{ padding: '20px' }}>
        {/* 头部：图标 + 名称 + 状态 + 标签 */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
          <div
            style={{
              width: 52,
              height: 52,
              borderRadius: '14px',
              background: isHovered 
                ? 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-hover) 100%)' 
                : 'var(--vip-primary-light)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: '24px',
              color: isHovered ? '#fff' : 'var(--vip-primary)',
              flexShrink: 0,
              transition: 'all 0.3s ease',
              boxShadow: isHovered ? '0 8px 20px rgba(114, 46, 209, 0.3)' : 'none',
            }}
          >
            <RobotOutlined />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
              <Text strong style={{ fontSize: '16px', color: 'var(--vip-text-primary)' }}>
                {item.name}
              </Text>
              {/* 状态指示点 */}
              <Badge 
                status={item.status === 1 ? 'success' : 'default'} 
                text={item.status === 1 ? intl.formatMessage({ id: 'pages.agent.status.running', defaultMessage: 'Running' }) : intl.formatMessage({ id: 'pages.agent.status.disabled', defaultMessage: 'Disabled' })}
                style={{ fontSize: '12px' }}
              />
            </div>
            {/* 对话模型标签 */}
            {item.modelName && (
              <div style={{ display: 'flex', gap: 6, marginBottom: 6, flexWrap: 'wrap' }}>
                <Tag color="blue" icon={<ApiOutlined />} style={{ fontSize: '11px' }}>
                  {item.modelName}
                </Tag>
                {item.modelPrice !== undefined && item.modelPrice !== null && (
                  <Tag color="cyan" style={{ fontSize: '11px' }}>
                    ¥{item.modelPrice}/M
                  </Tag>
                )}
              </div>
            )}
          </div>
          {/* 状态开关 */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            {hasOperationPermission(isAdmin, currentUser, item.creator) && (
              <Switch
                checked={item.status === 1}
                onChange={(checked) => onToggleStatus(item.id!, checked ? 1 : 0)}
                checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
                unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
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
          {item.description || intl.formatMessage({ id: 'pages.common.noDescription', defaultMessage: 'No description' })}
        </Paragraph>

        {/* MCP、Skill 和 Session 统计 */}
        <div style={{ 
          display: 'flex', 
          gap: 12, 
          marginBottom: 16,
          padding: '10px 12px',
          background: 'var(--vip-bg-layout)',
          borderRadius: '10px',
        }}>
          {/* MCP 统计 */}
          <Popover
            content={
              <div style={{ maxWidth: 320 }}>
                {mcpCount === 0 ? (
                  <div style={{ padding: '8px 0', textAlign: 'center' }}>
                    <Text type="secondary">{intl.formatMessage({ id: 'pages.agent.mcp.noConfig', defaultMessage: 'No MCP configuration' })}</Text>
                  </div>
                ) : (
                  <List
                    size="small"
                    dataSource={item.mcpList || []}
                    renderItem={(mcp, index) => (
                        <List.Item 
                          style={{ 
                            padding: '8px 12px',
                            background: 'var(--vip-bg-container)',
                            transition: 'all 0.2s ease',
                            cursor: 'pointer',
                          }}
                          onClick={() => {
                            // 在新窗口打开 MCP 详情页
                            window.open(`/context/mcp/detail/${mcp.mcpId}`, '_blank');
                          }}
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
                              <Text strong style={{ fontSize: 13, color: 'var(--vip-text-primary)' }}>
                                {mcp.mcpName || `MCP #${mcp.mcpId}`}
                              </Text>
                            </div>
                            {mcp.mcpDescription && (
                              <div style={{ paddingLeft: 12 }}>
                                <Text style={{ fontSize: 12, color: 'var(--vip-text-secondary)' }}>
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
            }
            title={null}
            trigger="hover"
            placement="bottom"
            overlayStyle={{ maxWidth: 320 }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <div style={{
                width: 28, height: 28,
                borderRadius: '8px',
                background: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-hover) 100%)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <ApiOutlined style={{ fontSize: '14px', color: '#fff' }} />
              </div>
              <div>
                <Text style={{ fontSize: '11px', color: 'var(--vip-text-tertiary)', display: 'block' }}>MCPs</Text>
                <Text strong style={{ fontSize: '14px', color: 'var(--vip-primary)' }}>{mcpCount}</Text>
              </div>
            </div>
          </Popover>

          <div style={{ width: 1, background: 'var(--vip-border)' }} />

          {/* Skill 统计 */}
          <Popover
            content={
              <div style={{ maxWidth: 320 }}>
                {skillCount === 0 ? (
                  <div style={{ padding: '8px 0', textAlign: 'center' }}>
                    <Text type="secondary">{intl.formatMessage({ id: 'pages.agent.skill.noConfig', defaultMessage: 'No skill configuration' })}</Text>
                  </div>
                ) : (
                  <List
                    size="small"
                    dataSource={item.skillList || []}
                    renderItem={(skill, index) => (
                        <List.Item 
                          style={{ 
                            padding: '8px 12px',
                            background: 'var(--vip-bg-container)',
                            transition: 'all 0.2s ease',
                            cursor: 'pointer',
                          }}
                          onClick={() => {
                            // 在新窗口打开 Skill 详情页
                            window.open(`/context/skill/detail/${skill.skillId}`, '_blank');
                          }}
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
                              <Text strong style={{ fontSize: 13, color: 'var(--vip-text-primary)' }}>
                                {skill.skillName || `Skill #${skill.skillId}`}
                              </Text>
                            </div>
                            {skill.repositoryName && (
                              <div style={{ paddingLeft: 12, marginBottom: 2 }}>
                                <Text style={{ fontSize: 11, color: 'var(--vip-text-tertiary)' }}>
                                  仓库: {skill.repositoryName}
                                </Text>
                              </div>
                            )}
                            {skill.skillDescription && (
                              <div style={{ paddingLeft: 12 }}>
                                <Text style={{ fontSize: 12, color: 'var(--vip-text-secondary)' }}>
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
            }
            title={null}
            trigger="hover"
            placement="bottom"
            overlayStyle={{ maxWidth: 320 }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <div style={{
                width: 28, height: 28,
                borderRadius: '8px',
                background: 'linear-gradient(135deg, var(--vip-success) 0%, var(--vip-success-hover) 100%)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <ToolOutlined style={{ fontSize: '14px', color: '#fff' }} />
              </div>
              <div>
                <Text style={{ fontSize: '11px', color: 'var(--vip-text-tertiary)', display: 'block' }}>Skills</Text>
                <Text strong style={{ fontSize: '14px', color: 'var(--vip-success)' }}>{skillCount}</Text>
              </div>
            </div>
          </Popover>

          <div style={{ width: 1, background: 'var(--vip-border)' }} />

          {/* Session 统计 */}
          <Popover
            content={
              <div style={{ maxWidth: 350 }}>
                {!item.sessionList || item.sessionList.length === 0 ? (
                  <div style={{ padding: '8px 0', textAlign: 'center' }}>
                    <Text type="secondary">{intl.formatMessage({ id: 'pages.agent.session.noSession', defaultMessage: 'No sessions' })}</Text>
                  </div>
                ) : (
                  <List
                    size="small"
                    dataSource={item.sessionList}
                    renderItem={(session, index) => (
                        <List.Item
                          style={{ 
                            padding: '8px 12px',
                            background: 'var(--vip-bg-container)',
                            cursor: 'pointer',
                            transition: 'all 0.2s ease',
                          }}
                          onClick={() => {
                            // 在新窗口打开会话页面
                            window.open(`/agent/session?id=${session.id}`, '_blank');
                          }}
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
                              <Text strong style={{ fontSize: 13, color: 'var(--vip-text-primary)' }}>
                                {session.title || `会话 #${session.id}`}
                              </Text>
                            </div>
                            {session.sessionDescription && (
                              <div style={{ paddingLeft: 12 }}>
                                <Text style={{ fontSize: 12, color: 'var(--vip-text-secondary)' }}>
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
            }
            title={null}
            trigger="hover"
            placement="bottom"
            overlayStyle={{ maxWidth: 350 }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <div style={{
                width: 28, height: 28,
                borderRadius: '8px',
                background: 'linear-gradient(135deg, #5c7cff 0%, #94aaff 100%)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <MessageOutlined style={{ fontSize: '14px', color: '#fff' }} />
              </div>
              <div>
                <Text style={{ fontSize: '11px', color: 'var(--vip-text-tertiary)', display: 'block' }}>Sessions</Text>
                <Text strong style={{ fontSize: '14px', color: 'var(--vip-info)' }}>{sessionCount}</Text>
              </div>
            </div>
          </Popover>
        </div>

        {/* 底部信息栏 */}
        <div style={{ 
          display: 'flex', 
          alignItems: 'center', 
          gap: 8, 
          borderTop: '1px solid var(--vip-border)', 
          paddingTop: '12px' 
        }}>
          {item.isPublic === 1 ? (
            <Tag color="blue" style={{ margin: 0, fontSize: '11px' }}>
              {intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            </Tag>
          ) : (
            <Tag style={{ margin: 0, fontSize: '11px', color: 'var(--vip-text-tertiary)' }}>
              {intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
            </Tag>
          )}
          <Text type="secondary" style={{ fontSize: '12px' }}>
            {item.createTime?.replace('T', ' ')}
          </Text>
          {item.creator && (
            <Text type="secondary" style={{ fontSize: '11px', color: 'var(--vip-text-tertiary)' }}>
              {item.creator}
            </Text>
          )}
          <div style={{ flex: 1 }} />
          {hasOperationPermission(isAdmin, currentUser, item.creator) && (
            <Space size={8}>
              <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
                <Button
                  type="text"
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => onEdit(item)}
                  style={{ color: '#4f6ef7' }}
                />
              </Tooltip>
              <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={() => onDelete(item.id!)}
                />
              </Tooltip>
            </Space>
          )}
        </div>
      </div>
    </Card>
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
  const [pageSize, setPageSize] = useState<number>(12);
  const [keyword, setKeyword] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);
  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = pageNum, size = pageSize) => {
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
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [pageNum, pageSize, status]);

  /** 搜索 */
  const handleSearch = () => {
    setPageNum(1);
    loadData(1);
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
            <RobotOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
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
              placeholder={intl.formatMessage({ id: 'pages.placeholder.search', defaultMessage: 'Please enter to search' }) + intl.formatMessage({ id: 'menu.agent.management', defaultMessage: 'Agent Management' })}
              prefix={<SearchOutlined style={{ color: '#8c8c9a' }} />}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={handleSearch}
              style={{ 
                width: 260, 
                borderRadius: '10px',
                height: '40px',
              }}
              allowClear
            />
            <Select
              placeholder={intl.formatMessage({ id: 'pages.placeholder.statusFilter', defaultMessage: 'Status filter' })}
              value={status}
              onChange={(val) => setStatus(val)}
              style={{ width: 140 }}
              allowClear
              options={[
                { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
                { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
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
              {intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
            </Button>
            <Button 
              onClick={() => { setKeyword(''); setStatus(undefined); setPageNum(1); loadData(1); }} 
              style={{ 
                borderRadius: '10px',
                height: '40px',
                padding: '0 20px',
                background: 'var(--vip-bg-container)',
                borderColor: 'var(--vip-border)',
                color: 'var(--vip-text-primary)',
              }}
            >
              {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
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
            {intl.formatMessage({ id: 'pages.agent.create', defaultMessage: 'Create Agent' })}
          </Button>
        </div>
      </Card>

      {/* 卡片列表 */}
      {data.length > 0 ? (
        <>
          <Row gutter={[20, 20]}>
            {data.map((item, index) => (
              <Col xs={24} sm={12} lg={8} xl={6} key={item.id}>
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
                />
              </Col>
            ))}
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
              style={{ padding: '12px 24px', background: 'var(--vip-bg-container)', borderRadius: '10px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
            />
          </div>
        </>
      ) : (
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
            <RobotOutlined style={{ fontSize: 48, color: 'var(--vip-primary)' }} />
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
            style={{ 
              borderRadius: '10px', 
              height: '44px',
              padding: '0 24px',
              fontWeight: 600,
            }}
          >
            {intl.formatMessage({ id: 'pages.agent.create', defaultMessage: 'Create Agent' })}
          </Button>
        </div>
      )}

      {/* 新建智能体弹窗 */}
      <CreateForm
        visible={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            await createAgent(values);
            messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
            setCreateModalVisible(false);
            loadData();
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
              await updateAgent(currentRow.id!, values);
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

export default AgentManagement;
