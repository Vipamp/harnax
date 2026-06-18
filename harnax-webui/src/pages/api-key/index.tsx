import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useIntl } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { message, Modal, Space, Tag, Typography, Tooltip, Button } from 'antd';
import {
  PlusOutlined,
  KeyOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  getApiKeyPage,
  createApiKey,
  updateApiKey,
  deleteApiKey,
  toggleApiKeyStatus,
  regenerateApiKey,
} from '@/services/ant-design-pro/apiKey';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import RawKeyModal from './components/RawKeyModal';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import StatusSwitch from '@/components/StatusSwitch';
import StyledProTable from '@/components/StyledProTable';

const { Text, Paragraph } = Typography;

const ApiKeyManagement: React.FC = () => {
  const intl = useIntl();
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [rawKeyModalVisible, setRawKeyModalVisible] = useState<boolean>(false);
  const [rawKeyData, setRawKeyData] = useState<{ rawKey: string; name: string }>({ rawKey: '', name: '' });
  const [currentRow, setCurrentRow] = useState<API.ApiKeyItem>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.ApiKeyItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');
  const [enabledFilter, setEnabledFilter] = useState<number | undefined>(undefined);

  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  const filtersRef = useRef({
    keyword: '',
    enabledFilter: undefined as number | undefined,
  });

  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);
  const [messageApi, contextHolder] = message.useMessage();

  const loadDataWithFilters = async (page = 1, size = pageSize) => {
    const { keyword: kw, enabledFilter: ef } = filtersRef.current;
    setLoading(true);
    try {
      const res = await getApiKeyPage({
        pageNum: page,
        pageSize: size,
        keyword: kw || undefined,
        enabled: ef,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: '操作失败' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDataWithFilters(pageNum, pageSize);
  }, [pageNum, pageSize]);

  useEffect(() => {
    return () => {
      if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    };
  }, []);

  const handleKeywordChange = (value: string) => {
    setKeyword(value);
    filtersRef.current.keyword = value;
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    searchTimerRef.current = setTimeout(() => {
      setPageNum(1);
      loadDataWithFilters(1);
    }, 500);
  };

  const handleEnabledChange = (value: number | undefined) => {
    setEnabledFilter(value);
    filtersRef.current.enabledFilter = value;
    setPageNum(1);
    loadDataWithFilters(1);
  };

  const handleReset = () => {
    setKeyword('');
    setEnabledFilter(undefined);
    filtersRef.current = { keyword: '', enabledFilter: undefined };
    setPageNum(1);
    loadDataWithFilters(1);
  };

  const handleDelete = async (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.apiKey.delete.confirm.title', defaultMessage: '确认删除吗？' }),
      content: intl.formatMessage({ id: 'pages.apiKey.delete.confirm.content', defaultMessage: '此操作不可恢复，请谨慎操作。' }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: '确定' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: '取消' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteApiKey(id);
          if (response.code === 200) {
            messageApi.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: '删除成功' }));
            loadDataWithFilters(pageNum, pageSize);
          } else {
            messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: '删除失败' }));
          }
        } catch (error: any) {
          messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: '删除失败' }));
        }
      },
    });
  };

  const handleToggleStatus = async (id: number, newStatus: number) => {
    try {
      const response = await toggleApiKeyStatus(id, newStatus);
      if (response.code === 200) {
        messageApi.success(
          newStatus === 1
            ? intl.formatMessage({ id: 'pages.message.enabled', defaultMessage: '已启用' })
            : intl.formatMessage({ id: 'pages.message.disabled', defaultMessage: '已停用' }),
        );
        setData((prev) => prev.map((item) => (item.id === id ? { ...item, enabled: newStatus } : item)));
      } else {
        messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: '操作失败' }));
      }
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: '操作失败' }));
    }
  };

  const handleRegenerate = (id: number, name: string) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.apiKey.regenerate.confirm.title', defaultMessage: '确认重新生成？' }),
      content: intl.formatMessage({
        id: 'pages.apiKey.regenerate.confirm.content',
        defaultMessage: '重新生成将使当前 Key 失效，所有使用该 Key 的客户端需要更新。',
      }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: '确定' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: '取消' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await regenerateApiKey(id);
          if (response.code === 200 && response.data) {
            setRawKeyData({ rawKey: response.data.rawKey, name });
            setRawKeyModalVisible(true);
            loadDataWithFilters(pageNum, pageSize);
          } else {
            messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: '操作失败' }));
          }
        } catch (error: any) {
          messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: '操作失败' }));
        }
      },
    });
  };

  const isExpired = (expiresAt?: string) => {
    if (!expiresAt) return false;
    return new Date(expiresAt) < new Date();
  };

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.name', defaultMessage: '名称' }),
      dataIndex: 'name',
      key: 'name',
      width: 160,
      align: 'center' as const,
      render: (text: string) => <Text strong>{text}</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.keyPrefix', defaultMessage: 'Key 前缀' }),
      dataIndex: 'keyPrefix',
      key: 'keyPrefix',
      width: 220,
      align: 'center' as const,
      render: (text: string) => (
        <Paragraph
          copyable={{ text }}
          style={{
            margin: 0,
            fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
            fontSize: 12,
          }}
        >
          {text}
        </Paragraph>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.scopes', defaultMessage: '权限范围' }),
      dataIndex: 'scopes',
      key: 'scopes',
      width: 240,
      align: 'center' as const,
      render: (text: string) => (
        <Space size={[4, 4]} wrap>
          {(text || '').split(',').filter(Boolean).map((s: string) => (
            <Tag key={s} color="blue">{s}</Tag>
          ))}
        </Space>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.rateLimit', defaultMessage: '限流' }),
      dataIndex: 'rateLimit',
      key: 'rateLimit',
      width: 100,
      align: 'center' as const,
      render: (val: number) => val ? `${val} /min` : '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.expiresAt', defaultMessage: '过期时间' }),
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 170,
      align: 'center' as const,
      render: (text: string) => {
        if (!text) return intl.formatMessage({ id: 'pages.apiKey.expires.never', defaultMessage: '永不过期' });
        const expired = isExpired(text);
        return (
          <Tag color={expired ? 'red' : 'default'}>
            {text.replace('T', ' ')}
            {expired && ` (${intl.formatMessage({ id: 'pages.apiKey.expires.expired', defaultMessage: '已过期' })})`}
          </Tag>
        );
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.status', defaultMessage: '状态' }),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      align: 'center' as const,
      render: (_: any, record: API.ApiKeyItem) => {
        const canOperate = hasOperationPermission(isAdmin, currentUser, record.creator);
        return (
          <StatusSwitch
            status={record.enabled}
            onChange={(newStatus) => handleToggleStatus(record.id, newStatus)}
            disabled={!canOperate}
          />
        );
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.creator', defaultMessage: '创建者' }),
      dataIndex: 'creator',
      key: 'creator',
      width: 100,
      align: 'center' as const,
    },
    {
      title: intl.formatMessage({ id: 'pages.apiKey.table.createTime', defaultMessage: '创建时间' }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      align: 'center' as const,
      render: (text: string) => text?.replace('T', ' ') || '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.common.operation', defaultMessage: '操作' }),
      key: 'action',
      width: 220,
      align: 'center' as const,
      render: (_: any, record: API.ApiKeyItem) => {
        const canOperate = hasOperationPermission(isAdmin, currentUser, record.creator);
        return canOperate ? (
          <Space size={8}>
            <EditButton onClick={() => {
              setCurrentRow(record);
              setUpdateModalVisible(true);
            }} />
            <Tooltip title={intl.formatMessage({ id: 'pages.apiKey.tooltip.regenerate', defaultMessage: '重新生成' })}>
              <Button
                type="link"
                size="small"
                icon={<ReloadOutlined />}
                onClick={() => handleRegenerate(record.id, record.name)}
                style={{ padding: '0 4px' }}
              />
            </Tooltip>
            <DeleteButton
              onConfirm={() => handleDelete(record.id)}
              confirmTitle={intl.formatMessage({ id: 'pages.apiKey.deleteConfirm', defaultMessage: '确认删除此 API Key？' })}
            />
          </Space>
        ) : null;
      },
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <KeyOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.apiKey.title', defaultMessage: 'API Key 管理' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      <SearchFilterBar
        onSearch={() => {}}
        onReset={handleReset}
        showSearchButton={false}
        searchText={intl.formatMessage({ id: 'pages.apiKey.button.search', defaultMessage: '搜索' })}
        resetText={intl.formatMessage({ id: 'pages.apiKey.button.reset', defaultMessage: '重置' })}
        extra={
          <ActionButton type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
            {intl.formatMessage({ id: 'pages.apiKey.button.create', defaultMessage: '创建 API Key' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={handleKeywordChange}
          placeholder={intl.formatMessage({ id: 'pages.apiKey.search.placeholder', defaultMessage: '搜索名称' })}
          width="auto"
        />
        <FilterSelect
          value={enabledFilter}
          onChange={handleEnabledChange}
          placeholder={intl.formatMessage({ id: 'pages.apiKey.filter.status', defaultMessage: '按状态筛选' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.apiKey.status.enabled', defaultMessage: '启用' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.apiKey.status.disabled', defaultMessage: '停用' }), value: 0 },
          ]}
        />
      </SearchFilterBar>

      <StyledProTable<API.ApiKeyItem>
        headerTitle={undefined}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => intl.formatMessage({ id: 'pages.common.pagination.total', defaultMessage: '共 {total} 条' }, { total: t }),
          onChange: (page, size) => {
            setPageNum(page);
            if (size) setPageSize(size);
          },
        }}
        dataSource={data}
        search={false}
        toolBarRender={false}
        columns={columns}
        scroll={{ x: 1400 }}
      />

      <CreateForm
        visible={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            const response = await createApiKey(values);
            if (response.code === 200 && response.data) {
              setRawKeyData({ rawKey: response.data.rawKey, name: values.name });
              setRawKeyModalVisible(true);
              setCreateModalVisible(false);
              loadDataWithFilters(pageNum, pageSize);
            } else {
              messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: '创建失败' }));
            }
          } catch (error: any) {
            messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: '创建失败' }));
          }
        }}
      />

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
              const response = await updateApiKey(currentRow.id, values);
              if (response.code === 200) {
                messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: '更新成功' }));
                setUpdateModalVisible(false);
                setCurrentRow(undefined);
                loadDataWithFilters(pageNum, pageSize);
              } else {
                messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: '更新失败' }));
              }
            } catch (error: any) {
              messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: '更新失败' }));
            }
          }}
        />
      )}

      <RawKeyModal
        visible={rawKeyModalVisible}
        rawKey={rawKeyData.rawKey}
        keyName={rawKeyData.name}
        onClose={() => {
          setRawKeyModalVisible(false);
          setRawKeyData({ rawKey: '', name: '' });
        }}
      />
    </PageContainer>
  );
};

export default ApiKeyManagement;
