import React, { useState, useEffect } from 'react';
import { Row, Col, Card, Button, Switch, message, Spin, Empty, Tag, Popconfirm, Input, Select } from 'antd';
import { PlusOutlined, ApiOutlined, CheckCircleOutlined, CloseCircleOutlined, SearchOutlined, ReloadOutlined, GlobalOutlined, ThunderboltOutlined, ToolOutlined, EyeOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import ProviderList from './components/ProviderList';
import ProviderForm from './components/ProviderForm';
import ModelListTable from './components/ModelListTable';
import ModelForm from './components/ModelForm';
import { modelProviderPage, toggleModelProvider, deleteModelProvider, connectivityTest } from '@/services/ant-design-pro/modelProvider';

// 标签选项
const TAG_OPTIONS = [
  { label: '联网', value: 'internet', icon: <GlobalOutlined /> },
  { label: '推理', value: 'reasoning', icon: <ThunderboltOutlined /> },
  { label: '工具', value: 'tool', icon: <ToolOutlined /> },
  { label: 'MCP', value: 'mcp', icon: <ApiOutlined /> },
  { label: '视觉', value: 'vision', icon: <EyeOutlined /> },
];

const ModelManagement: React.FC = () => {
  const [providers, setProviders] = useState<API.ModelProviderItem[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<API.ModelProviderItem | null>(null);
  const [providerLoading, setProviderLoading] = useState(false);
  const [providerFormVisible, setProviderFormVisible] = useState(false);
  const [editingProvider, setEditingProvider] = useState<API.ModelProviderItem | null>(null);
  const [modelFormVisible, setModelFormVisible] = useState(false);
  const [editingModel, setEditingModel] = useState<API.ModelItem | null>(null);
  const [modelListKey, setModelListKey] = useState(0);
  
  // 筛选状态
  const [filters, setFilters] = useState({
    name: '',
    modelType: undefined as string | undefined,
    tags: [] as string[],
    status: undefined as number | undefined,
    minPrice: undefined as number | undefined,
    maxPrice: undefined as number | undefined,
  });
  
  // 重置筛选
  const handleResetFilters = () => {
    setFilters({
      name: '',
      modelType: undefined,
      tags: [],
      status: undefined,
      minPrice: undefined,
      maxPrice: undefined,
    });
  };

  // 加载服务商列表
  const loadProviders = async () => {
    setProviderLoading(true);
    try {
      const response = await modelProviderPage({ pageNum: 1, pageSize: 100 });
      if (response.data) {
        setProviders(response.data.records || []);
        // 默认选中第一个服务商
        if (response.data.records && response.data.records.length > 0 && !selectedProvider) {
          setSelectedProvider(response.data.records[0]);
        }
      }
    } catch (error) {
      message.error('加载服务商列表失败');
    } finally {
      setProviderLoading(false);
    }
  };

  useEffect(() => {
    loadProviders();
  }, []);

  // 切换服务商状态
  const handleToggleProvider = async (id: number) => {
    try {
      await toggleModelProvider(id);
      message.success('状态切换成功');
      // 只更新当前卡片状态，不重新加载整个列表
      setProviders((prevProviders) =>
        prevProviders.map((provider) =>
          provider.id === id
            ? { ...provider, status: provider.status === 1 ? 0 : 1 }
            : provider
        )
      );
      // 如果当前选中的服务商被切换，也更新选中状态
      if (selectedProvider?.id === id) {
        setSelectedProvider((prev) =>
          prev ? { ...prev, status: prev.status === 1 ? 0 : 1 } : null
        );
      }
    } catch (error) {
      message.error('状态切换失败');
    }
  };

  // 删除服务商
  const handleDeleteProvider = async (id: number) => {
    try {
      await deleteModelProvider(id);
      message.success('删除成功');
      if (selectedProvider?.id === id) {
        setSelectedProvider(null);
      }
      loadProviders();
    } catch (error) {
      message.error('删除失败');
    }
  };

  // 连接测试
  const handleConnectivityTest = async (id: number) => {
    try {
      const response = await connectivityTest(id);
      if (response.data) {
        message.success('连接测试成功');
      } else {
        message.error('连接测试失败');
      }
    } catch (error) {
      message.error('连接测试失败');
    }
  };

  // 打开创建服务商表单
  const handleCreateProvider = () => {
    setEditingProvider(null);
    setProviderFormVisible(true);
  };

  // 打开编辑服务商表单
  const handleEditProvider = (provider: API.ModelProviderItem) => {
    setEditingProvider(provider);
    setProviderFormVisible(true);
  };

  // 关闭服务商表单
  const handleProviderFormClose = () => {
    setProviderFormVisible(false);
    setEditingProvider(null);
  };

  // 服务商表单提交成功
  const handleProviderFormSuccess = () => {
    handleProviderFormClose();
    loadProviders();
  };

  // 打开创建模型表单
  const handleCreateModel = () => {
    if (!selectedProvider) {
      message.warning('请先选择一个服务商');
      return;
    }
    setEditingModel(null);
    setModelFormVisible(true);
  };

  // 打开编辑模型表单
  const handleEditModel = (model: API.ModelItem) => {
    setEditingModel(model);
    setModelFormVisible(true);
  };

  // 关闭模型表单
  const handleModelFormClose = () => {
    setModelFormVisible(false);
    setEditingModel(null);
  };

  // 模型表单提交成功
  const handleModelFormSuccess = () => {
    handleModelFormClose();
    // 刷新模型列表
    setModelListKey((prev) => prev + 1);
  };

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <ApiOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
            模型管理
          </span>
        ),
      }}
    >
      <div style={{ padding: '0', minHeight: 'calc(100vh - 140px)' }}>
      <Row gutter={16}>
        {/* 左侧：服务商列表 */}
        <Col span={6}>
          <Card
            title="模型服务商"
            extra={
              <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateProvider}>
                新增
              </Button>
            }
            styles={{
              body: { padding: '12px', maxHeight: 'calc(100vh - 200px)', overflow: 'auto' }
            }}
          >
            <Spin spinning={providerLoading}>
              {providers.length === 0 ? (
                <Empty description="暂无服务商" />
              ) : (
                <ProviderList
                  providers={providers}
                  selectedProvider={selectedProvider}
                  onSelect={setSelectedProvider}
                  onEdit={handleEditProvider}
                  onToggle={handleToggleProvider}
                  onDelete={handleDeleteProvider}
                  onConnectivityTest={handleConnectivityTest}
                />
              )}
            </Spin>
          </Card>
        </Col>

        {/* 右侧：模型列表 */}
        <Col span={18}>
          <Card
            title={
              selectedProvider ? (
                <span>
                  模型列表
                  <Tag color="blue" style={{ marginLeft: 8 }}>
                    {selectedProvider.displayName}
                  </Tag>
                </span>
              ) : (
                '模型列表'
              )
            }
            extra={
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                {/* 关键词搜索 */}
                <Input
                  placeholder="搜索模型名称"
                  prefix={<SearchOutlined />}
                  value={filters.name}
                  onChange={(e) => setFilters({ ...filters, name: e.target.value })}
                  allowClear
                  style={{ width: 180 }}
                />
                {/* 模型类型 */}
                <Select
                  placeholder="模型类型"
                  style={{ width: 120 }}
                  value={filters.modelType}
                  onChange={(value) => setFilters({ ...filters, modelType: value })}
                  allowClear
                  options={[
                    { label: '对话模型', value: 'chat' },
                    { label: '嵌入模型', value: 'embedding' },
                  ]}
                />
                {/* 标签筛选 */}
                <Select
                  placeholder="标签"
                  mode="multiple"
                  style={{ width: 200 }}
                  value={filters.tags}
                  onChange={(value) => setFilters({ ...filters, tags: value })}
                  allowClear
                  maxTagCount="responsive"
                  options={TAG_OPTIONS.map(tag => ({
                    label: (
                      <span>
                        {tag.icon} {tag.label}
                      </span>
                    ),
                    value: tag.value,
                  }))}
                />
                {/* 状态 */}
                <Select
                  placeholder="状态"
                  style={{ width: 100 }}
                  value={filters.status}
                  onChange={(value) => setFilters({ ...filters, status: value })}
                  allowClear
                  options={[
                    { label: '启用', value: 1 },
                    { label: '禁用', value: 0 },
                  ]}
                />
                {/* 价格范围 */}
                <Input
                  placeholder="最低价格"
                  type="number"
                  step="0.0001"
                  value={filters.minPrice}
                  onChange={(e) => setFilters({ ...filters, minPrice: e.target.value ? parseFloat(e.target.value) : undefined })}
                  style={{ width: 100 }}
                />
                <span style={{ color: '#999' }}>-</span>
                <Input
                  placeholder="最高价格"
                  type="number"
                  step="0.0001"
                  value={filters.maxPrice}
                  onChange={(e) => setFilters({ ...filters, maxPrice: e.target.value ? parseFloat(e.target.value) : undefined })}
                  style={{ width: 100 }}
                />
                {/* 重置按钮 */}
                <Button
                  icon={<ReloadOutlined />}
                  onClick={handleResetFilters}
                >
                  重置
                </Button>
                {/* 新增按钮 */}
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={handleCreateModel}
                  disabled={!selectedProvider}
                >
                  新增
                </Button>
              </div>
            }
            styles={{
              body: { padding: '12px' }
            }}
          >
            {selectedProvider ? (
              <ModelListTable
                key={modelListKey}
                providerId={selectedProvider.id}
                onEdit={handleEditModel}
                filters={filters}
              />
            ) : (
              <Empty description="请选择一个服务商" />
            )}
          </Card>
        </Col>
      </Row>

      {/* 服务商表单 */}
      <ProviderForm
        visible={providerFormVisible}
        values={editingProvider}
        onCancel={handleProviderFormClose}
        onSuccess={handleProviderFormSuccess}
      />

      {/* 模型表单 */}
      <ModelForm
        visible={modelFormVisible}
        values={editingModel}
        providerId={selectedProvider?.id}
        onCancel={handleModelFormClose}
        onSuccess={handleModelFormSuccess}
      />
      </div>
    </PageContainer>
  );
};

export default ModelManagement;
