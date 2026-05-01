import React from 'react';
import { Modal } from 'antd';
import { ProForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';

export interface UpdateFormProps {
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
  visible: boolean;
  values: any;
}

const UpdateForm: React.FC<UpdateFormProps> = (props) => {
  const { onCancel, onSubmit, visible, values } = props;
  const intl = useIntl();

  return (
    <Modal
      destroyOnClose
      title={
        <span style={{ fontSize: '16px', fontWeight: 600, color: '#1a1a2e' }}>
          {intl.formatMessage({
            id: 'pages.tenant.management.edit',
            defaultMessage: '编辑租户',
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
        initialValues={{
          name: values?.name,
          status: values?.status,
        }}
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
              min: 2,
              message: intl.formatMessage({
                id: 'pages.tenant.management.tenantName.min',
                defaultMessage: '租户名称至少 2 个字符',
              }),
            },
          ]}
        />
        <ProFormSelect
          name="status"
          label={intl.formatMessage({
            id: 'pages.tenant.management.status',
            defaultMessage: '状态',
          })}
          valueEnum={{
            0: {
              text: intl.formatMessage({
                id: 'pages.tenant.management.disabled',
                defaultMessage: '禁用',
              }),
            },
            1: {
              text: intl.formatMessage({
                id: 'pages.tenant.management.enabled',
                defaultMessage: '启用',
              }),
            },
          }}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.tenant.management.status.required',
                defaultMessage: '请选择状态',
              }),
            },
          ]}
        />
      </ProForm>
    </Modal>
  );
};

export default UpdateForm;
