import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useIntl } from '@umijs/max';
import { Table, Tag, Empty, Spin, Typography, Tooltip, Space } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { GlobalOutlined, ThunderboltOutlined, ToolOutlined, ApiOutlined, EyeOutlined } from '@ant-design/icons';
import { modelPage, toggleModel, deleteModel } from '@/services/ant-design-pro/model';
import { message } from 'antd';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import StatusSwitch from '@/components/StatusSwitch';

const { Text } = Typography;

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

const ModelListTable: React.FC<ModelListProps> = ({ providerId, onEdit, filters }) => {
  const intl = useIntl();
  const [models, setModels] = useState<API.ModelItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [columnsWidth, setColumnsWidth] = useState<Record<string, number>>({});

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
      message.error(intl.formatMessage({ id: 'pages.message.loadFailed', defaultMessage: 'Failed to load data' }));
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
  const handleToggle = async (id: number, currentStatus: number) => {
    try {
      const newStatus = currentStatus === 1 ? 0 : 1;
      const response = await toggleModel(id, newStatus);
      
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.message.toggleSuccess', defaultMessage: 'Status toggled successfully' }));
        // 只更新当前卡片状态，不重新加载整个列表
        setModels((prevModels) =>
          prevModels.map((model) =>
            model.id === id ? { ...model, status: newStatus } : model
          )
        );
      } else {
        const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.toggleFailed', defaultMessage: 'Failed to toggle status' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || '状态切换失败';
      message.error(errorMsg);
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
        const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
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
    return capabilities;
  };

  const columns: ColumnsType<API.ModelItem> = [
    {
      title: intl.formatMessage({ id: 'pages.model.model', defaultMessage: 'Model' }),
      key: 'model',
      width: 160,
      render: (_, record) => (
        <div>
          <Text strong style={{ fontSize: '14px' }}>{record.name}</Text>
          <br />
          <Text type="secondary" style={{ fontSize: '12px' }}>{record.modelName}</Text>
        </div>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.model.type', defaultMessage: 'Type' }),
      dataIndex: 'modelType',
      key: 'modelType',
      width: 100,
      render: (text) => (
        <Tag color={getModelTypeColor(text)}>
          {getModelTypeLabel(intl, text)}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.model.price', defaultMessage: 'Price' }),
      dataIndex: 'price',
      key: 'price',
      width: 80,
      sorter: (a, b) => (a.price ?? 0) - (b.price ?? 0),
      render: (text) => (text !== undefined && text !== null ? `¥${text}` : '-'),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' }),
      dataIndex: 'description',
      key: 'description',
      width: 140,
      ellipsis: true,
      render: (text) => (
        <Text type="secondary" style={{ fontSize: '12px' }}>
          {text || intl.formatMessage({ id: 'pages.model.noDescription', defaultMessage: 'No description' })}
        </Text>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.model.capabilities', defaultMessage: 'Capabilities' }),
      key: 'capabilities',
      width: 280,
      render: (_, record) => (
        <Space size={4}>
          {renderCapabilityTags(record)}
        </Space>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.operation', defaultMessage: 'Action' }),
      key: 'action',
      width: 150,
      fixed: 'right',
      render: (_, record) => {
        const canOperate = hasOperationPermission(isAdmin, currentUser, record.creator);
        return canOperate ? (
          <Space size={8}>
            <StatusSwitch
              status={record.status}
              onChange={(newStatus) => handleToggle(record.id, newStatus)}
              disabled={!canOperate}
            />
            <EditButton onClick={() => onEdit(record)} />
            <DeleteButton 
              onConfirm={() => handleDelete(record.id)}
              confirmTitle={intl.formatMessage({ id: 'pages.message.modelDeleteConfirm', defaultMessage: 'Are you sure to delete this model?' })}
            />
          </Space>
        ) : null;
      },
    },
  ];

  return (
    <Spin spinning={loading}>
      {models.length === 0 ? (
        <Empty description={intl.formatMessage({ id: 'pages.model.noModels', defaultMessage: 'No models' })} />
      ) : (
        <Table
          className="styled-pro-table"
          columns={columns}
          dataSource={models}
          rowKey="id"
          pagination={false}
          size="small"
          scroll={{ x: 1200 }}
        />
      )}
    </Spin>
  );
};

export default ModelListTable;
