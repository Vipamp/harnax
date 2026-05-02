import React, { useState, useEffect } from 'react';
import { Table, Button, Modal, message, Tag } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { ProForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { getUserPage } from '@/services/ant-design-pro/user';
import { getTenantUsers, addUserToTenant, removeUserFromTenant } from '@/services/tenant';

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
      message.error('加载租户用户列表失败');
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
      message.error('加载用户列表失败');
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
        message.success('添加用户成功');
        setAddModalVisible(false);
        loadTenantUsers();
      } else {
        message.error(response.message || '添加用户失败');
      }
    } catch (error: any) {
      message.error(error?.message || '添加用户失败');
    }
  };

  // 从租户移除用户
  const handleRemoveUser = async (userId: number) => {
    Modal.confirm({
      title: '确认移除',
      content: '确定要从该租户中移除此用户吗？',
      onOk: async () => {
        try {
          const response = await removeUserFromTenant(tenantId, userId);
          if (response.code === 200) {
            message.success('移除用户成功');
            loadTenantUsers();
          } else {
            message.error(response.message || '移除用户失败');
          }
        } catch (error: any) {
          message.error(error?.message || '移除用户失败');
        }
      },
    });
  };

  const columns = [
    {
      title: '用户名',
      dataIndex: 'username',
      key: 'username',
    },
    {
      title: '昵称',
      dataIndex: 'nickname',
      key: 'nickname',
    },
    {
      title: '角色',
      dataIndex: 'role',
      key: 'role',
      render: (role: string) => (
        <Tag color={role === 'admin' ? 'blue' : 'default'}>
          {role === 'admin' ? '管理员' : '成员'}
        </Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: number) => (
        <Tag color={status === 1 ? 'green' : 'red'}>
          {status === 1 ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '加入时间',
      dataIndex: 'joinedAt',
      key: 'joinedAt',
    },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: any) => (
        <Button
          type="link"
          danger
          icon={<DeleteOutlined />}
          onClick={() => handleRemoveUser(record.userId)}
        >
          移除
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
        <h3>租户用户列表</h3>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            loadAvailableUsers();
            setAddModalVisible(true);
          }}
        >
          添加用户
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
          showTotal: (total) => `共 ${total} 条`,
        }}
      />

      <Modal
        title="添加用户到租户"
        open={addModalVisible}
        onCancel={() => setAddModalVisible(false)}
        footer={null}
        width={600}
      >
        <ProForm onFinish={handleAddUser} submitter={{}}>
          <ProFormSelect
            name="userId"
            label="选择用户"
            placeholder="请选择要添加的用户"
            options={userOptions}
            rules={[{ required: true, message: '请选择用户' }]}
            fieldProps={{
              loading: userLoading,
              showSearch: true,
            }}
          />
          <ProFormSelect
            name="role"
            label="用户角色"
            placeholder="请选择用户角色"
            initialValue="member"
            options={[
              { label: '成员', value: 'member' },
              { label: '管理员', value: 'admin' },
            ]}
            rules={[{ required: true, message: '请选择用户角色' }]}
          />
          <div style={{ textAlign: 'right', marginTop: 24 }}>
            <Button style={{ marginRight: 8 }} onClick={() => setAddModalVisible(false)}>
              {intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
            </Button>
            <Button type="primary" htmlType="submit">
              {intl.formatMessage({ id: 'pages.common.submit', defaultMessage: 'Submit' })}
            </Button>
          </div>
        </ProForm>
      </Modal>
    </div>
  );
};

export default TenantUserList;
