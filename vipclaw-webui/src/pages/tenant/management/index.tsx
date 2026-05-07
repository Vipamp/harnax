import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Button, message, Modal, Space, Tag, Tooltip, Typography, Tabs } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import {
  createTenant,
  deleteTenant,
  getTenantList,
  toggleTenantStatus,
} from '@/services/tenant';
import CreateForm from './components/CreateForm';

import TenantUserList from './components/TenantUserList';
import { PlusOutlined, ShopOutlined } from '@ant-design/icons';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import ManageUsersButton from '@/components/ManageUsersButton';
import StatusSwitch from '@/components/StatusSwitch';
import StyledProTable from '@/components/StyledProTable';

const { Text } = Typography;

const TenantManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);

  const [userModalVisible, setUserModalVisible] = useState<boolean>(false);

  const [currentTenantId, setCurrentTenantId] = useState<number>(0);
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
      messageApi.error(intl.formatMessage({
        id: 'pages.message.loadFailed',
        defaultMessage: 'Failed to load data',
      }));
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



  const columns: ProColumns<any>[] = [
    {
      title: intl.formatMessage({
        id: 'pages.common.id',
        defaultMessage: 'ID',
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
        id: 'pages.tenant.management.admin',
        defaultMessage: '管理员',
      }),
      dataIndex: 'adminName',
      valueType: 'text',
      hideInSearch: true,
      render: (_, record) => {
        return record.adminName || '-';
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
        defaultMessage: 'Action',
      }),
      valueType: 'option',
      width: 250,
      render: (_, record) => (
        <Space size={8}>
          <StatusSwitch 
            status={record.status}
            onChange={() => handleToggleStatus(record.id)}
          />

          <ManageUsersButton 
            onClick={() => {
              setCurrentTenantId(record.id);
              setUserModalVisible(true);
            }}
          />
          <DeleteButton 
            onConfirm={() => handleRemove(record.id)}
          />
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ShopOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
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
      <SearchFilterBar
        onSearch={handleSearch}
        onReset={() => {
          setName('');
          setStatus(undefined);
          setPageNum(1);
          loadData(1);
        }}
        searchText={intl.formatMessage({
          id: 'pages.common.search',
          defaultMessage: 'Search',
        })}
        resetText={intl.formatMessage({
          id: 'pages.common.reset',
          defaultMessage: 'Reset',
        })}
        extra={
          <ActionButton
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
          >
            {intl.formatMessage({
              id: 'pages.tenant.management.createNew',
              defaultMessage: '新建租户',
            })}
          </ActionButton>
        }
      >
        <SearchInput
          value={name}
          onChange={setName}
          onSearch={handleSearch}
          placeholder={intl.formatMessage({
            id: 'pages.tenant.management.search.placeholder',
            defaultMessage: 'Search tenant name',
          })}
          width="auto"
        />
        <FilterSelect
          value={status}
          onChange={setStatus}
          placeholder={intl.formatMessage({
            id: 'pages.tenant.management.status.filter.placeholder',
            defaultMessage: 'Filter by status',
          })}
          width="auto"
          options={[
            { 
              label: intl.formatMessage({ 
                id: 'pages.status.enabled', 
                defaultMessage: 'Enabled' 
              }), 
              value: 1 
            },
            { 
              label: intl.formatMessage({ 
                id: 'pages.status.disabled', 
                defaultMessage: 'Disabled' 
              }), 
              value: 0 
            },
          ]}
        />
      </SearchFilterBar>

      <StyledProTable<any>
        headerTitle={undefined}
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => intl.formatMessage(
            { id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' },
            { total: t }
          ),
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



      {/* 管理用户模态框 */}
      <Modal
        title={intl.formatMessage({
          id: 'pages.tenant.management.manageUsers.title',
          defaultMessage: 'Manage Tenant Users',
        })}
        open={userModalVisible}
        onCancel={() => {
          setUserModalVisible(false);
          setCurrentTenantId(0);
        }}
        footer={null}
        width={900}
        destroyOnClose
      >
        <TenantUserList
          tenantId={currentTenantId}
          visible={userModalVisible}
        />
      </Modal>
    </PageContainer>
  );
};

export default TenantManagement;
