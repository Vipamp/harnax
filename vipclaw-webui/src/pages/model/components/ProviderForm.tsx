import React from 'react';
import { Modal, Form, Input, Select } from 'antd';
import { createModelProvider, updateModelProvider } from '@/services/ant-design-pro/modelProvider';
import { message } from 'antd';

interface ProviderFormProps {
  visible: boolean;
  values: API.ModelProviderItem | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const PROVIDER_OPTIONS = [
  { label: 'DashScope（阿里云）', value: 'dashscope' },
  { label: 'OpenAI', value: 'openai' },
  { label: 'Ollama（本地）', value: 'ollama' },
];

const ProviderForm: React.FC<ProviderFormProps> = ({ visible, values, onCancel, onSuccess }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = React.useState(false);

  React.useEffect(() => {
    if (visible) {
      if (values) {
        form.setFieldsValue({
          name: values.name,
          displayName: values.displayName,
          apiKey: '', // API Key 不回显
          baseUrl: values.baseUrl,
          status: values.status,
        });
      } else {
        form.resetFields();
      }
    }
  }, [visible, values, form]);

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);

      if (values) {
        // 更新
        await updateModelProvider(values.id, {
          displayName: formValues.displayName,
          apiKey: formValues.apiKey || undefined,
          baseUrl: formValues.baseUrl,
          status: formValues.status,
        });
        message.success('更新成功');
      } else {
        // 创建
        await createModelProvider(formValues);
        message.success('创建成功');
      }

      onSuccess();
    } catch (error) {
      message.error(values ? '更新失败' : '创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={values ? '编辑服务商' : '新增服务商'}
      open={visible}
      onCancel={onCancel}
      onOk={handleSubmit}
      confirmLoading={loading}
      destroyOnClose
      width={500}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="name"
          label="服务商名称"
          rules={[{ required: true, message: '请选择服务商' }]}
        >
          <Select
            placeholder="请选择服务商"
            options={PROVIDER_OPTIONS}
            disabled={!!values}
          />
        </Form.Item>

        <Form.Item
          name="displayName"
          label="显示名称"
          rules={[{ required: true, message: '请输入显示名称' }]}
        >
          <Input placeholder="请输入显示名称" />
        </Form.Item>

        <Form.Item
          name="apiKey"
          label="API Key"
          extra={values ? '留空表示不修改' : ''}
        >
          <Input.Password placeholder="请输入 API Key" />
        </Form.Item>

        <Form.Item
          name="baseUrl"
          label="API 地址"
        >
          <Input placeholder="如：https://dashscope.aliyuncs.com/compatible-mode/v1" />
        </Form.Item>

        <Form.Item
          name="status"
          label="状态"
          initialValue={1}
        >
          <Select
            options={[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default ProviderForm;
