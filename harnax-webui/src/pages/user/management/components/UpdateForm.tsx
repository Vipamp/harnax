import React from 'react';
import { Switch } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { ProForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { FormModal } from '@/components/FormModal';

export interface UpdateFormProps {
  onCancel: () => void;
  onSubmit: (values: API.UserItem) => Promise<void>;
  visible: boolean;
  values?: API.UserItem;
}

const UpdateForm: React.FC<UpdateFormProps> = (props) => {
  const { onCancel, onSubmit, visible, values } = props;
  const intl = useIntl();

  // Email 必填
  const emailRules: any[] = [
    {
      required: true,
      message: intl.formatMessage({
        id: 'pages.user.management.email.required',
        defaultMessage: '邮箱不能为空',
      }),
    },
    {
      type: 'email',
      message: intl.formatMessage({
        id: 'pages.user.management.email.pattern',
        defaultMessage: '请输入正确的邮箱格式',
      }),
    },
  ];

  // Phone 必填
  const phoneRules: any[] = [
    {
      required: true,
      message: intl.formatMessage({
        id: 'pages.user.management.phone.required',
        defaultMessage: '手机号不能为空',
      }),
    },
    {
      pattern: /^1[3-9]\d{9}$/,
      message: intl.formatMessage({
        id: 'pages.user.management.phone.pattern',
        defaultMessage: '请输入正确的手机号',
      }),
    },
  ];

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: intl.formatMessage({
          id: 'pages.user.management.edit',
          defaultMessage: '编辑用户',
        }),
        subtitle: intl.formatMessage({
          id: 'pages.user.management.edit.subtitle',
          defaultMessage: '修改用户信息，保存后即时生效',
        }),
        icon: <UserOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(var(--vip-primary-rgb), 0.25)',
      }}
    >
      <ProForm<API.UserItem>
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
                    fontWeight: 500,
                    ...(item.props.style || {})
                  }
                })
              )}
            </div>
          ),
          searchConfig: {
            submitText: intl.formatMessage({
              id: 'pages.user.management.submit',
              defaultMessage: '提交',
            }),
            resetText: intl.formatMessage({
              id: 'pages.user.management.reset',
              defaultMessage: '重置',
            }),
          },
        }}
        initialValues={{
          id: values?.id,
          username: values?.username,
          nickname: values?.nickname,
          email: values?.email,
          phone: values?.phone,
          gender: values?.gender,
          avatar: values?.avatar,
        }}
      >
        <ProFormText
          name="username"
          label={intl.formatMessage({
            id: 'pages.user.management.username',
            defaultMessage: '用户名',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.username.placeholder',
            defaultMessage: '请输入用户名',
          })}
          disabled
          readonly
        />

        <ProFormText
          name="nickname"
          label={intl.formatMessage({
            id: 'pages.user.management.nickname',
            defaultMessage: '昵称',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.nickname.placeholder',
            defaultMessage: '请输入昵称',
          })}
        />

        <ProFormText
          name="email"
          label={intl.formatMessage({
            id: 'pages.user.management.email',
            defaultMessage: '邮箱',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.email.placeholder',
            defaultMessage: '请输入邮箱',
          })}
          fieldProps={{
            type: 'email',
          }}
          rules={emailRules}
        />

        <ProFormText
          name="phone"
          label={intl.formatMessage({
            id: 'pages.user.management.phone',
            defaultMessage: '手机号',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.phone.placeholder',
            defaultMessage: '请输入手机号',
          })}
          rules={phoneRules}
        />

        <ProFormSelect
          name="gender"
          label={intl.formatMessage({
            id: 'pages.user.management.gender',
            defaultMessage: '性别',
          })}
          options={[
            { label: intl.formatMessage({ id: 'pages.gender.female', defaultMessage: 'Female' }), value: 0 },
            { label: intl.formatMessage({ id: 'pages.gender.male', defaultMessage: 'Male' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.gender.unknown', defaultMessage: 'Unknown' }), value: 2 },
          ]}
        />

        <ProFormText
          name="avatar"
          label={intl.formatMessage({
            id: 'pages.user.management.avatar',
            defaultMessage: '头像 URL',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.avatar.placeholder',
            defaultMessage: '请输入头像 URL',
          })}
        />
      </ProForm>
    </FormModal>
  );
};

export default UpdateForm;
