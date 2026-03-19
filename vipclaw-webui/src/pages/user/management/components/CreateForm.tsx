import React from 'react';
import { Modal } from 'antd';
import type { ProColumns } from '@ant-design/pro-components';
import { ProForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';

export interface CreateFormProps {
  onCancel: () => void;
  onSubmit: (values: API.UserItem) => Promise<void>;
  visible: boolean;
}

const CreateForm: React.FC<CreateFormProps> = (props) => {
  const { onCancel, onSubmit, visible } = props;
  const intl = useIntl();

  return (
    <Modal
      destroyOnClose
      title={
        <span style={{ fontSize: '16px', fontWeight: 600, color: '#1a1a2e' }}>
          {intl.formatMessage({
            id: 'pages.user.management.add',
            defaultMessage: '新建用户',
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
      <ProForm<API.UserItem>
        onFinish={onSubmit}
        submitter={{
          render: (_, dom) => [dom],
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
          rules={[
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
          rules={[
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
            { label: '男', value: 1 },
            { label: '女', value: 0 },
            { label: '保密', value: 2 },
          ]}
          initialValue={1}
          fieldProps={{ defaultValue: 1 }}
        />

        <ProFormSelect
          name="status"
          label={intl.formatMessage({
            id: 'pages.user.management.status',
            defaultMessage: '状态',
          })}
          options={[
            { label: '正常', value: 1 },
            { label: '禁用', value: 0 },
          ]}
          initialValue={1}
          fieldProps={{ defaultValue: 1 }}
        />

        <ProFormSelect
          name="isAdmin"
          label={intl.formatMessage({
            id: 'pages.user.management.isAdmin',
            defaultMessage: '管理员',
          })}
          options={[
            { label: '是', value: 1 },
            { label: '否', value: 0 },
          ]}
          initialValue={0}
          fieldProps={{ defaultValue: 0 }}
        />

        <ProFormTextArea
          name="avatar"
          label={intl.formatMessage({
            id: 'pages.user.management.avatar',
            defaultMessage: '头像 URL',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.avatar.placeholder',
            defaultMessage: '请输入头像 URL',
          })}
          fieldProps={{
            rows: 2,
          }}
        />
      </ProForm>
    </Modal>
  );
};

export default CreateForm;
