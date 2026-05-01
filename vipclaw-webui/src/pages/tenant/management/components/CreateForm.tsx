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
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{
            width: 40,
            height: 40,
            borderRadius: 12,
            background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 4px 12px rgba(102, 126, 234, 0.3)'
          }}>
            <span style={{ color: 'white', fontSize: 20 }}>🏢</span>
          </div>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#1a1a2e' }}>
            {intl.formatMessage({
              id: 'pages.tenant.management.createNew',
              defaultMessage: '新建租户',
            })}
          </span>
        </div>
      }
      width={680}
      open={visible}
      footer={null}
      onCancel={() => onCancel()}
      styles={{
        body: { 
          padding: '32px', 
          background: 'linear-gradient(135deg, #fafbff 0%, #f5f7ff 100%)',
          borderRadius: '0 0 16px 16px'
        },
        header: {
          background: 'white',
          borderBottom: '2px solid #e8ecfb',
          padding: '24px 32px',
          borderRadius: '16px 16px 0 0'
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
