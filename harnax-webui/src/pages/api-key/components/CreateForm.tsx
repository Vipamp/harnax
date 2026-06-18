import React, { useState, useEffect } from 'react';
import { Form, Input, InputNumber, DatePicker, Checkbox, Button } from 'antd';
import { useIntl } from '@umijs/max';
import { KeyOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';

interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: API.ApiKeyCreateRequest) => Promise<void>;
}

const SCOPE_OPTIONS = [
  { label: '路由调用 (router:invoke)', value: 'router:invoke' },
  { label: '对话 API (api:chat)', value: 'api:chat' },
  { label: '会话 API (api:session)', value: 'api:session' },
];

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!visible) {
      form.resetFields();
    }
  }, [visible]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);
      const submitData: API.ApiKeyCreateRequest = {
        ...values,
        scopes: (values.scopes as string[]).join(','),
        expiresAt: values.expiresAt ? values.expiresAt.format('YYYY-MM-DDTHH:mm:ss') : undefined,
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
        mainTitle: intl.formatMessage({ id: 'pages.apiKey.modal.create.title', defaultMessage: '创建 API Key' }),
        subtitle: intl.formatMessage({
          id: 'pages.apiKey.modal.create.subtitle',
          defaultMessage: '创建一个新的 API Key 用于外部系统接入',
        }),
        icon: <KeyOutlined />,
      }}
    >
      <Form form={form} layout="horizontal" labelCol={{ span: 6 }} wrapperCol={{ span: 18 }} initialValues={{ rateLimit: 60 }}>
        <Form.Item
          label={intl.formatMessage({ id: 'pages.apiKey.form.label.name', defaultMessage: '名称' })}
          name="name"
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.apiKey.form.rule.required.name', defaultMessage: '请输入 API Key 名称' }) },
            { max: 128, message: intl.formatMessage({ id: 'pages.apiKey.form.rule.max.name', defaultMessage: '名称不超过 128 字符' }) },
          ]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.apiKey.form.placeholder.name', defaultMessage: '如：third-party-app' })} />
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

export default CreateForm;
