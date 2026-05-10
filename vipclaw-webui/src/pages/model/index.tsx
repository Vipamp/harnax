import React, { useState, useEffect, useRef } from 'react';
import { useIntl } from '@umijs/max';
import { Button, message, Spin, Empty, Input } from 'antd';
import { PlusOutlined, ApiOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import ProviderCard from './components/ProviderCard';
import ProviderForm from './components/ProviderForm';
import ModelListTable from './components/ModelListTable';
import ModelForm from './components/ModelForm';
import { modelProviderPage, toggleModelProvider, deleteModelProvider, connectivityTest } from '@/services/ant-design-pro/modelProvider';
import ResponsiveCardGrid from '@/components/ResponsiveCardGrid';
import CardPagination from '@/components/CardPagination';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import BackButton from '@/components/BackButton';

// 标签选项
const TAG_OPTIONS = [
  { label: '联网', value: 'internet' },
  { label: '推理', value: 'reasoning' },
  { label: '工具', value: 'tool' },
  { label: 'MCP', value: 'mcp' },
  { label: '视觉', value: 'vision' },
];

const ModelManagement: React.FC = () => {
  const intl = useIntl();
  
  // 视图模式: 'providers' | 'models'
  const [viewMode, setViewMode] = useState<'providers' | 'models'>('providers');
  const [selectedProvider, setSelectedProvider] = useState<API.ModelProviderItem | null>(null);
  
  // 服务商相关状态
  const [providers, setProviders] = useState<API.ModelProviderItem[]>([]);
  const [providerLoading, setProviderLoading] = useState(false);
  const [providerFormVisible, setProviderFormVisible] = useState(false);
  const [editingProvider, setEditingProvider] = useState<API.ModelProviderItem | null>(null);
  
  // 模型相关状态
  const [modelFormVisible, setModelFormVisible] = useState(false);
  const [editingModel, setEditingModel] = useState<API.ModelItem | null>(null);
  const [modelListKey, setModelListKey] = useState(0);
  
  // 服务商筛选状态
  const [providerKeyword, setProviderKeyword] = useState<string>('');
  const [providerType, setProviderType] = useState<string | undefined>(undefined);
  const [providerStatus, setProviderStatus] = useState<number | undefined>(undefined);
  
  // 服务商分页状态
  const [providerPageNum, setProviderPageNum] = useState<number>(1);
  const [providerPageSize, setProviderPageSize] = useState<number>(8); // 默认第一个选项：4 * 2 = 8
  const [providerTotal, setProviderTotal] = useState<number>(0);
  const [providerCardsPerRow, setProviderCardsPerRow] = useState<number>(4);
  
  // 防抖定时器引用
  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  
  // 使用 ref 保存最新的筛选参数，避免闭包问题
  const filtersRef = useRef({
    providerKeyword: '',
    providerType: undefined as string | undefined,
    providerStatus: undefined as number | undefined,
  });
  
  // 模型筛选状态
  const [filters, setFilters] = useState({
    name: '',
    modelType: undefined as string | undefined,
    tags: [] as string[],
    status: undefined as number | undefined,
    minPrice: undefined as number | undefined,
    maxPrice: undefined as number | undefined,
  });
  
  // 响应式屏幕尺寸
  const [screenSize, setScreenSize] = useState<'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl'>('lg');

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

  // 重置服务商筛选
  const handleResetProviderFilters = () => {
    setProviderKeyword('');
    setProviderType(undefined);
    setProviderStatus(undefined);
    // 更新 ref 中的值
    filtersRef.current = {
      providerKeyword: '',
      providerType: undefined,
      providerStatus: undefined,
    };
    // 重置后立即加载数据
    setProviderPageNum(1);
    loadProvidersWithFilters(1);
  };

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

  // 服务商关键词变化（带防抖）
  const handleProviderKeywordChange = (value: string) => {
    setProviderKeyword(value);
    // 更新 ref 中的值
    filtersRef.current.providerKeyword = value;
    
    // 清除之前的定时器
    if (searchTimerRef.current) {
      clearTimeout(searchTimerRef.current);
    }
    
    // 设置新的定时器，500ms 后执行搜索
    searchTimerRef.current = setTimeout(() => {
      setProviderPageNum(1);
      loadProvidersWithFilters(1);
    }, 500);
  };

  // 服务商状态筛选改变
  const handleProviderStatusChange = (value: number | undefined) => {
    setProviderStatus(value);
    // 更新 ref 中的值
    filtersRef.current.providerStatus = value;
    // 状态改变时自动触发搜索
    setProviderPageNum(1);
    loadProvidersWithFilters(1);
  };

  // 服务商类型筛选改变
  const handleProviderTypeChange = (value: string | undefined) => {
    setProviderType(value);
    // 更新 ref 中的值
    filtersRef.current.providerType = value;
    // 类型改变时自动触发搜索
    setProviderPageNum(1);
    loadProvidersWithFilters(1);
  };

  // 组件卸载时清理定时器
  useEffect(() => {
    return () => {
      if (searchTimerRef.current) {
        clearTimeout(searchTimerRef.current);
      }
    };
  }, []);
  // 加载服务商列表（使用 ref 中的最新筛选参数，避免闭包问题）
  const loadProvidersWithFilters = async (page = providerPageNum, size = providerPageSize) => {
    const { providerKeyword: keyword, providerType: type, providerStatus: status } = filtersRef.current;
    
    setProviderLoading(true);
    try {
      const response = await modelProviderPage({ 
        pageNum: page, 
        pageSize: size,
        name: keyword || undefined,
        type: type || undefined,
        status: status,
      });
      if (response.data) {
        setProviders(response.data.records || []);
        setProviderTotal(response.data.total || 0);
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    } finally {
      setProviderLoading(false);
    }
  };

  // 初始化加载
  useEffect(() => {
    loadProvidersWithFilters();
  }, []);

  // 选择服务商 - 进入模型列表视图
  const handleSelectProvider = (provider: API.ModelProviderItem) => {
    setSelectedProvider(provider);
    setViewMode('models');
  };

  // 返回服务商列表
  const handleBackToProviders = () => {
    setViewMode('providers');
    setSelectedProvider(null);
    handleResetFilters();
  };

  // 切换服务商状态
  const handleToggleProvider = async (id: number, currentStatus: number) => {
    try {
      const newStatus = currentStatus === 1 ? 0 : 1;
      const response = await toggleModelProvider(id, newStatus);
      
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }));
        setProviders((prevProviders) =>
          prevProviders.map((provider) =>
            provider.id === id
              ? { ...provider, status: newStatus }
              : provider
          )
        );
        if (selectedProvider?.id === id) {
          setSelectedProvider((prev) =>
            prev ? { ...prev, status: prev.status === 1 ? 0 : 1 } : null
          );
        }
      } else {
        const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' });
      message.error(errorMsg);
    }
  };

  // 删除服务商
  const handleDeleteProvider = async (id: number) => {
    try {
      const response = await deleteModelProvider(id);
      
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
        if (selectedProvider?.id === id) {
          handleBackToProviders();
        }
        loadProvidersWithFilters();
      } else {
        const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
      message.error(errorMsg);
    }
  };

  // 连接测试
  const handleConnectivityTest = async (id: number) => {
    try {
      const response = await connectivityTest(id);
      if (response.code === 200 && response.data) {
        message.success(intl.formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }));
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' });
      message.error(errorMsg);
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
    loadProvidersWithFilters();
  };

  // 打开创建模型表单
  const handleCreateModel = () => {
    if (!selectedProvider) {
      message.warning(intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'menu.context.model', defaultMessage: 'Model' }));
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
    setModelListKey((prev) => prev + 1);
  };

  return (
    <PageContainer
      header={{
        title: viewMode === 'models' && selectedProvider ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <BackButton onClick={handleBackToProviders} />
            <div style={{ width: 1, height: 24, background: 'var(--vip-border)' }} />
            <span style={{ fontSize: 18, fontWeight: 600, color: 'var(--vip-text-primary)', margin: 0 }}>
              {selectedProvider.name}
            </span>
          </div>
        ) : (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ApiOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({
              id: 'menu.context.model',
              defaultMessage: 'Model Management',
            })}
          </span>
        ),
        breadcrumb: {
          items: viewMode === 'models' && selectedProvider ? [
            { 
              title: intl.formatMessage({ id: 'menu.context', defaultMessage: 'Context Management' }),
              path: '/context'
            },
            { 
              title: intl.formatMessage({ id: 'menu.context.model', defaultMessage: 'Model Management' }),
              path: '/model'
            },
            { title: selectedProvider.name }
          ] : [
            { 
              title: intl.formatMessage({ id: 'menu.context', defaultMessage: 'Context Management' }),
              path: '/context'
            },
            { 
              title: intl.formatMessage({ id: 'menu.context.model', defaultMessage: 'Model Management' })
            }
          ]
        },
      }}
    >
      <div style={{ padding: '0', minHeight: 'calc(100vh - 140px)' }}>
        {viewMode === 'providers' ? (
          // 服务商列表视图
          <>
            {/* 搜索筛选栏 */}
            <SearchFilterBar
              onSearch={() => {}}
              onReset={handleResetProviderFilters}
              showSearchButton={false}
              searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
              resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
              extra={
                <ActionButton
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={handleCreateProvider}
                >
                  {intl.formatMessage({ id: 'pages.model.createProvider', defaultMessage: 'Create Provider' })}
                </ActionButton>
              }
            >
              <SearchInput
                value={providerKeyword}
                onChange={handleProviderKeywordChange}
                placeholder={intl.formatMessage({ id: 'pages.model.searchPlaceholder', defaultMessage: 'Search provider name or description' })}
                width="auto"
              />
              <FilterSelect
                value={providerType}
                onChange={handleProviderTypeChange}
                placeholder={intl.formatMessage({ id: 'pages.model.typeFilter', defaultMessage: 'Type Filter' })}
                width="auto"
                options={[
                  { label: 'DashScope', value: 'dashscope' },
                  { label: 'OpenAI', value: 'openai' },
                  { label: 'Ollama', value: 'ollama' },
                ]}
              />
              <FilterSelect
                value={providerStatus}
                onChange={handleProviderStatusChange}
                placeholder={intl.formatMessage({ id: 'pages.model.statusFilter', defaultMessage: 'Status Filter' })}
                width="auto"
                options={[
                  { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
                  { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
                ]}
              />
            </SearchFilterBar>

            {/* 卡片列表 */}
            <ResponsiveCardGrid
              data={providers}
              cardHeight={260}
              minAspectRatio={1.4}
              gutter={[16, 16]}
              loading={providerLoading}
              onCardsPerRowChange={setProviderCardsPerRow}
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
                    background: 'rgba(79, 110, 247, 0.08)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginBottom: 24,
                  }}>
                    <ApiOutlined style={{ fontSize: 48, color: 'var(--vip-primary)' }} />
                  </div>
                  <Empty description={intl.formatMessage({ id: 'pages.model.noProviders', defaultMessage: 'No providers' })} />
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={handleCreateProvider}
                    style={{ 
                      borderRadius: '10px', 
                      height: '44px',
                      padding: '0 24px',
                      fontWeight: 600,
                      marginTop: 16,
                    }}
                  >
                    {intl.formatMessage({ id: 'pages.model.createProvider', defaultMessage: 'Create Provider' })}
                  </Button>
                </div>
              }
              renderCard={(item, index) => (
                <ProviderCard
                  provider={item}
                  index={index}
                  onSelect={handleSelectProvider}
                  onEdit={handleEditProvider}
                  onToggle={handleToggleProvider}
                  onDelete={handleDeleteProvider}
                  onConnectivityTest={handleConnectivityTest}
                  screenSize={screenSize}
                />
              )}
            />
            
            {/* 分页组件 */}
            <CardPagination
              current={providerPageNum}
              pageSize={providerPageSize}
              total={providerTotal}
              cardsPerRow={providerCardsPerRow}
              onChange={(page, size) => {
                setProviderPageNum(page);
                setProviderPageSize(size);
                loadProvidersWithFilters(page, size);
              }}
            />
          </>
        ) : (
          // 模型列表视图
          <>
            {/* 搜索筛选栏 */}
            <SearchFilterBar
              onSearch={() => {}}
              onReset={handleResetFilters}
              showSearchButton={false}
              extra={
                <ActionButton
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={handleCreateModel}
                >
                  {intl.formatMessage({ id: 'pages.model.createModel', defaultMessage: 'Create Model' })}
                </ActionButton>
              }
            >
              {/* 关键词搜索 */}
              <SearchInput
                value={filters.name}
                onChange={(e) => setFilters({ ...filters, name: e.target.value })}
                placeholder={intl.formatMessage({ id: 'pages.placeholder.search', defaultMessage: 'Please enter to search' }) + intl.formatMessage({ id: 'pages.common.name', defaultMessage: 'Name' })}
              />
              
              {/* 模型类型 */}
              <FilterSelect
                value={filters.modelType}
                onChange={(value) => setFilters({ ...filters, modelType: value })}
                placeholder={intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'pages.model.type', defaultMessage: 'Model Type' })}
                options={[
                  { label: intl.formatMessage({ id: 'pages.model.chat', defaultMessage: 'Chat Model' }), value: 'chat' },
                  { label: intl.formatMessage({ id: 'pages.model.embedding', defaultMessage: 'Embedding Model' }), value: 'embedding' },
                ]}
              />
              
              {/* 标签筛选 */}
              <FilterSelect
                mode="multiple"
                value={filters.tags}
                onChange={(value) => setFilters({ ...filters, tags: value as string[] })}
                placeholder={intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'pages.model.tags', defaultMessage: 'Tags' })}
                options={TAG_OPTIONS.map(tag => ({
                  label: intl.formatMessage({ id: `pages.model.tag.${tag.value}`, defaultMessage: tag.label }),
                  value: tag.value,
                }))}
              />
              
              {/* 状态 */}
              <FilterSelect
                value={filters.status}
                onChange={(value) => setFilters({ ...filters, status: value })}
                placeholder={intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
                options={[
                  { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
                  { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
                ]}
              />
              
              {/* 价格范围 - 最低价 */}
              <Input
                type="number"
                placeholder={intl.formatMessage({ id: 'pages.model.minPrice', defaultMessage: 'Min Price' })}
                value={filters.minPrice}
                onChange={(e) => setFilters({ ...filters, minPrice: e.target.value ? parseFloat(e.target.value) : undefined })}
                style={{
                  width: 120,
                  borderRadius: '6px',
                  height: '28px',
                  fontSize: '12px',
                }}
                suffix={intl.formatMessage({ id: 'pages.model.priceUnit', defaultMessage: '元' })}
              />
              
              {/* 价格范围 - 最高价 */}
              <Input
                type="number"
                placeholder={intl.formatMessage({ id: 'pages.model.maxPrice', defaultMessage: 'Max Price' })}
                value={filters.maxPrice}
                onChange={(e) => setFilters({ ...filters, maxPrice: e.target.value ? parseFloat(e.target.value) : undefined })}
                style={{
                  width: 120,
                  borderRadius: '6px',
                  height: '28px',
                  fontSize: '12px',
                }}
                suffix={intl.formatMessage({ id: 'pages.model.priceUnit', defaultMessage: '元' })}
              />
            </SearchFilterBar>

            {/* 模型列表 */}
            {selectedProvider ? (
              <ModelListTable
                key={modelListKey}
                providerId={selectedProvider.id}
                onEdit={handleEditModel}
                filters={filters}
              />
            ) : (
              <Empty description={intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' })} />
            )}
          </>
        )}

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
