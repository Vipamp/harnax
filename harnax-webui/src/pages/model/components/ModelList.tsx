import React, { useState, useEffect, useMemo } from 'react';
import { useIntl } from '@umijs/max';
import { Card, Row, Col, Tag, Switch, Button, Space, Popconfirm, Empty, Spin, Typography, Tooltip } from 'antd';
import { EditOutlined, DeleteOutlined, GlobalOutlined, ThunderboltOutlined, ToolOutlined, ApiOutlined, EyeOutlined } from '@ant-design/icons';
import { modelPage, toggleModel, deleteModel } from '@/services/ant-design-pro/model';
import { message } from 'antd';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

const { Text, Paragraph } = Typography;

interface ModelListProps {
  providerId: number;
  onEdit: (model: API.ModelItem) => void;
  filters: {
    name: string;
    modelType?: string;
    tags: string[];
    status?: number;
    minPrice?: number;
    maxPrice?: number;
  };
}

// 获取模型类型标签（国际化）
const getModelTypeLabel = (intl: any, type: string) => {
  const typeMap: Record<string, string> = {
    chat: 'Chat Model',
    embedding: 'Embedding Model',
  };
  return intl.formatMessage({ 
    id: `pages.model.${type}`, 
    defaultMessage: typeMap[type] || type 
  });
};

const getModelTypeColor = (type: string) => {
  const colorMap: Record<string, string> = {
    chat: 'blue',
    embedding: 'purple',
  };
  return colorMap[type] || 'default';
};

const getModelTypeBg = (type: string) => {
  const bgMap: Record<string, string> = {
    chat: 'linear-gradient(135deg, #1890ff 0%, #40a9ff 100%)',
    embedding: 'linear-gradient(135deg, #722ed1 0%, #b37feb 100%)',
  };
  return bgMap[type] || '#999';
};

