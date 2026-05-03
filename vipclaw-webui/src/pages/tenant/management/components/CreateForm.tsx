import React, { useState, useEffect } from 'react';
import { message } from 'antd';
import { ShopOutlined } from '@ant-design/icons';
import { ProForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { getUserPage } from '@/services/ant-design-pro/user';
import { FormModal } from '@/components/FormModal';

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
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="sm"
      titleConfig={{
        mainTitle: intl.formatMessage({
          id: 'pages.tenant.management.createNew',
          defaultMessage: '新建租户',
        }),
        subtitle: intl.formatMessage({
          id: 'pages.tenant.management.createNew.subtitle',
          defaultMessage: '填写租户基本信息，创建独立租户空间',
        }),
        icon: <ShopOutlined />,
      }}
    >
      <ProForm
        onFinish={onSubmit}
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
    </FormModal>
  );
};

export default CreateForm;
