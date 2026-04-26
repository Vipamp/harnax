import React from 'react';
import { Modal, Switch } from 'antd';
import type { ProColumns } from '@ant-design/pro-components';
import { ProForm, ProFormSelect, ProFormText, ProFormTextArea, ProFormSwitch } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { isPersonal } from '@/utils/edition';

export interface UpdateFormProps {
  onCancel: () => void;
  onSubmit: (values: API.UserItem) => Promise<void>;
  visible: boolean;
  values?: API.UserItem;
}

const UpdateForm: React.FC<UpdateFormProps> = (props) => {
  const { onCancel, onSubmit, visible, values } = props;
  const intl = useIntl();
  
  // 根据版本设置校验规则：企业版和公开版必填，个人版不强制
  const isPersonalEdition = isPersonal();
  
  const emailRules: any[] = [
    {
      type: 'email',
      message: intl.formatMessage({
        id: 'pages.user.management.email.pattern',
        defaultMessage: '请输入正确的邮箱格式',
      }),
    },
  ];
  
  // 企业版和公开版：email 必填
  if (!isPersonalEdition) {
    emailRules.unshift({
      required: true,
      message: intl.formatMessage({
        id: 'pages.user.management.email.required',
        defaultMessage: '邮箱不能为空',
      }),
    });
  }
  
  const phoneRules: any[] = [
    {
      pattern: /^1[3-9]\d{9}$/,
      message: intl.formatMessage({
        id: 'pages.user.management.phone.pattern',
        defaultMessage: '请输入正确的手机号',
      }),
    },
  ];
  
  // 企业版和公开版：phone 必填
  if (!isPersonalEdition) {
    phoneRules.unshift({
      required: true,
      message: intl.formatMessage({
        id: 'pages.user.management.phone.required',
        defaultMessage: '手机号不能为空',
      }),
    });
  }

  return (
    <Modal
      destroyOnClose
      title={
        <span style={{ fontSize: '16px', fontWeight: 600, color: '#1a1a2e' }}>
          {intl.formatMessage({
            id: 'pages.user.management.edit',
            defaultMessage: '编辑用户',
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
            { label: '女', value: 0 },
            { label: '男', value: 1 },
            { label: '未知', value: 2 },
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
    </Modal>
  );
};

export default UpdateForm;
