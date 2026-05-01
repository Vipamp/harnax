import React from 'react';
import { Modal, message } from 'antd';
import { ProForm, ProFormDigit, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';

export interface CreateFormProps {
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
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
        <ProFormDigit
          name="adminUserId"
          label={intl.formatMessage({
            id: 'pages.tenant.management.adminUserId',
            defaultMessage: '管理员用户ID',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.tenant.management.adminUserId.placeholder',
            defaultMessage: '请输入管理员用户ID',
          })}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.tenant.management.adminUserId.required',
                defaultMessage: '请输入管理员用户ID',
              }),
            },
          ]}
          fieldProps={{
            min: 1,
            precision: 0,
          }}
        />
      </ProForm>
    </Modal>
  );
};

export default CreateForm;