const ModelList: React.FC<ModelListProps> = ({ providerId, onEdit, filters }) => {
  const intl = useIntl();
  const [models, setModels] = useState<API.ModelItem[]>([]);
  const [loading, setLoading] = useState(false);

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);

  const loadModels = async () => {
    setLoading(true);
    try {
      const params: any = { providerId, pageNum: 1, pageSize: 100 };
      if (filters.name) {
        params.name = filters.name;
      }
      if (filters.modelType) {
        params.modelType = filters.modelType;
      }
      if (filters.tags && filters.tags.length > 0) {
        params.tags = filters.tags.join(',');
      }
      if (filters.status !== undefined) {
        params.status = filters.status;
      }
      if (filters.minPrice !== undefined) {
        params.minPrice = filters.minPrice;
      }
      if (filters.maxPrice !== undefined) {
        params.maxPrice = filters.maxPrice;
      }
      const response = await modelPage(params);
      if (response.data) {
        setModels(response.data.records || []);
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.message.loadFailed', defaultMessage: 'Failed to load model list' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (providerId) {
      loadModels();
    }
  }, [providerId, filters]);

  // 切换模型状态
  const handleToggle = async (id: number, newStatus: number) => {
    try {
      await toggleModel(id, newStatus);
      message.success(intl.formatMessage({ id: 'pages.message.toggleSuccess', defaultMessage: 'Status toggled successfully' }));
      // 只更新当前卡片状态，不重新加载整个列表
      setModels((prevModels) =>
        prevModels.map((model) =>
          model.id === id ? { ...model, status: newStatus } : model
        )
      );
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.message.toggleFailed', defaultMessage: 'Failed to toggle status' }));
    }
  };

  // 删除模型
  const handleDelete = async (id: number) => {
    try {
      const response = await deleteModel(id);
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
        loadModels();
      } else {
        const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' });
      message.error(errorMsg);
    }
  };

  // 能力标签渲染
  const renderCapabilityTags = (model: API.ModelItem) => {
    const capabilities = [];
    if (model.supportInternet) {
      capabilities.push(
        <Tooltip key="internet" title={intl.formatMessage({ id: 'pages.model.supportInternet', defaultMessage: 'Support Internet' })}>
          <Tag icon={<GlobalOutlined />} color="cyan">{intl.formatMessage({ id: 'pages.model.tag.internet', defaultMessage: 'Internet' })}</Tag>
        </Tooltip>
      );
    }
    if (model.supportReasoning) {
      capabilities.push(
        <Tooltip key="reasoning" title={intl.formatMessage({ id: 'pages.model.supportReasoning', defaultMessage: 'Support Reasoning' })}>
          <Tag icon={<ThunderboltOutlined />} color="orange">{intl.formatMessage({ id: 'pages.model.tag.reasoning', defaultMessage: 'Reasoning' })}</Tag>
        </Tooltip>
      );
    }
    if (model.supportTool) {
      capabilities.push(
        <Tooltip key="tool" title={intl.formatMessage({ id: 'pages.model.supportTool', defaultMessage: 'Support Tool Call' })}>
          <Tag icon={<ToolOutlined />} color="geekblue">{intl.formatMessage({ id: 'pages.model.tag.tool', defaultMessage: 'Tool' })}</Tag>
        </Tooltip>
      );
    }
    if (model.supportMcp) {
      capabilities.push(
        <Tooltip key="mcp" title={intl.formatMessage({ id: 'pages.model.supportMcp', defaultMessage: 'Support MCP' })}>
          <Tag icon={<ApiOutlined />} color="green">MCP</Tag>
        </Tooltip>
      );
    }
    if (model.supportVision) {
      capabilities.push(
        <Tooltip key="vision" title={intl.formatMessage({ id: 'pages.model.supportVision', defaultMessage: 'Support Vision' })}>
          <Tag icon={<EyeOutlined />} color="purple">{intl.formatMessage({ id: 'pages.model.tag.vision', defaultMessage: 'Vision' })}</Tag>
        </Tooltip>
      );
    }
    return capabilities.length > 0 ? capabilities : <Tag>{intl.formatMessage({ id: 'pages.model.noCapabilities', defaultMessage: 'No special capabilities' })}</Tag>;
  };

  return (
    <Spin spinning={loading}>
      {models.length === 0 ? (
        <Empty description={intl.formatMessage({ id: 'pages.model.noModels', defaultMessage: 'No models' })} />
      ) : (
        <Row gutter={[12, 12]}>
          {models.map((model) => (
            <Col span={8} key={model.id}>
              <Card
                size="small"
                hoverable
                className="model-card"
                styles={{
                  body: { padding: 0 }
                }}
                style={{
                  borderRadius: '16px',
                  border: 'none',
                  boxShadow: '0 4px 20px rgba(0,0,0,0.06)',
                  overflow: 'hidden',
                  position: 'relative',
                  transition: 'all 0.3s ease',
                  transform: 'translateY(0)',
                }}
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
                    background: getModelTypeBg(model.modelType),
                  }}
                />
                <div style={{ padding: '16px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '8px' }}>
                  <div>
                    <Text strong style={{ fontSize: '14px' }}>{model.name}</Text>
                    <br />
                    <Text type="secondary" style={{ fontSize: '12px' }}>{model.modelName}</Text>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    {model.price !== undefined && model.price !== null && (
                      <Tag color="orange">¥{model.price}</Tag>
                    )}
                    <Tag color={model.status === 1 ? 'green' : 'red'}>
                      {model.status === 1 ? intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
                    </Tag>
                    <Tag color={getModelTypeColor(model.modelType)}>
                      {getModelTypeLabel(intl, model.modelType)}
                    </Tag>
                  </div>
                </div>

                {model.description ? (
                  <Paragraph
                    type="secondary"
                    style={{ fontSize: '12px', marginBottom: '8px' }}
                    ellipsis={{ rows: 2 }}
                  >
                    {model.description}
                  </Paragraph>
                ) : (
                  <Paragraph
                    type="secondary"
                    style={{ fontSize: '12px', marginBottom: '8px', fontStyle: 'italic' }}
                    ellipsis={{ rows: 2 }}
                  >
                    暂无描述
                  </Paragraph>
                )}

                <div style={{ marginBottom: '12px' }}>
                  {renderCapabilityTags(model)}
                </div>

                {/* 是否公开、创建时间、创建人和操作按钮 */}
                <div style={{ marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '8px', borderTop: '1px solid var(--vip-border)', paddingTop: '12px' }}>
                  {model.isPublic === 1 && (
                    <Tag color="blue">{intl.formatMessage({ id: 'pages.model.public', defaultMessage: 'Public' })}</Tag>
                  )}
                  <Text type="secondary" style={{ fontSize: '12px' }}>
                    {model.createTime?.replace('T', ' ')}
                  </Text>
                  {model.creator && (
                    <Text type="secondary" style={{ fontSize: '11px' }}>{model.creator}</Text>
                  )}
                  <div style={{ flex: 1 }} />
                  <Space size={8}>
                    {hasOperationPermission(isAdmin, currentUser, model.creator) && (
                      <>
                        <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
                          <Button
                            type="link"
                            size="small"
                            icon={<EditOutlined />}
                            onClick={() => onEdit(model)}
                            style={{ padding: '4px', color: '#1890ff' }}
                          />
                        </Tooltip>
                        <Popconfirm
                          title={intl.formatMessage({ id: 'pages.message.modelDeleteConfirm', defaultMessage: 'Are you sure to delete this model?' })}
                          onConfirm={() => handleDelete(model.id)}
                        >
                          <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
                            <Button
                              type="link"
                              size="small"
                              danger
                              icon={<DeleteOutlined />}
                              style={{ padding: '4px' }}
                            />
                          </Tooltip>
                        </Popconfirm>
                        <Switch
                          checked={model.status === 1}
                          onChange={(checked) => handleToggle(model.id, checked ? 1 : 0)}
                          checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
                          unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
                          style={{
                            backgroundColor: model.status === 1 ? 'var(--vip-primary)' : 'var(--vip-border)',
                          }}
                        />
                      </>
                    )}
                  </Space>
                </div>
                </div>
              </Card>
            </Col>
          ))}
        </Row>
      )}
    </Spin>
  );
};

export default ModelList;
