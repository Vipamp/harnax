import React, { useState, useEffect } from 'react';
import { Form, Input, InputNumber, DatePicker, Checkbox, Button, Switch } from 'antd';
import { useIntl } from '@umijs/max';
import { KeyOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';

interface UpdateFormProps {
  visible: boolean;
  values: API.ApiKeyItem;
  onCancel: () => void;
  onSubmit: (values: API.ApiKeyUpdateRequest) => Promise<void>;
}

const SCOPE_OPTIONS = [
  { label: '对话 (chat)', value: 'chat' },
  { label: '管理 (manager)', value: 'manager' },
];

const UpdateForm: React.FC<UpdateFormProps> = ({ visible, values, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (visible && values) {
      form.setFieldsValue({
        name: values.name,
        scopes: values.scopes ? values.scopes.split(',') : [],
        tenantId: values.tenantId,
        rateLimit: values.rateLimit,
        expiresAt: values.expiresAt ? require('dayjs')(values.expiresAt) : undefined,
        enabled: values.enabled === 1,
      });
    }
  }, [visible, values]);

  useEffect(() => {
    if (!visible) {
      form.resetFields();
    }
  }, [visible]);

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);
      const submitData: API.ApiKeyUpdateRequest = {
        scopes: (formValues.scopes as string[]).join(','),
        tenantId: formValues.tenantId,
        rateLimit: formValues.rateLimit,
        enabled: formValues.enabled ? 1 : 0,
        expiresAt: formValues.expiresAt ? formValues.expiresAt.format('YYYY-MM-DDTHH:mm:ss') : undefined,
      };
      await onSubmit(submitData);
      form.resetFields();
    } catch (error) {
      console.error('表单验证失败', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.apiKey.modal.update.title', defaultMessage: '编辑 API Key' }),
        subtitle: intl.formatMessage({
          id: 'pages.apiKey.modal.update.subtitle',
          defaultMessage: '修改 API Key 配置，保存后即时生效',
        }),
        icon: <KeyOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
      }}
    >
      <Form form={form} layout="horizontal" labelCol={{ span: 6 }} wrapperCol={{ span: 18 }}>
        <Form.Item
          label={intl.formatMessage({ id: 'pages.apiKey.form.label.name', defaultMessage: '名称' })}
          name="name"
        >
          <Input disabled />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.apiKey.form.label.scopes', defaultMessage: '权限范围' })}
          name="scopes"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.apiKey.form.rule.required.scopes', defaultMessage: '请至少选择一项权限' }) }]}
        >
          <Checkbox.Group options={SCOPE_OPTIONS} />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.apiKey.form.label.tenantId', defaultMessage: '租户 ID' })}
          name="tenantId"
        >
          <InputNumber style={{ width: '100%' }} placeholder={intl.formatMessage({ id: 'pages.apiKey.form.placeholder.tenantId', defaultMessage: '可选，留空为全局' })} min={1} />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.apiKey.form.label.rateLimit', defaultMessage: '限流' })}
          name="rateLimit"
        >
          <InputNumber style={{ width: '100%' }} min={1} max={10000} addonAfter={intl.formatMessage({ id: 'pages.apiKey.form.rateLimit.unit', defaultMessage: '次/分钟' })} />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.apiKey.form.label.expiresAt', defaultMessage: '过期时间' })}
          name="expiresAt"
        >
          <DatePicker showTime style={{ width: '100%' }} placeholder={intl.formatMessage({ id: 'pages.apiKey.form.placeholder.expiresAt', defaultMessage: '可选，留空为永不过期' })} />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.apiKey.form.label.enabled', defaultMessage: '启用' })}
          name="enabled"
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>

        <Form.Item wrapperCol={{ span: 24 }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 24, paddingTop: 20, borderTop: '1px solid var(--vip-border, #e8e8e8)' }}>
            <Button onClick={() => form.resetFields()}>{intl.formatMessage({ id: 'pages.common.reset', defaultMessage: '重置' })}</Button>
            <Button type="primary" onClick={handleSubmit} loading={loading}>{intl.formatMessage({ id: 'pages.common.submit', defaultMessage: '提交' })}</Button>
          </div>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default UpdateForm;
