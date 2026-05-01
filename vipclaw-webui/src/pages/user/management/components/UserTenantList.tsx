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
      message.info('用户租户管理功能开发中');
    } catch (error) {
      message.error('加载用户租户列表失败');
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
      message.error('加载租户列表失败');
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
      //   message.success('添加成功');
      //   setAddModalVisible(false);
      //   loadUserTenants();
      // } else {
      //   message.error(response.message || '添加失败');
      // }
      
      message.success('功能开发中');
      setAddModalVisible(false);
    } catch (error: any) {
      message.error(error?.message || '添加失败');
    }
  };

  // 从租户移除用户
  const handleRemoveTenant = async (tenantId: number) => {
    Modal.confirm({
      title: '确认移除',
      content: '确定要将用户从该租户中移除吗？',
      onOk: async () => {
        try {
          // TODO: 需要创建从租户移除用户的API
          // const response = await removeUserFromTenant(tenantId, userId);
          // if (response.code === 200) {
          //   message.success('移除成功');
          //   loadUserTenants();
          // } else {
          //   message.error(response.message || '移除失败');
          // }
          
          message.success('功能开发中');
        } catch (error: any) {
          message.error(error?.message || '移除失败');
        }
      },
    });
  };

  const columns = [
    {
      title: '租户名称',
      dataIndex: 'tenantName',
      key: 'tenantName',
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
          onClick={() => handleRemoveTenant(record.tenantId)}
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
        <h3>用户所属租户列表</h3>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            loadAvailableTenants();
            setAddModalVisible(true);
          }}
        >
          添加到租户
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
        <ProForm onFinish={handleAddTenant} submitter={{}}>
          <ProFormSelect
            name="tenantId"
            label="选择租户"
            placeholder="请选择要添加的租户"
            options={tenantOptions}
            rules={[{ required: true, message: '请选择租户' }]}
            fieldProps={{
              loading: tenantLoading,
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
              取消
            </Button>
            <Button type="primary" htmlType="submit">
              提交
            </Button>
          </div>
        </ProForm>
      </Modal>
    </div>
  );
};

export default UserTenantList;
