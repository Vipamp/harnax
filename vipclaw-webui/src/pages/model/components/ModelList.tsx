import React, { useState, useEffect, useMemo } from 'react';
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

const MODEL_TYPE_MAP: Record<string, { label: string; color: string; bg: string }> = {
  chat: { label: '对话模型', color: 'blue', bg: 'linear-gradient(135deg, #1890ff 0%, #40a9ff 100%)' },
  embedding: { label: '嵌入模型', color: 'purple', bg: 'linear-gradient(135deg, #722ed1 0%, #b37feb 100%)' },
};

const ModelList: React.FC<ModelListProps> = ({ providerId, onEdit, filters }) => {
  const [models, setModels] = useState<API.ModelItem[]>([]);
  const [loading, setLoading] = useState(false);

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);

  const loadModels = async () => {
    setLoading(true);
    try {
      const params: any = { providerId, current: 1, pageSize: 100 };
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
      message.error('加载模型列表失败');
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
  const handleToggle = async (id: number) => {
    try {
      await toggleModel(id);
      message.success('状态切换成功');
      // 只更新当前卡片状态，不重新加载整个列表
      setModels((prevModels) =>
        prevModels.map((model) =>
          model.id === id ? { ...model, status: model.status === 1 ? 0 : 1 } : model
        )
      );
    } catch (error) {
      message.error('状态切换失败');
    }
  };

  // 删除模型
  const handleDelete = async (id: number) => {
    try {
      await deleteModel(id);
      message.success('删除成功');
      loadModels();
    } catch (error) {
      message.error('删除失败');
    }
  };

  // 能力标签渲染
  const renderCapabilityTags = (model: API.ModelItem) => {
    const capabilities = [];
    if (model.supportInternet) {
      capabilities.push(
        <Tooltip key="internet" title="支持联网">
          <Tag icon={<GlobalOutlined />} color="cyan">联网</Tag>
        </Tooltip>
      );
    }
    if (model.supportReasoning) {
      capabilities.push(
        <Tooltip key="reasoning" title="支持推理">
          <Tag icon={<ThunderboltOutlined />} color="orange">推理</Tag>
        </Tooltip>
      );
    }
    if (model.supportTool) {
      capabilities.push(
        <Tooltip key="tool" title="支持工具调用">
          <Tag icon={<ToolOutlined />} color="geekblue">工具</Tag>
        </Tooltip>
      );
    }
    if (model.supportMcp) {
      capabilities.push(
        <Tooltip key="mcp" title="支持 MCP">
          <Tag icon={<ApiOutlined />} color="green">MCP</Tag>
        </Tooltip>
      );
    }
    if (model.supportVision) {
      capabilities.push(
        <Tooltip key="vision" title="支持视觉">
          <Tag icon={<EyeOutlined />} color="purple">视觉</Tag>
        </Tooltip>
      );
    }
    return capabilities.length > 0 ? capabilities : <Tag>无特殊能力</Tag>;
  };

  return (
    <Spin spinning={loading}>
      {models.length === 0 ? (
        <Empty description="暂无模型" />
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
                    background: MODEL_TYPE_MAP[model.modelType]?.bg || '#999',
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
                      {model.status === 1 ? '启用' : '禁用'}
                    </Tag>
                    <Tag color={MODEL_TYPE_MAP[model.modelType]?.color || 'default'}>
                      {MODEL_TYPE_MAP[model.modelType]?.label || model.modelType}
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
                <div style={{ marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '8px', borderTop: '1px solid #f0f0f0', paddingTop: '12px' }}>
                  {model.isPublic === 1 && (
                    <Tag color="blue">公开</Tag>
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
                        <Tooltip title="编辑">
                          <Button
                            type="link"
                            size="small"
                            icon={<EditOutlined />}
                            onClick={() => onEdit(model)}
                            style={{ padding: '4px', color: '#1890ff' }}
                          />
                        </Tooltip>
                        <Popconfirm
                          title="确定要删除此模型吗？"
                          onConfirm={() => handleDelete(model.id)}
                        >
                          <Tooltip title="删除">
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
                          onChange={() => handleToggle(model.id)}
                          checkedChildren="启用"
                          unCheckedChildren="禁用"
                          style={{
                            backgroundColor: model.status === 1 ? '#4f6ef7' : '#d9d9d9',
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
