import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Empty,
  message,
  Modal,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useRef, useState } from 'react';
import { useIntl } from '@umijs/max';
import {
  SettingOutlined,
  PlusOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeInvisibleOutlined,
} from '@ant-design/icons';
import SearchFilterBar, { SearchInput, ActionButton } from '@/components/SearchFilterBar';
import {
  getEnvVariablePage,
  createEnvVariable,
  updateEnvVariable,
  deleteEnvVariable,
  toggleEnvVariable,
} from '@/services/ant-design-pro/envVariable';
import StatusSwitch from '@/components/StatusSwitch';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';

const { Text } = Typography;

const EnvVariableManagement: React.FC = () => {
  const intl = useIntl();

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<any>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');

  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  const filtersRef = useRef({ keyword: '' });
  const [messageApi, contextHolder] = message.useMessage();

  const loadData = async (page = 1, size = pageSize) => {
    const { keyword: kw } = filtersRef.current;
    setLoading(true);
    try {
      const res = await getEnvVariablePage({
        current: page,
        size: size,
        keyword: kw || undefined,
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
    loadData(pageNum, pageSize);
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
      loadData(1);
    }, 500);
  };

  const handleReset = () => {
    setKeyword('');
    filtersRef.current = { keyword: '' };
    setPageNum(1);
    loadData(1);
  };

  const handleToggle = async (id: number, newStatus: number) => {
    try {
      const response = await toggleEnvVariable(id, newStatus);
      if (response.code === 200) {
        messageApi.success(
          newStatus === 1
            ? intl.formatMessage({ id: 'pages.message.enableSuccess', defaultMessage: 'Enabled successfully' })
            : intl.formatMessage({ id: 'pages.message.disableSuccess', defaultMessage: 'Disabled successfully' }),
        );
        loadData();
      } else {
        messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
      }
    } catch (error: any) {
      messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
    }
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.env.deleteConfirm', defaultMessage: 'Are you sure to delete this env variable?' }),
      content: intl.formatMessage({ id: 'pages.message.irreversibleOperation', defaultMessage: 'This operation cannot be undone, please proceed with caution' }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteEnvVariable(id);
          if (response.code === 200) {
            messageApi.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
            loadData();
          } else {
            messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' }));
          }
        } catch (error: any) {
          messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' }));
        }
      },
    });
  };

  const columns: ColumnsType<any> = [
    {
      title: intl.formatMessage({ id: 'pages.env.key', defaultMessage: 'Key' }),
      dataIndex: 'envKey',
      key: 'envKey',
      width: 200,
      ellipsis: true,
      render: (text: string) => (
        <Text strong copyable style={{ fontFamily: 'monospace' }}>
          {text}
        </Text>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.env.value', defaultMessage: 'Value' }),
      dataIndex: 'envValue',
      key: 'envValue',
      ellipsis: true,
      render: (text: string, record: any) => (
        <Space>
          {record.sensitive === 1 && (
            <Tooltip title={intl.formatMessage({ id: 'pages.env.sensitiveValue', defaultMessage: 'Sensitive value - masked' })}>
              <EyeInvisibleOutlined style={{ color: '#999' }} />
            </Tooltip>
          )}
          <Text style={{ fontFamily: 'monospace', color: record.sensitive === 1 ? '#999' : undefined }}>
            {text}
          </Text>
        </Space>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      width: 200,
      render: (text: string) => text || <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.env.sensitive', defaultMessage: 'Sensitive' }),
      dataIndex: 'sensitive',
      key: 'sensitive',
      width: 90,
      align: 'center',
      render: (val: number) =>
        val === 1 ? (
          <Tag color="orange">{intl.formatMessage({ id: 'pages.common.yes', defaultMessage: 'Yes' })}</Tag>
        ) : (
          <Tag>{intl.formatMessage({ id: 'pages.common.no', defaultMessage: 'No' })}</Tag>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.env.status', defaultMessage: 'Status' }),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      align: 'center',
      render: (val: number, record: any) => (
        <StatusSwitch
          status={val ?? 1}
          onChange={(newStatus) => handleToggle(record.id, newStatus)}
        />
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.env.creator', defaultMessage: 'Creator' }),
      dataIndex: 'creator',
      key: 'creator',
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.env.createTime', defaultMessage: 'Created At' }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      render: (text: string) => text?.replace('T', ' ')?.substring(0, 19),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.actions', defaultMessage: 'Actions' }),
      key: 'actions',
      width: 120,
      align: 'center',
      render: (_: any, record: any) => (
        <Space>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setCurrentRow(record);
                setUpdateModalVisible(true);
              }}
            />
          </Tooltip>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => handleDelete(record.id)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <SettingOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.env.title', defaultMessage: 'Env Variables' })}
          </span>
        ),
      }}
    >
      {contextHolder}

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
            {intl.formatMessage({ id: 'pages.env.create', defaultMessage: 'Create Env Variable' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={handleKeywordChange}
          placeholder={intl.formatMessage({ id: 'pages.env.searchPlaceholder', defaultMessage: 'Search key or description' })}
          width="auto"
        />
      </SearchFilterBar>

      <Table
        columns={columns}
        dataSource={data}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pageNum,
          pageSize: pageSize,
          total: total,
          showSizeChanger: true,
          showQuickJumper: true,
          pageSizeOptions: ['10', '20', '50'],
          onChange: (page, size) => {
            setPageNum(page);
            setPageSize(size);
            loadData(page, size);
          },
        }}
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({ id: 'pages.env.noData', defaultMessage: 'No env variables' })}
            />
          ),
        }}
      />

      <CreateForm
        visible={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values) => {
          try {
            const submitData = {
              ...values,
              sensitive: values.sensitive ? 1 : 0,
            };
            const response = await createEnvVariable(submitData);
            if (response.code === 200) {
              messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
              setCreateModalVisible(false);
              loadData();
            } else {
              messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' }));
            }
          } catch (error: any) {
            messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' }));
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
              const response = await updateEnvVariable(currentRow.id!, values);
              if (response.code === 200) {
                messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
                setUpdateModalVisible(false);
                setCurrentRow(undefined);
                loadData();
              } else {
                messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' }));
              }
            } catch (error: any) {
              messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' }));
            }
          }}
        />
      )}
    </PageContainer>
  );
};

export default EnvVariableManagement;
