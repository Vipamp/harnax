import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useRequest } from '@umijs/max';
import { Button, message, Modal, Space, Tag, Tooltip, Typography } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { deleteUser, getUserPage, updateUser as updateUserApi, createUser, toggleUserStatus } from '@/services/ant-design-pro/user';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import UserTenantList from './components/UserTenantList';
import { PlusOutlined, UserOutlined, TeamOutlined } from '@ant-design/icons';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import StatusSwitch from '@/components/StatusSwitch';
import StyledProTable from '@/components/StyledProTable';

const { Text } = Typography;

const UserManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.UserItem>();
  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.UserItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);
  const [tenantId, setTenantId] = useState<number | undefined>(undefined);
  const [tenantModalVisible, setTenantModalVisible] = useState<boolean>(false);
  const [selectedUserId, setSelectedUserId] = useState<number>(0);
  const [currentUserTenants, setCurrentUserTenants] = useState<number>(0);

  const intl = useIntl();
  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = pageNum, size = pageSize) => {
    setTableLoading(true);
    try {
      const res = await getUserPage({
        pageNum: page,
        pageSize: size,
        keyword: keyword || undefined,
        status: status,
        tenantId: tenantId,
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

  /** 切换用户状态 */
  const handleToggle = async (userId: number, status: number) => {
    const hide = message.loading(intl.formatMessage({
      id: 'pages.message.updating',
      defaultMessage: 'Updating...',
    }));
    try {
      const response = await toggleUserStatus(userId, status);
      hide();
      if (response.code === 200) {
        messageApi.success(
          intl.formatMessage({
            id: 'pages.user.management.updateSuccess',
            defaultMessage: '更新成功',
          }),
        );
        loadData();
      } else {
        const errorMsg = response.message || intl.formatMessage({
          id: 'pages.user.management.updateFailed',
          defaultMessage: '更新失败，请重试',
        });
        messageApi.error(errorMsg);
      }
    } catch (error: any) {
      hide();
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
        id: 'pages.user.management.updateFailed',
        defaultMessage: '更新失败，请重试',
      });
      messageApi.error(errorMsg);
    }
  };

  /** 删除节点 */
  const handleRemove = async (userId: number) => {
    Modal.confirm({
      title: intl.formatMessage({
        id: 'pages.user.management.deleteConfirm',
        defaultMessage: '确认删除该用户吗？',
      }),
      content: intl.formatMessage({
        id: 'pages.user.management.deleteContent',
        defaultMessage: '此操作不可恢复，请谨慎操作',
      }),
      okText: intl.formatMessage({
        id: 'pages.user.management.confirm',
        defaultMessage: '确定',
      }),
      cancelText: intl.formatMessage({
        id: 'pages.user.management.cancel',
        defaultMessage: '取消',
      }),
      onOk: async () => {
        const hide = message.loading(intl.formatMessage({
          id: 'pages.message.deleting',
          defaultMessage: 'Deleting...',
        }));
        if (!userId) return;
        try {
          const response = await deleteUser(userId);
          hide();
          if (response.code === 200) {
            messageApi.success(
              intl.formatMessage({
                id: 'pages.user.management.deleteSuccess',
                defaultMessage: '删除成功',
              }),
            );
            loadData();
          } else {
            const errorMsg = response.message || intl.formatMessage({
              id: 'pages.user.management.deleteFailed',
              defaultMessage: '删除失败，请重试',
            });
            messageApi.error(errorMsg);
          }
        } catch (error: any) {
          hide();
          const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
            id: 'pages.user.management.deleteFailed',
            defaultMessage: '删除失败，请重试',
          });
          messageApi.error(errorMsg);
        }
      },
    });
  };

  /** 批量删除 */
  const handleBatchRemove = useCallback(async () => {
    // TODO: 实现批量删除逻辑
    messageApi.warning(intl.formatMessage({
      id: 'pages.message.batchRemovalDevelopment',
      defaultMessage: 'Batch removal feature is under development',
    }));
  }, []);

  const columns: ProColumns<API.UserItem>[] = [
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
        id: 'pages.user.management.keyword',
        defaultMessage: '关键词',
      }),
  dataIndex: 'keyword',
      valueType: 'text',
  hideInTable: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.username',
        defaultMessage: '用户名',
      }),
     dataIndex: 'username',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.nickname',
        defaultMessage: '昵称',
      }),
     dataIndex: 'nickname',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.email',
        defaultMessage: '邮箱',
      }),
     dataIndex: 'email',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.phone',
        defaultMessage: '手机号',
      }),
     dataIndex: 'phone',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.gender',
        defaultMessage: '性别',
      }),
     dataIndex: 'gender',
      valueEnum: {
        0: { text: intl.formatMessage({ id: 'pages.gender.female', defaultMessage: 'Female' }) },
        1: { text: intl.formatMessage({ id: 'pages.gender.male', defaultMessage: 'Male' }) },
        2: { text: intl.formatMessage({ id: 'pages.gender.confidential', defaultMessage: 'Confidential' }) },
      },
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.isAdmin',
        defaultMessage: '管理员',
      }),
     dataIndex: 'isAdmin',
      valueEnum: {
        0: { text: intl.formatMessage({ id: 'pages.common.no', defaultMessage: 'No' }) },
        1: { text: intl.formatMessage({ id: 'pages.common.yes', defaultMessage: 'Yes' }) },
      },
     hideInSearch: true,
     render: (_, record) => {
       return (
          <Tag color={record.isAdmin === 1 ? 'blue' : 'default'}>
            {record.isAdmin === 1 ? intl.formatMessage({ id: 'pages.common.yes', defaultMessage: 'Yes' }) : intl.formatMessage({ id: 'pages.common.no', defaultMessage: 'No' })}
          </Tag>
        );
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.tenantCount',
        defaultMessage: '所属租户',
      }),
     dataIndex: 'tenantCount',
      hideInSearch: true,
     render: (_, record) => {
       const count = (record as any).tenantCount || 0;
       return count;
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.createTime',
        defaultMessage: '创建时间',
      }),
    dataIndex: 'createTime',
     valueType: 'dateTime',
    hideInForm: true,
    hideInSearch: true,
   sorter: true,
    defaultSortOrder: 'descend',
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.lastLoginTime',
        defaultMessage: '最近登录',
      }),
    dataIndex: 'lastLoginTime',
     valueType: 'dateTime',
    hideInForm: true,
    hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.action',
        defaultMessage: '操作',
      }),
      valueType: 'option',
      key: 'option',
     render: (text, record) => (
        <Space size={8}>
          <StatusSwitch 
            status={record.status}
            onChange={(newStatus) => handleToggle(record.id!, newStatus)}
            disabled={record.isAdmin === 1}
          />
          <EditButton 
            onClick={() => {
              setCurrentRow(record);
              setUpdateModalVisible(true);
            }}
          />
          {/* 管理员用户不显示删除按钮 */}
          {record.isAdmin !== 1 && (
            <DeleteButton 
              onConfirm={() => handleRemove(record.id!)}
              confirmTitle={intl.formatMessage({ id: 'pages.user.management.deleteConfirm', defaultMessage: 'Are you sure to delete this user?' })}
            />
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <UserOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({
              id: 'pages.user.management.title',
              defaultMessage: '用户管理',
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
          setKeyword('');
          setStatus(undefined);
          setTenantId(undefined);
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
              id: 'pages.user.management.add',
              defaultMessage: 'New User',
            })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={setKeyword}
          onSearch={handleSearch}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.search.placeholder',
            defaultMessage: '搜索用户名或邮箱',
          })}
          width="auto"
        />
        <FilterSelect
          value={status}
          onChange={setStatus}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.status.filter.placeholder',
            defaultMessage: '状态筛选',
          })}
          width="auto"
          options={[
            { 
              label: intl.formatMessage({
                id: 'pages.status.enabled',
                defaultMessage: '启用',
              }), 
              value: 1 
            },
            { 
              label: intl.formatMessage({
                id: 'pages.status.disabled',
                defaultMessage: '禁用',
              }), 
              value: 0 
            },
          ]}
        />
      </SearchFilterBar>

      <StyledProTable<API.UserItem>
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

      {/* 新建用户弹窗 */}
      <CreateForm
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values: API.SysUserCreateRequest) => {
          try {
            const res = await createUser(values);
            
            // 检查后端返回的 code 字段
            if (res.code === 200) {
              messageApi.success(intl.formatMessage({
                id: 'pages.user.management.createSuccess',
                defaultMessage: 'Created successfully',
              }));
              setCreateModalVisible(false);
              loadData();
            } else {
              messageApi.error(res.message || intl.formatMessage({
                id: 'pages.user.management.createFailed',
                defaultMessage: 'Create failed, please try again',
              }));
            }
          } catch (error: any) {
            messageApi.error(error?.message || intl.formatMessage({
              id: 'pages.user.management.createFailed',
              defaultMessage: 'Create failed, please try again',
            }));
          }
        }}
        visible={createModalVisible}
      />

      {/* 更新用户弹窗 */}
      {currentRow && (
        <UpdateForm
          onSubmit={async (values) => {
            try {
              await updateUserApi(currentRow.id || 0, values);
              messageApi.success(intl.formatMessage({
                id: 'pages.user.management.updateSuccess',
                defaultMessage: 'Updated successfully',
              }));
              setUpdateModalVisible(false);
              setCurrentRow(undefined);
              loadData();
            } catch (error) {
              messageApi.error(intl.formatMessage({
                id: 'pages.user.management.updateFailed',
                defaultMessage: 'Update failed, please try again',
              }));
            }
          }}
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          visible={updateModalVisible}
          values={currentRow}
        />
      )}

      {/* 管理租户弹窗 */}
      <Modal
        title={
          <span style={{ fontSize: '16px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <TeamOutlined style={{ marginRight: 8, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({
              id: 'pages.user.management.tenant.management.title',
              defaultMessage: '管理用户租户',
            })}
          </span>
        }
        open={tenantModalVisible}
        onCancel={() => {
          setTenantModalVisible(false);
          setSelectedUserId(0);
        }}
        footer={null}
        width={900}
        destroyOnClose
        styles={{
          body: { padding: '24px' },
          header: {
            background: 'linear-gradient(135deg, #f7f8ff 0%, #eef1fe 100%)',
            borderBottom: '1px solid var(--vip-border)',
            padding: '18px 24px',
          },
        }}
      >
        {selectedUserId > 0 && (
          <UserTenantList
            userId={selectedUserId}
            visible={tenantModalVisible}
          />
        )}
      </Modal>
    </PageContainer>
  );
};

export default UserManagement;
