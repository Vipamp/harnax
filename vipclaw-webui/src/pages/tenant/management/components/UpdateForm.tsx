import React from 'react';
import { ShopOutlined } from '@ant-design/icons';
import { ProForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { FormModal } from '@/components/FormModal';

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
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="sm"
      titleConfig={{
        mainTitle: intl.formatMessage({
          id: 'pages.tenant.management.edit',
          defaultMessage: '编辑租户',
        }),
        subtitle: intl.formatMessage({
          id: 'pages.tenant.management.edit.subtitle',
          defaultMessage: '修改租户信息，保存后即时生效',
        }),
        icon: <ShopOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-warning) 0%, var(--vip-warning-light) 100%)',
        iconShadowColor: 'rgba(250, 173, 20, 0.25)',
      }}
    >
      <ProForm
        initialValues={{
          name: values?.name,
          status: values?.status,
        }}
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
    </FormModal>
  );
};

export default UpdateForm;
