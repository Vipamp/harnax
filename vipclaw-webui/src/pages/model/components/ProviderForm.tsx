import React from 'react';
import { useIntl } from '@umijs/max';
import { Modal, Form, Input, Select, Switch, Typography } from 'antd';
import { createModelProvider, updateModelProvider } from '@/services/ant-design-pro/modelProvider';
import { message } from 'antd';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';

const { Text } = Typography;

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
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = React.useState(false);
  const { username, isAdmin } = getCurrentUserInfo();
  const isCreate = !values;

  React.useEffect(() => {
    if (visible) {
      if (values) {
        form.setFieldsValue({
          type: values.type,
          name: values.name,
          apiKey: '', // API Key 不回显
          baseUrl: values.baseUrl,
          isPublic: values.isPublic === 1,
        });
      } else {
        form.resetFields();
        form.setFieldsValue({
          isPublic: true,
        });
      }
    }
  }, [visible, values, form]);

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);

      if (values) {
        // 更新
        const response = await updateModelProvider(values.id, {
          type: formValues.type,
          name: formValues.name,
          apiKey: formValues.apiKey || undefined,
          baseUrl: formValues.baseUrl,
          isPublic: formValues.isPublic ? 1 : 0,
        });
        
        if (response.code === 200) {
          message.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
          onSuccess();
        } else {
          const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' });
          message.error(errorMsg);
        }
      } else {
        // 创建
        const response = await createModelProvider({
          type: formValues.type,
          name: formValues.name,
          apiKey: formValues.apiKey || undefined,
          baseUrl: formValues.baseUrl,
          isPublic: formValues.isPublic ? 1 : 0,
        });
        
        if (response.code === 200) {
          message.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
          onSuccess();
        } else {
          const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' });
          message.error(errorMsg);
        }
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || (values ? intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' }) : intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' }));
      message.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={values ? intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' }) + intl.formatMessage({ id: 'pages.model.provider', defaultMessage: 'Provider' }) : intl.formatMessage({ id: 'pages.common.add', defaultMessage: 'Add' }) + intl.formatMessage({ id: 'pages.model.provider', defaultMessage: 'Provider' })}
      open={visible}
      onCancel={onCancel}
      onOk={handleSubmit}
      confirmLoading={loading}
      destroyOnClose
      width={500}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="type"
          label="服务商类型"
          rules={[{ required: true, message: '请选择服务商类型' }]}
        >
          <Select
            placeholder="请选择服务商类型"
            options={PROVIDER_OPTIONS}
            disabled={!!values}
          />
        </Form.Item>

        <Form.Item
          name="name"
          label="名称"
          rules={[{ required: true, message: '请输入名称' }]}
        >
          <Input placeholder="请输入名称" />
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
          rules={[
            { 
              type: 'url', 
              message: '请输入有效的 URL 地址',
              transform: (value) => value && value.trim() !== '' ? value : undefined
            }
          ]}
        >
          <Input placeholder="如：https://dashscope.aliyuncs.com/compatible-mode/v1" />
        </Form.Item>

        <Form.Item
          name="isPublic"
          label="是否公开"
          valuePropName="checked"
          initialValue={false}
          extra={
            isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate) && !isCreate
              ? '您没有权限修改此设置'
              : '公开后其他用户也可以查看此服务商'
          }
        >
          <Switch
            checkedChildren="公开"
            unCheckedChildren="私有"
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate)}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default ProviderForm;
