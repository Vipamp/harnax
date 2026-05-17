import React, { useState, useEffect } from 'react';
import { Table, Button, Modal, message, Tag, Space } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { ProForm, ProFormSelect } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { getTenantList } from '@/services/tenant';

export interface UserTenantListProps {
  userId: number;
  visible: boolean;
}

const UserTenantList: React.FC<UserTenantListProps> = ({ userId, visible }) => {
  const intl = useIntl();
  const [tenants, setTenants] = useState<any[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [addModalVisible, setAddModalVisible] = useState<boolean>(false);
  const [tenantOptions, setTenantOptions] = useState<any[]>([]);
  const [tenantLoading, setTenantLoading] = useState<boolean>(false);

  // 加载用户所属租户列表
  useEffect(() => {
    if (visible && userId) {
      loadUserTenants();
    }
  }, [visible, userId]);

  const loadUserTenants = async () => {
    setLoading(true);
    try {
      // TODO: 需要创建获取用户租户列表的API
      // const response = await getUserTenants(userId);
      // if (response.code === 200) {
      //   setTenants(response.data || []);
      // }
      
      // 临时使用空数组
      setTenants([]);
      message.info(intl.formatMessage({
        id: 'pages.user.management.tenant.development',
        defaultMessage: 'User tenant management feature is under development',
      }));
    } catch (error) {
      message.error(intl.formatMessage({
        id: 'pages.user.management.tenant.loadFailed',
        defaultMessage: 'Failed to load user tenant list',
      }));
    } finally {
      setLoading(false);
    }
  };

  // 加载可添加的租户列表
  const loadAvailableTenants = async () => {
    setTenantLoading(true);
    try {
      const response = await getTenantList({ current: 1, pageSize: 1000 });

      if (response.code === 200) {
        const allTenants = response.data || [];
        // 过滤掉用户已加入的租户
        const existingTenantIds = new Set(tenants.map((t) => t.tenantId));
        const availableTenants = allTenants.filter((t: any) => !existingTenantIds.has(t.id));

        const options = availableTenants.map((tenant: any) => ({
          label: tenant.name,
          value: tenant.id,
        }));
        setTenantOptions(options);
      }
    } catch (error) {
      message.error(intl.formatMessage({
        id: 'pages.tenant.loadListFailed',
        defaultMessage: 'Failed to load tenant list',
      }));
    } finally {
      setTenantLoading(false);
    }
  };

  // 添加用户到租户
  const handleAddTenant = async (fields: any) => {
    try {
      // TODO: 需要创建添加用户到租户的API
      // const response = await addUserToTenant(fields.tenantId, {
      //   userId: userId,
      //   role: fields.role || 'member',
      // });

      // if (response.code === 200) {
      //   message.success(intl.formatMessage({
      //     id: 'pages.user.management.tenant.addSuccess',
      //     defaultMessage: 'Added successfully',
      //   }));
      //   setAddModalVisible(false);
      //   loadUserTenants();
      // } else {
      //   message.error(response.message || intl.formatMessage({
      //     id: 'pages.user.management.tenant.addFailed',
      //     defaultMessage: 'Failed to add',
      //   }));
      // }
      
      message.success(intl.formatMessage({
        id: 'pages.user.management.tenant.development',
        defaultMessage: 'User tenant management feature is under development',
      }));
      setAddModalVisible(false);
    } catch (error: any) {
      message.error(error?.message || intl.formatMessage({
        id: 'pages.user.management.tenant.addFailed',
        defaultMessage: 'Failed to add',
      }));
    }
  };

  // 从租户移除用户
  const handleRemoveTenant = async (tenantId: number) => {
    Modal.confirm({
      title: intl.formatMessage({
        id: 'pages.user.management.tenant.remove.confirm.title',
        defaultMessage: 'Confirm Remove',
      }),
      content: intl.formatMessage({
        id: 'pages.user.management.tenant.remove.confirm.content',
        defaultMessage: 'Are you sure you want to remove this user from the tenant?',
      }),
      onOk: async () => {
        try {
          // TODO: 需要创建从租户移除用户的API
          // const response = await removeUserFromTenant(tenantId, userId);
          // if (response.code === 200) {
          //   message.success(intl.formatMessage({
          //     id: 'pages.user.management.tenant.remove.success',
          //     defaultMessage: 'Removed successfully',
          //   }));
          //   loadUserTenants();
          // } else {
          //   message.error(response.message || intl.formatMessage({
          //     id: 'pages.user.management.tenant.remove.failed',
          //     defaultMessage: 'Failed to remove user',
          //   }));
          // }
          
          message.success(intl.formatMessage({
            id: 'pages.user.management.tenant.remove.success',
            defaultMessage: 'Removed successfully',
          }));
        } catch (error: any) {
          message.error(error?.message || intl.formatMessage({
            id: 'pages.user.management.tenant.remove.failed',
            defaultMessage: 'Failed to remove user',
          }));
        }
      },
    });
  };

  const columns = [
    {
      title: intl.formatMessage({
        id: 'pages.user.management.tenant.list.column.tenantName',
        defaultMessage: 'Tenant Name',
      }),
      dataIndex: 'tenantName',
      key: 'tenantName',
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.tenant.list.column.role',
        defaultMessage: 'Role',
      }),
      dataIndex: 'role',
      key: 'role',
      render: (role: string) => (
        <Tag color={role === 'admin' ? 'blue' : 'default'}>
          {intl.formatMessage({
            id: role === 'admin' 
              ? 'pages.user.management.tenant.role.admin' 
              : 'pages.user.management.tenant.role.member',
            defaultMessage: role === 'admin' ? 'Admin' : 'Member',
          })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.tenant.list.column.status',
        defaultMessage: 'Status',
      }),
      dataIndex: 'status',
      key: 'status',
      render: (status: number) => (
        <Tag color={status === 1 ? 'green' : 'red'}>
          {intl.formatMessage({
            id: status === 1 
              ? 'pages.status.enabled' 
              : 'pages.status.disabled',
            defaultMessage: status === 1 ? 'Enabled' : 'Disabled',
          })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.tenant.list.column.joinedAt',
        defaultMessage: 'Joined At',
      }),
      dataIndex: 'joinedAt',
      key: 'joinedAt',
    },
    {
      title: intl.formatMessage({
        id: 'pages.common.operation',
        defaultMessage: 'Action',
      }),
      key: 'action',
      render: (_: any, record: any) => (
        <Button
          type="link"
          danger
          icon={<DeleteOutlined />}
          onClick={() => handleRemoveTenant(record.tenantId)}
        >
          {intl.formatMessage({
            id: 'pages.user.management.tenant.list.action.remove',
            defaultMessage: 'Remove',
          })}
        </Button>
      ),
    },
  ];

  if (!visible) {
    return null;
  }

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h3>{intl.formatMessage({
              id: 'pages.user.management.tenant.list.title',
              defaultMessage: 'User Tenant List',
            })}</h3>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            loadAvailableTenants();
            setAddModalVisible(true);
          }}
        >
          {intl.formatMessage({
              id: 'pages.user.management.tenant.add',
              defaultMessage: 'Add to Tenant',
            })}
        </Button>
      </div>

      <Table
        rowKey="tenantId"
        columns={columns}
        dataSource={tenants}
        loading={loading}
        pagination={{
          pageSize: 10,
          showSizeChanger: true,
          showTotal: (total) => intl.formatMessage(
            { id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' },
            { total: total }
          ),
        }}
      />

      <Modal
        title={intl.formatMessage({
              id: 'pages.user.management.tenant.add.title',
              defaultMessage: 'Add User to Tenant',
            })}
        open={addModalVisible}
        onCancel={() => setAddModalVisible(false)}
        footer={null}
        width={600}
      >
        <ProForm onFinish={handleAddTenant} submitter={{}}>
          <ProFormSelect
            name="tenantId"
            label={intl.formatMessage({
              id: 'pages.user.management.tenant.select',
              defaultMessage: 'Select Tenant',
            })}
            placeholder={intl.formatMessage({
              id: 'pages.user.management.tenant.select.placeholder',
              defaultMessage: 'Please select tenant to add',
            })}
            options={tenantOptions}
            rules={[{ required: true, message: intl.formatMessage({
              id: 'pages.user.management.tenant.select.required',
              defaultMessage: 'Please select tenant',
            }) }]}
            fieldProps={{
              loading: tenantLoading,
              showSearch: true,
            }}
          />
          <ProFormSelect
            name="role"
            label={intl.formatMessage({
              id: 'pages.user.management.tenant.role.label',
              defaultMessage: 'User Role',
            })}
            placeholder={intl.formatMessage({
              id: 'pages.user.management.tenant.role.placeholder',
              defaultMessage: 'Please select user role',
            })}
            initialValue="member"
            options={[
              { label: intl.formatMessage({
                id: 'pages.user.management.tenant.role.member',
                defaultMessage: 'Member',
              }), value: 'member' },
              { label: intl.formatMessage({
                id: 'pages.user.management.tenant.role.admin',
                defaultMessage: 'Admin',
              }), value: 'admin' },
            ]}
            rules={[{ required: true, message: intl.formatMessage({
              id: 'pages.user.management.tenant.role.required',
              defaultMessage: 'Please select user role',
            }) }]}
          />
          <div style={{ textAlign: 'right', marginTop: 24 }}>
            <Button style={{ marginRight: 8 }} onClick={() => setAddModalVisible(false)}>
              {intl.formatMessage({
                id: 'pages.common.cancel',
                defaultMessage: 'Cancel',
              })}
            </Button>
            <Button type="primary" htmlType="submit">
              {intl.formatMessage({
                id: 'pages.common.submit',
                defaultMessage: 'Submit',
              })}
            </Button>
          </div>
        </ProForm>
      </Modal>
    </div>
  );
};

export default UserTenantList;
