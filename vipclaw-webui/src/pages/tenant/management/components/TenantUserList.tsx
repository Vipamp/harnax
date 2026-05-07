import React, { useState, useEffect } from 'react';
import { Table, Button, Modal, message, Tag, Space, Tooltip, Select } from 'antd';
import { PlusOutlined, UsergroupAddOutlined, DeleteOutlined } from '@ant-design/icons';
import { ProForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { getUserPage } from '@/services/ant-design-pro/user';
import { getTenantUsers, addUserToTenant, removeUserFromTenant, updateUserRole } from '@/services/tenant';
import { FormModal } from '@/components/FormModal';

export interface TenantUserListProps {
  tenantId: number;
  visible: boolean;
}

const TenantUserList: React.FC<TenantUserListProps> = ({ tenantId, visible }) => {
  const intl = useIntl();
  const [users, setUsers] = useState<any[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [loading, setLoading] = useState<boolean>(false);
  const [addModalVisible, setAddModalVisible] = useState<boolean>(false);
  const [userOptions, setUserOptions] = useState<any[]>([]);
  const [userLoading, setUserLoading] = useState<boolean>(false);

  // 加载租户用户列表
  useEffect(() => {
    if (visible && tenantId) {
      loadTenantUsers();
    }
  }, [visible, tenantId]);

  const loadTenantUsers = async () => {
    setLoading(true);
    try {
      const response = await getTenantUsers(tenantId, {
        pageNum: 1,
        pageSize: 100,
      });

      if (response.code === 200) {
        setUsers(response.data?.records || []);
        setTotal(response.data?.total || 0);
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.tenant.userListLoadFailed', defaultMessage: 'Failed to load tenant user list' }));
    } finally {
      setLoading(false);
    }
  };

  // 加载可添加的用户列表
  const loadAvailableUsers = async () => {
    setUserLoading(true);
    try {
      const response = await getUserPage({
        pageNum: 1,
        pageSize: 1000,
        status: 1,
      });

      if (response.code === 200) {
        const allUsers = response.data?.records || [];
        // 过滤掉已经在租户中的用户
        const existingUserIds = new Set(users.map((u) => u.userId));
        const availableUsers = allUsers.filter((u: any) => !existingUserIds.has(u.id));

        const options = availableUsers.map((user: any) => ({
          label: `${user.nickname || user.username} (${user.username})`,
          value: user.id,
        }));
        setUserOptions(options);
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.tenant.availableUserListLoadFailed', defaultMessage: 'Failed to load available user list' }));
    } finally {
      setUserLoading(false);
    }
  };

  // 添加用户到租户
  const handleAddUser = async (fields: any) => {
    try {
      const response = await addUserToTenant(tenantId, {
        userId: fields.userId,
        role: fields.role || 'member',
      });

      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.tenant.userAddSuccess', defaultMessage: 'User added successfully' }));
        setAddModalVisible(false);
        loadTenantUsers();
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.tenant.userAddFailed', defaultMessage: 'Failed to add user' }));
      }
    } catch (error: any) {
      message.error(error?.message || intl.formatMessage({ id: 'pages.tenant.userAddFailed', defaultMessage: 'Failed to add user' }));
    }
  };

  // 从租户移除用户
  const handleRemoveUser = async (userId: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.tenant.confirmRemove', defaultMessage: 'Confirm Remove' }),
      content: intl.formatMessage({ id: 'pages.tenant.confirmRemoveUser', defaultMessage: 'Are you sure to remove this user from the tenant?' }),
      onOk: async () => {
        try {
          const response = await removeUserFromTenant(tenantId, userId);
          if (response.code === 200) {
            message.success(intl.formatMessage({ id: 'pages.tenant.userRemoveSuccess', defaultMessage: 'User removed successfully' }));
            loadTenantUsers();
          } else {
            message.error(response.message || intl.formatMessage({ id: 'pages.tenant.userRemoveFailed', defaultMessage: 'Failed to remove user' }));
          }
        } catch (error: any) {
          message.error(error?.message || intl.formatMessage({ id: 'pages.tenant.userRemoveFailed', defaultMessage: 'Failed to remove user' }));
        }
      },
    });
  };

  // 更新用户角色
  const handleRoleChange = async (userId: number, newRole: string) => {
    try {
      const response = await updateUserRole(tenantId, userId, newRole);
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.tenant.roleUpdateSuccess', defaultMessage: 'Role updated successfully' }));
        loadTenantUsers();
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.tenant.roleUpdateFailed', defaultMessage: 'Failed to update role' }));
      }
    } catch (error: any) {
      message.error(error?.message || intl.formatMessage({ id: 'pages.tenant.roleUpdateFailed', defaultMessage: 'Failed to update role' }));
    }
  };

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.tenant.user.list.column.username', defaultMessage: 'Username' }),
      dataIndex: 'username',
      key: 'username',
    },
    {
      title: intl.formatMessage({ id: 'pages.tenant.user.list.column.nickname', defaultMessage: 'Nickname' }),
      dataIndex: 'nickname',
      key: 'nickname',
    },
    {
      title: intl.formatMessage({ id: 'pages.tenant.user.list.column.role', defaultMessage: 'Role' }),
      dataIndex: 'role',
      key: 'role',
      render: (role: string, record: any) => (
        <Select
          value={role}
          onChange={(value) => handleRoleChange(record.userId, value)}
          style={{ width: 120 }}
          options={[
            { 
              label: intl.formatMessage({ id: 'pages.tenant.role.admin', defaultMessage: 'Admin' }), 
              value: 'admin' 
            },
            { 
              label: intl.formatMessage({ id: 'pages.tenant.role.member', defaultMessage: 'Member' }), 
              value: 'member' 
            },
          ]}
        />
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.tenant.user.list.column.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      render: (status: number) => (
        <Tag color={status === 1 ? 'green' : 'red'}>
          {status === 1 ? intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.tenant.user.list.column.joinedAt', defaultMessage: 'Joined At' }),
      dataIndex: 'joinedAt',
      key: 'joinedAt',
    },
    {
      title: intl.formatMessage({ id: 'pages.common.operation', defaultMessage: 'Action' }),
      key: 'action',
      render: (_: any, record: any) => (
        <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Remove' })}>
          <Button
            type="link"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => handleRemoveUser(record.userId)}
          />
        </Tooltip>
      ),
    },
  ];

  if (!visible) {
    return null;
  }

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h3>{intl.formatMessage({ id: 'pages.tenant.user.list.title', defaultMessage: 'Tenant User List' })}</h3>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            loadAvailableUsers();
            setAddModalVisible(true);
          }}
        >
          {intl.formatMessage({ id: 'pages.tenant.addUser', defaultMessage: 'Add User' })}
        </Button>
      </div>

      <Table
        rowKey="userId"
        columns={columns}
        dataSource={users}
        loading={loading}
        pagination={{
          total,
          pageSize: 10,
          showSizeChanger: true,
          showTotal: (total) => intl.formatMessage(
            { id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' },
            { total: total }
          ),
        }}
      />

      <FormModal
        open={addModalVisible}
        onCancel={() => setAddModalVisible(false)}
        size="sm"
        titleConfig={{
          mainTitle: intl.formatMessage({ id: 'pages.tenant.addUserToTenant', defaultMessage: 'Add User to Tenant' }),
          subtitle: intl.formatMessage({
            id: 'pages.tenant.addUserToTenant.subtitle',
            defaultMessage: 'Select a user and assign a role to add to the current tenant',
          }),
          icon: <UsergroupAddOutlined />,
        }}
      >
        <ProForm 
          onFinish={handleAddUser}
          layout="horizontal"
          labelCol={{ span: 6 }}
          wrapperCol={{ span: 18 }}
          submitter={{
            render: (_, dom) => (
              <div style={{ 
                display: 'flex', 
                justifyContent: 'flex-end', 
                gap: '10px',
                marginTop: '12px',
                paddingTop: '10px',
                borderTop: '1px solid var(--vip-border)'
              }}>
                {dom.map((item: any) => 
                  React.cloneElement(item, {
                    style: {
                      fontSize: '12px',
                      fontWeight: 500,
                      height: '32px',
                      padding: '4px 20px',
                      borderRadius: '6px',
                      ...(item.props.style || {})
                    }
                  })
                )}
              </div>
            ),
            searchConfig: {
              submitText: intl.formatMessage({ id: 'pages.common.submit', defaultMessage: '提交' }),
              resetText: intl.formatMessage({ id: 'pages.common.reset', defaultMessage: '重置' }),
            },
          }}
        >
          <ProFormSelect
            name="userId"
            label={intl.formatMessage({ id: 'pages.tenant.selectUser', defaultMessage: 'Select User' })}
            placeholder={intl.formatMessage({ id: 'pages.tenant.placeholder.selectUser', defaultMessage: 'Please select user to add' })}
            options={userOptions}
            rules={[{ required: true, message: intl.formatMessage({ id: 'pages.tenant.userRequired', defaultMessage: 'Please select user' }) }]}
            fieldProps={{
              loading: userLoading,
              showSearch: true,
            }}
          />
          <ProFormSelect
            name="role"
            label={intl.formatMessage({ id: 'pages.tenant.userRole', defaultMessage: '用户角色' })}
            placeholder={intl.formatMessage({ id: 'pages.tenant.userRole.placeholder', defaultMessage: '请选择用户角色' })}
            initialValue="member"
            options={[
              { 
                label: intl.formatMessage({ id: 'pages.tenant.role.member', defaultMessage: '成员' }), 
                value: 'member' 
              },
              { 
                label: intl.formatMessage({ id: 'pages.tenant.role.admin', defaultMessage: '管理员' }), 
                value: 'admin' 
              },
            ]}
            rules={[{ required: true, message: intl.formatMessage({ id: 'pages.tenant.userRole.required', defaultMessage: '请选择用户角色' }) }]}
          />
        </ProForm>
      </FormModal>
    </div>
  );
};

export default TenantUserList;
