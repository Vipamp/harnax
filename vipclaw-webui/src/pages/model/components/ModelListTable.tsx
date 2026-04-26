import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Table, Tag, Switch, Button, Space, Popconfirm, Empty, Spin, Typography, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { EditOutlined, DeleteOutlined, GlobalOutlined, ThunderboltOutlined, ToolOutlined, ApiOutlined, EyeOutlined } from '@ant-design/icons';
import { modelPage, toggleModel, deleteModel } from '@/services/ant-design-pro/model';
import { message } from 'antd';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

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

const MODEL_TYPE_MAP: Record<string, { label: string; color: string }> = {
  chat: { label: '对话模型', color: 'blue' },
  embedding: { label: '嵌入模型', color: 'purple' },
};

const ModelListTable: React.FC<ModelListProps> = ({ providerId, onEdit, filters }) => {
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
  const handleToggle = async (id: number, currentStatus: number) => {
    try {
      const newStatus = currentStatus === 1 ? 0 : 1;
      const response = await toggleModel(id, newStatus);
      
      if (response.code === 200) {
        message.success('状态切换成功');
        // 只更新当前卡片状态，不重新加载整个列表
        setModels((prevModels) =>
          prevModels.map((model) =>
            model.id === id ? { ...model, status: newStatus } : model
          )
        );
      } else {
        const errorMsg = response.message || '状态切换失败';
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
        message.success('删除成功');
        loadModels();
      } else {
        const errorMsg = response.message || '删除失败';
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || '删除失败';
      message.error(errorMsg);
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
    return capabilities;
  };

  const columns: ColumnsType<API.ModelItem> = [
    {
      title: '模型',
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
      title: '类型',
      dataIndex: 'modelType',
      key: 'modelType',
      width: 100,
      render: (text) => (
        <Tag color={MODEL_TYPE_MAP[text]?.color || 'default'}>
          {MODEL_TYPE_MAP[text]?.label || text}
        </Tag>
      ),
    },
    {
      title: '价格',
      dataIndex: 'price',
      key: 'price',
      width: 80,
      sorter: (a, b) => (a.price ?? 0) - (b.price ?? 0),
      render: (text) => (text !== undefined && text !== null ? `¥${text}` : '-'),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      width: 140,
      ellipsis: true,
      render: (text) => (
        <Text type="secondary" style={{ fontSize: '12px' }}>
          {text || '暂无描述'}
        </Text>
      ),
    },
    {
      title: '能力',
      key: 'capabilities',
      width: 280,
      render: (_, record) => (
        <Space size={4}>
          {renderCapabilityTags(record)}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (status) => (
        <Tag color={status === 1 ? 'green' : 'red'}>
          {status === 1 ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      fixed: 'right',
      render: (_, record) => {
        const canOperate = hasOperationPermission(isAdmin, currentUser, record.creator);
        return canOperate ? (
          <Space size={8}>
            <Tooltip title="编辑">
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                onClick={() => onEdit(record)}
                style={{ padding: '4px', color: '#1890ff' }}
              />
            </Tooltip>
            <Popconfirm
              title="确定要删除此模型吗？"
              onConfirm={() => handleDelete(record.id)}
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
              checked={record.status === 1}
              onChange={() => handleToggle(record.id, record.status)}
              checkedChildren="启用"
              unCheckedChildren="禁用"
              style={{
                backgroundColor: record.status === 1 ? '#4f6ef7' : '#d9d9d9',
              }}
            />
          </Space>
        ) : (
          <Switch
            disabled
            checked={record.status === 1}
            checkedChildren="启用"
            unCheckedChildren="禁用"
            style={{
              backgroundColor: record.status === 1 ? '#4f6ef7' : '#d9d9d9',
            }}
          />
        );
      },
    },
  ];

  return (
    <Spin spinning={loading}>
      {models.length === 0 ? (
        <Empty description="暂无模型" />
      ) : (
        <Table
          columns={columns}
          dataSource={models}
          rowKey="id"
          pagination={false}
          size="middle"
          scroll={{ x: 1200 }}
        />
      )}
    </Spin>
  );
};

export default ModelListTable;
