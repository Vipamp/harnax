import React from 'react';
import { Modal } from 'antd';
import type { ProColumns } from '@ant-design/pro-components';
import { ProForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';

export interface UpdateFormProps {
  onCancel: () => void;
  onSubmit: (values: API.UserItem) => Promise<void>;
  visible: boolean;
  values?: API.UserItem;
}

const UpdateForm: React.FC<UpdateFormProps> = (props) => {
  const { onCancel, onSubmit, visible, values } = props;
  const intl = useIntl();

  return (
    <Modal
      destroyOnClose
      title={intl.formatMessage({
        id: 'pages.user.management.edit',
        defaultMessage: '编辑用户',
      })}
      width={800}
      open={visible}
      footer={null}
      onCancel={() => onCancel()}
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
        initialValues={{
          userId: values?.userId,
          username: values?.username,
          nickname: values?.nickname,
          email: values?.email,
          phone: values?.phone,
          gender: values?.gender,
          avatar: values?.avatar,
          status: values?.status,
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
            defaultMessage: '不修改请留空',
          })}
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
          valueEnum={{
            0: '女',
            1: '男',
            2: '保密',
          }}
        />

        <ProFormSelect
          name="status"
          label={intl.formatMessage({
            id: 'pages.user.management.status',
            defaultMessage: '状态',
          })}
          valueEnum={{
            0: '禁用',
            1: '正常',
          }}
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

export default UpdateForm;
