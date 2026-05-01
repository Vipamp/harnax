import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Button, Card, Input, message, Modal, Select, Space, Tag, Tooltip, Typography } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import {
  createTenant,
  deleteTenant,
  getTenantList,
  toggleTenantStatus,
  updateTenant,
} from '@/services/tenant';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined, ShopOutlined } from '@ant-design/icons';

const { Text } = Typography;

const TenantManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<any>();
  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [name, setName] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);

  const intl = useIntl();
  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = pageNum, size = pageSize) => {
    setTableLoading(true);
    try {
      const res = await getTenantList({
        pageNum: page,
        pageSize: size,
        name: name || undefined,
        status: status,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      messageApi.error('获取数据失败');
    } finally {
      setTableLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [pageNum, pageSize]);

  /** 搜索 */
  const handleSearch = () => {
    setPageNum(1);
    loadData(1);
  };

  /** 删除租户 */
  const handleRemove = async (tenantId: number) => {
    Modal.confirm({
      title: intl.formatMessage({
        id: 'pages.tenant.management.deleteConfirm',
        defaultMessage: '确认删除该租户吗？',
      }),
      content: intl.formatMessage({
        id: 'pages.tenant.management.deleteContent',
        defaultMessage: '此操作将删除租户及其所有关联数据，请谨慎操作',
      }),
      okText: intl.formatMessage({
        id: 'pages.tenant.management.confirm',
        defaultMessage: '确定',
      }),
      cancelText: intl.formatMessage({
        id: 'pages.tenant.management.cancel',
        defaultMessage: '取消',
      }),
      onOk: async () => {
        const hide = message.loading('正在删除');
        if (!tenantId) return;
        try {
          const response = await deleteTenant(tenantId);
          hide();
          if (response.code === 200) {
            messageApi.success(
              intl.formatMessage({
                id: 'pages.tenant.management.deleteSuccess',
                defaultMessage: '删除成功',
              }),
            );
            loadData();
          } else {
            const errorMsg = response.message || intl.formatMessage({
              id: 'pages.tenant.management.deleteFailed',
              defaultMessage: '删除失败，请重试',
            });
            messageApi.error(errorMsg);
          }
        } catch (error: any) {
          hide();
          const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
            id: 'pages.tenant.management.deleteFailed',
            defaultMessage: '删除失败，请重试',
          });
          messageApi.error(errorMsg);
        }
      },
    });
  };

  /** 切换租户状态 */
  const handleToggleStatus = async (tenantId: number) => {
    const hide = message.loading('正在切换状态');
    try {
      const response = await toggleTenantStatus(tenantId);
      hide();
      if (response.code === 200) {
        messageApi.success(
          intl.formatMessage({
            id: 'pages.tenant.management.toggleSuccess',
            defaultMessage: '状态切换成功',
          }),
        );
        loadData();
      } else {
        const errorMsg = response.message || intl.formatMessage({
          id: 'pages.tenant.management.toggleFailed',
          defaultMessage: '状态切换失败',
        });
        messageApi.error(errorMsg);
      }
    } catch (error: any) {
      hide();
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
        id: 'pages.tenant.management.toggleFailed',
        defaultMessage: '状态切换失败',
      });
      messageApi.error(errorMsg);
    }
  };

  /** 创建租户 */
  const handleCreate = async (fields: any) => {
    const hide = message.loading('正在创建');
    try {
      const response = await createTenant(fields);
      hide();
      if (response.code === 200) {
        messageApi.success(
          intl.formatMessage({
            id: 'pages.tenant.management.createSuccess',
            defaultMessage: '创建成功',
          }),
        );
        setCreateModalVisible(false);
        loadData();
      } else {
        const errorMsg = response.message || intl.formatMessage({
          id: 'pages.tenant.management.createFailed',
          defaultMessage: '创建失败，请重试',
        });
        messageApi.error(errorMsg);
      }
    } catch (error: any) {
      hide();
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
        id: 'pages.tenant.management.createFailed',
        defaultMessage: '创建失败，请重试',
      });
      messageApi.error(errorMsg);
    }
  };

  /** 更新租户 */
  const handleUpdate = async (fields: any) => {
    const hide = message.loading('正在更新');
    try {
      const response = await updateTenant(currentRow.id, fields);
      hide();
      if (response.code === 200) {
        messageApi.success(
          intl.formatMessage({
            id: 'pages.tenant.management.updateSuccess',
            defaultMessage: '更新成功',
          }),
        );
        setUpdateModalVisible(false);
        setCurrentRow(undefined);
        loadData();
      } else {
        const errorMsg = response.message || intl.formatMessage({
          id: 'pages.tenant.management.updateFailed',
          defaultMessage: '更新失败，请重试',
        });
        messageApi.error(errorMsg);
      }
    } catch (error: any) {
      hide();
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
        id: 'pages.tenant.management.updateFailed',
        defaultMessage: '更新失败，请重试',
      });
      messageApi.error(errorMsg);
    }
  };

  const columns: ProColumns<any>[] = [
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.tenantId',
        defaultMessage: '租户 ID',
      }),
      dataIndex: 'id',
      valueType: 'text',
      hideInForm: true,
      hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.tenantName',
        defaultMessage: '租户名称',
      }),
      dataIndex: 'name',
      valueType: 'text',
      hideInTable: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.name',
        defaultMessage: '租户名称',
      }),
      dataIndex: 'name',
      valueType: 'text',
      hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.creator',
        defaultMessage: '创建人',
      }),
      dataIndex: 'creator',
      valueType: 'text',
      hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.status',
        defaultMessage: '状态',
      }),
      dataIndex: 'status',
      filters: true,
      onFilter: true,
      valueEnum: {
        0: { text: '禁用', status: 'Error' },
        1: { text: '启用', status: 'Success' },
      },
      render: (_, record) => {
        return (
          <Tag color={record.status === 1 ? 'success' : 'error'}>
            {record.status === 1
              ? intl.formatMessage({
                  id: 'pages.tenant.management.enabled',
                  defaultMessage: '启用',
                })
              : intl.formatMessage({
                  id: 'pages.tenant.management.disabled',
                  defaultMessage: '禁用',
                })}
          </Tag>
        );
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.createTime',
        defaultMessage: '创建时间',
      }),
      dataIndex: 'createTime',
      valueType: 'dateTime',
      hideInSearch: true,
      hideInForm: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.updateTime',
        defaultMessage: '更新时间',
      }),
      dataIndex: 'updateTime',
      valueType: 'dateTime',
      hideInSearch: true,
      hideInForm: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.operation',
        defaultMessage: '操作',
      }),
      valueType: 'option',
      width: 250,
      render: (_, record) => (
        <Space>
          <Tooltip
            title={intl.formatMessage({
              id: 'pages.tenant.management.toggleStatus',
              defaultMessage: '切换状态',
            })}
          >
            <Button
              type="link"
              size="small"
              onClick={() => handleToggleStatus(record.id)}
            >
              {record.status === 1 ? '禁用' : '启用'}
            </Button>
          </Tooltip>
          <Tooltip
            title={intl.formatMessage({
              id: 'pages.tenant.management.edit',
              defaultMessage: '编辑',
            })}
          >
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setCurrentRow(record);
                setUpdateModalVisible(true);
              }}
            >
              {intl.formatMessage({
                id: 'pages.tenant.management.edit',
                defaultMessage: '编辑',
              })}
            </Button>
          </Tooltip>
          <Tooltip
            title={intl.formatMessage({
              id: 'pages.tenant.management.delete',
              defaultMessage: '删除',
            })}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              onClick={() => handleRemove(record.id)}
            >
              {intl.formatMessage({
                id: 'pages.tenant.management.delete',
                defaultMessage: '删除',
              })}
            </Button>
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <ShopOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
            {intl.formatMessage({
              id: 'pages.tenant.management.title',
              defaultMessage: '租户管理',
            })}
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <Card
        style={{ marginBottom: 24, borderRadius: '12px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
        styles={{ body: { padding: '16px 20px' } }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <Input
            placeholder="搜索租户名称"
            prefix={<SearchOutlined />}
            value={name}
            onChange={(e) => setName(e.target.value)}
            onPressEnter={handleSearch}
            style={{ width: 280, borderRadius: '8px' }}
            allowClear
          />
          <Select
            placeholder="状态筛选"
            value={status}
            onChange={(val) => setStatus(val)}
            style={{ width: 140, borderRadius: '8px' }}
            allowClear
            options={[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]}
          />
          <Button type="primary" onClick={handleSearch} style={{ borderRadius: '8px' }}>
            查询
          </Button>
          <Button onClick={() => { setName(''); setStatus(undefined); setPageNum(1); loadData(1); }} style={{ borderRadius: '8px' }}>
            重置
          </Button>
          <div style={{ flex: 1 }} />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{ borderRadius: '8px', fontWeight: 600 }}
          >
            {intl.formatMessage({
              id: 'pages.tenant.management.createNew',
              defaultMessage: '新建租户',
            })}
          </Button>
        </div>
      </Card>

      <ProTable<any>
        headerTitle={undefined}
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => {
            setPageNum(page);
            if (size) setPageSize(size);
          },
        }}
        dataSource={data}
        search={false}
        toolBarRender={false}
        columns={columns}
      />

      {/* 创建表单 */}
      <CreateForm
        onCancel={() => setCreateModalVisible(false)}
        visible={createModalVisible}
        onSubmit={handleCreate}
      />

      {/* 更新表单 */}
      {currentRow && (
        <UpdateForm
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          visible={updateModalVisible}
          onSubmit={handleUpdate}
          values={currentRow}
        />
      )}
    </PageContainer>
  );
};

export default TenantManagement;
