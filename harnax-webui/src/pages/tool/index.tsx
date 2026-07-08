import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Empty,
  message,
  Modal,
  Tag,
  Typography,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useIntl } from '@umijs/max';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import ResponsiveCardGrid from '@/components/ResponsiveCardGrid';
import CardPagination from '@/components/CardPagination';
import EntityCard, { TagItem } from '@/components/EntityCard';

// Tool 卡片组件
const ToolCard: React.FC<{
  item: any;
  index: number;
  config: { color: string; label: string; icon: React.ReactNode; bg: string };
  isAdmin: boolean;
  currentUser: string;
  onToggleStatus: (id: number, status: number) => void;
  onEdit: (item: any) => void;
  onDelete: (id: number) => void;
  hasOperationPermission: (isAdmin: boolean, currentUser: string, creator?: string) => boolean;
}> = ({ item, index, config, isAdmin, currentUser, onToggleStatus, onEdit, onDelete, hasOperationPermission }) => {
  const intl = useIntl();

  // 构建额外标签（needConfirm / readOnly）
  const extraTags: TagItem[] = [];
  if (item.needConfirm) {
    extraTags.push({
      label: intl.formatMessage({ id: 'pages.tool.needConfirm', defaultMessage: 'Need Confirm' }),
      color: 'orange',
    });
  }
  if (item.readOnly) {
    extraTags.push({
      label: intl.formatMessage({ id: 'pages.tool.readOnly', defaultMessage: 'Read Only' }),
      color: 'blue',
    });
  }

  // 渲染描述区域
  const renderDescription = () => {
    const hasDescription = item.description && item.description.trim() !== '';
    const hasDisplayName = item.displayName && item.displayName.trim() !== '';

    return (
      <div>
        {/* displayName */}
        {hasDisplayName && (
          <div style={{ marginBottom: '6px' }}>
            <span
              style={{
                fontSize: '12px',
                color: 'var(--vip-primary)',
                fontWeight: 500,
              }}
            >
              {item.displayName}
            </span>
          </div>
        )}

        {/* 描述 */}
        {hasDescription && (
          <div style={{ lineHeight: 1.6 }}>
            {item.description}
          </div>
        )}
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
      tags={extraTags}
      description={renderDescription()}
      status={item.status}
      isPublic={item.isPublic}
      creator={item.creator}
      createTime={item.createTime}
      actions={{
        showTest: false,
        showEdit: hasOperationPermission(isAdmin, currentUser, item.creator),
        showDelete: hasOperationPermission(isAdmin, currentUser, item.creator),
        onEdit: () => onEdit(item),
        onDelete: () => onDelete(item.id!),
      }}
      onToggle={(id, status) => onToggleStatus(id, status)}
    />
  );
};
import {
  deleteAgentTool,
  getAgentToolPage,
  toggleAgentToolStatus,
  createAgentTool,
  updateAgentTool,
} from '@/services/ant-design-pro/tool';
import {
  PlusOutlined,
  ToolOutlined,
  CodeOutlined,
  BuildOutlined,
  GlobalOutlined,
} from '@ant-design/icons';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

const { Text } = Typography;

const TOOL_TYPE_CONFIG: Record<
  string,
  { color: string; label: string; icon: React.ReactNode; bg: string }
> = {
  BUILTIN: { color: '#4f6ef7', label: 'BUILTIN', icon: <BuildOutlined />, bg: 'linear-gradient(135deg, #4f6ef7 0%, #6b8aff 100%)' },
  CUSTOM: { color: '#52c41a', label: 'CUSTOM', icon: <CodeOutlined />, bg: 'linear-gradient(135deg, #52c41a 0%, #73d13d 100%)' },
  HTTP: { color: '#faad14', label: 'HTTP', icon: <GlobalOutlined />, bg: 'linear-gradient(135deg, #faad14 0%, #ffc53d 100%)' },
};

const ToolManagement: React.FC = () => {
  const intl = useIntl();
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<any>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(8);
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

  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);

  /** 加载数据（使用 ref 中的最新筛选参数，避免闭包问题） */
  const loadDataWithFilters = async (page = 1, size = pageSize) => {
    const { keyword: kw, status: st, type: tp } = filtersRef.current;

    setLoading(true);
    try {
      const res = await getAgentToolPage({
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

  /** 删除工具 */
  const handleRemove = async (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.message.toolDeleteConfirm', defaultMessage: 'Are you sure to delete this tool?' }),
      content: intl.formatMessage({ id: 'pages.message.irreversibleOperation', defaultMessage: 'This operation cannot be undone, please proceed with caution' }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteAgentTool(id);
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
      const response = await toggleAgentToolStatus(id, newStatus);
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

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ToolOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.tool.title', defaultMessage: 'Tool Management' })}
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
            {intl.formatMessage({ id: 'pages.tool.createTool', defaultMessage: 'Create Tool' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={handleKeywordChange}
          placeholder={intl.formatMessage({ id: 'pages.tool.searchPlaceholder', defaultMessage: 'Search tool name or description' })}
          width="auto"
        />
        <FilterSelect
          value={status}
          onChange={handleStatusChange}
          placeholder={intl.formatMessage({ id: 'pages.tool.statusFilter', defaultMessage: 'Status Filter' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
          ]}
        />
        <FilterSelect
          value={type}
          onChange={handleTypeChange}
          placeholder={intl.formatMessage({ id: 'pages.tool.typeFilter', defaultMessage: 'Type Filter' })}
          width="auto"
          options={[
            { label: 'BUILTIN', value: 'BUILTIN' },
            { label: 'CUSTOM', value: 'CUSTOM' },
            { label: 'HTTP', value: 'HTTP' },
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
            description={intl.formatMessage({ id: 'pages.tool.noTools', defaultMessage: 'No tools' })}
            style={{ marginTop: 80 }}
          />
        }
        renderCard={(item, index) => {
          const config = TOOL_TYPE_CONFIG[item.type] || { color: '#999', label: item.type, icon: <ToolOutlined />, bg: '#999' };
          return (
            <ToolCard
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

      {/* 新建工具弹窗 */}
      <CreateForm
        visible={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            const response = await createAgentTool(values);
            if (response.code === 200) {
              messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
              setCreateModalVisible(false);
              loadDataWithFilters();
            } else {
              const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed, please try again' });
              messageApi.error(errorMsg);
            }
          } catch (error: any) {
            const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed, please try again' });
            messageApi.error(errorMsg);
          }
        }}
      />

      {/* 编辑工具弹窗 */}
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
              const response = await updateAgentTool(currentRow.id!, values);
              if (response.code === 200) {
                messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
                setUpdateModalVisible(false);
                setCurrentRow(undefined);
                loadDataWithFilters();
              } else {
                const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed, please try again' });
                messageApi.error(errorMsg);
              }
            } catch (error: any) {
              const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed, please try again' });
              messageApi.error(errorMsg);
            }
          }}
        />
      )}
    </PageContainer>
  );
};

export default ToolManagement;
