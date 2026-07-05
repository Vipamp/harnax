import React from 'react';
import { message } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { ProForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import * as CryptoJS from 'crypto-js';
import { checkUsername, checkPhone, checkEmail } from '@/services/ant-design-pro/user';
import { FormModal } from '@/components/FormModal';

export interface CreateFormProps {
  onCancel: () => void;
  onSubmit: (values: API.SysUserCreateRequest) => Promise<void>;
  visible: boolean;
}

const CreateForm: React.FC<CreateFormProps> = (props) => {
  const { onCancel, onSubmit, visible } = props;
  const intl = useIntl();

  const handleFinish = async (values: API.SysUserCreateRequest) => {
    // 对密码进行前端加密（SHA-256）
    const encryptedPassword = values.password 
      ? CryptoJS.SHA256(values.password).toString()
      : values.password;
    
    await onSubmit({ ...values, password: encryptedPassword });
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: intl.formatMessage({
          id: 'pages.user.management.add',
          defaultMessage: '新建用户',
        }),
        subtitle: intl.formatMessage({
          id: 'pages.user.management.add.subtitle',
          defaultMessage: '填写用户基本信息，创建系统账号',
        }),
        icon: <UserOutlined />,
      }}
    >
      <ProForm<API.SysUserCreateRequest>
        onFinish={handleFinish}
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
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.user.management.username.required',
                defaultMessage: '请输入用户名',
              }),
            },
            {
              min: 3,
              message: intl.formatMessage({
                id: 'pages.user.management.username.min',
                defaultMessage: '用户名至少 3 个字符',
              }),
            },
            {
              pattern: /^[a-zA-Z0-9_]+$/,
              message: intl.formatMessage({
                id: 'pages.user.management.username.pattern',
                defaultMessage: '用户名只能包含字母、数字和下划线',
              }),
            },
          ]}
          fieldProps={{
            onBlur: async (e: React.FocusEvent<HTMLInputElement>) => {
              const value = e.target.value;
              if (!value || value.length < 3) return;
              try {
                const res = await checkUsername(value);
                if (res.code === 200 && res.data) {
                  message.error(intl.formatMessage({
                    id: 'pages.message.usernameAlreadyExists',
                    defaultMessage: 'Username already exists',
                  }));
                }
              } catch (error) {
                // 忽略错误
              }
            },
          }}
        />

        <ProFormText.Password
          name="password"
          label={intl.formatMessage({
            id: 'pages.user.management.password',
            defaultMessage: '密码',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.password.placeholder',
            defaultMessage: '请输入密码',
          })}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.user.management.password.required',
                defaultMessage: '请输入密码',
              }),
            },
            {
              min: 6,
              message: intl.formatMessage({
                id: 'pages.user.management.password.min',
                defaultMessage: '密码至少 6 个字符',
              }),
            },
          ]}
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
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.user.management.nickname.required',
                defaultMessage: '请输入昵称',
              }),
            },
            {
              min: 1,
              message: intl.formatMessage({
                id: 'pages.user.management.nickname.min',
                defaultMessage: '昵称至少 1 个字符',
              }),
            },
            {
              max: 50,
              message: intl.formatMessage({
                id: 'pages.user.management.nickname.max',
                defaultMessage: '昵称最多 50 个字符',
              }),
            },
          ]}
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
            onBlur: async (e: React.FocusEvent<HTMLInputElement>) => {
              const value = e.target.value;
              if (!value) return;
              try {
                const res = await checkEmail(value);
                if (res.code === 200 && res.data) {
                  message.error(intl.formatMessage({
                    id: 'pages.message.emailAlreadyExists',
                    defaultMessage: 'Email already exists',
                  }));
                }
              } catch (error) {
                // 忽略错误
              }
            },
          }}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.user.management.email.required',
                defaultMessage: '请输入邮箱',
              }),
            },
            {
              type: 'email',
              message: intl.formatMessage({
                id: 'pages.user.management.email.pattern',
                defaultMessage: '请输入正确的邮箱格式',
              }),
            },
          ]}
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
          fieldProps={{
            onBlur: async (e: React.FocusEvent<HTMLInputElement>) => {
              const value = e.target.value;
              if (!value) return;
              try {
                const res = await checkPhone(value);
                if (res.code === 200 && res.data) {
                  message.error(intl.formatMessage({
                    id: 'pages.message.phoneAlreadyExists',
                    defaultMessage: 'Phone number already exists',
                  }));
                }
              } catch (error) {
                // 忽略错误
              }
            },
          }}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.user.management.phone.required',
                defaultMessage: '请输入手机号',
              }),
            },
            {
              pattern: /^1[3-9]\d{9}$/,
              message: intl.formatMessage({
                id: 'pages.user.management.phone.pattern',
                defaultMessage: '请输入正确的手机号',
              }),
            },
          ]}
        />

        <ProFormSelect
          name="gender"
          label={intl.formatMessage({
            id: 'pages.user.management.gender',
            defaultMessage: '性别',
          })}
          options={[
            { label: intl.formatMessage({ id: 'pages.gender.male', defaultMessage: 'Male' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.gender.female', defaultMessage: 'Female' }), value: 0 },
            { label: intl.formatMessage({ id: 'pages.gender.unknown', defaultMessage: 'Unknown' }), value: 2 },
          ]}
          initialValue={2}
          fieldProps={{ defaultValue: 2 }}
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

export default CreateForm;
