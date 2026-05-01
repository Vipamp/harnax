import React, { useState, useEffect } from 'react';
import { Modal, message } from 'antd';
import { ProForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { getUserPage } from '@/services/ant-design-pro/user';

export interface CreateFormProps {
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
  visible: boolean;
}

const CreateForm: React.FC<CreateFormProps> = (props) => {
  const { onCancel, onSubmit, visible } = props;
  const intl = useIntl();
  const [userOptions, setUserOptions] = useState<any[]>([]);
  const [userLoading, setUserLoading] = useState<boolean>(false);

  // 加载用户列表
  useEffect(() => {
    if (visible) {
      loadUsers();
    }
  }, [visible]);

  const loadUsers = async () => {
    setUserLoading(true);
    try {
      const res = await getUserPage({
        pageNum: 1,
        pageSize: 1000, // 获取所有用户
        status: 1, // 只获取启用状态的用户
      });
      const users = res.data?.records || [];
      const options = users.map((user: any) => ({
        label: `${user.nickname || user.username} (${user.username})`,
        value: user.id,
      }));
      setUserOptions(options);
    } catch (error) {
      message.error('加载用户列表失败');
    } finally {
      setUserLoading(false);
    }
  };

  return (
    <Modal
      destroyOnClose
      title={
        <span style={{ fontSize: '16px', fontWeight: 600, color: '#1a1a2e' }}>
          {intl.formatMessage({
            id: 'pages.tenant.management.createNew',
            defaultMessage: '新建租户',
          })}
        </span>
      }
      width={640}
      open={visible}
      footer={null}
      onCancel={() => onCancel()}
      styles={{
        body: { padding: '24px 28px', background: '#fafbff' },
        header: {
          background: 'linear-gradient(135deg, #f7f8ff 0%, #eef1fe 100%)',
          borderBottom: '1px solid #e8ecfb',
          padding: '18px 24px',
        },
      }}
    >
      <ProForm
        onFinish={onSubmit}
        submitter={{
          render: (_, dom) => [dom],
          searchConfig: {
            submitText: intl.formatMessage({
              id: 'pages.tenant.management.submit',
              defaultMessage: '提交',
            }),
            resetText: intl.formatMessage({
              id: 'pages.tenant.management.reset',
              defaultMessage: '重置',
            }),
          },
        }}
      >
        <ProFormText
          name="name"
          label={intl.formatMessage({
            id: 'pages.tenant.management.tenantName',
            defaultMessage: '租户名称',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.tenant.management.tenantName.placeholder',
            defaultMessage: '请输入租户名称',
          })}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.tenant.management.tenantName.required',
                defaultMessage: '请输入租户名称',
              }),
            },
            {
              min: 2,
              message: intl.formatMessage({
                id: 'pages.tenant.management.tenantName.min',
                defaultMessage: '租户名称至少 2 个字符',
              }),
            },
          ]}
        />
        <ProFormSelect
          name="adminUserId"
          label={intl.formatMessage({
            id: 'pages.tenant.management.adminUserId',
            defaultMessage: '管理员用户',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.tenant.management.adminUserId.placeholder',
            defaultMessage: '请选择管理员用户',
          })}
          options={userOptions}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.tenant.management.adminUserId.required',
                defaultMessage: '请选择管理员用户',
              }),
            },
          ]}
          fieldProps={{
            loading: userLoading,
            showSearch: true,
            filterOption: (input: string, option: any) =>
              (option?.label ?? '').toLowerCase().includes(input.toLowerCase()),
          }}
        />
      </ProForm>
    </Modal>
  );
};

export default CreateForm;
