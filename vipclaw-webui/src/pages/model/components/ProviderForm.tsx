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

// 国际化服务商选项
const getProviderLabel = (intl: any, label: string) => {
  const providerMap: Record<string, string> = {
    'DashScope（阿里云）': 'DashScope (Alibaba Cloud)',
    'OpenAI': 'OpenAI',
    'Ollama（本地）': 'Ollama (Local)',
  };
  return intl.formatMessage({ 
    id: `pages.model.provider${label.split('（')[0]}`, 
    defaultMessage: providerMap[label] || label 
  });
};

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
          label={intl.formatMessage({ id: 'pages.model.providerType', defaultMessage: 'Provider Type' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'pages.model.providerType', defaultMessage: 'Provider Type' }) }]}
        >
          <Select
            placeholder={intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'pages.model.providerType', defaultMessage: 'Provider Type' })}
            options={PROVIDER_OPTIONS.map(opt => ({
              label: getProviderLabel(intl, opt.label),
              value: opt.value,
            }))}
            disabled={!!values}
          />
        </Form.Item>

        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.common.name', defaultMessage: 'Name' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + intl.formatMessage({ id: 'pages.common.name', defaultMessage: 'Name' }) }]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + intl.formatMessage({ id: 'pages.common.name', defaultMessage: 'Name' })} />
        </Form.Item>

        <Form.Item
          name="apiKey"
          label={intl.formatMessage({ id: 'pages.model.apiKey', defaultMessage: 'API Key' })}
          extra={values ? intl.formatMessage({ id: 'pages.model.apiKeyHint', defaultMessage: 'Leave blank to keep unchanged' }) : ''}
        >
          <Input.Password placeholder={intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + ' API Key'} />
        </Form.Item>

        <Form.Item
          name="baseUrl"
          label={intl.formatMessage({ id: 'pages.model.apiUrl', defaultMessage: 'API URL' })}
          rules={[
            { 
              type: 'url', 
              message: intl.formatMessage({ id: 'pages.model.invalidUrl', defaultMessage: 'Please enter a valid URL' }),
              transform: (value) => value && value.trim() !== '' ? value : undefined
            }
          ]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.placeholder.example', defaultMessage: 'e.g.: ' }) + 'https://dashscope.aliyuncs.com/compatible-mode/v1'} />
        </Form.Item>

        <Form.Item
          name="isPublic"
          label={intl.formatMessage({ id: 'pages.model.isPublic', defaultMessage: 'Is Public' })}
          valuePropName="checked"
          initialValue={false}
          extra={
            isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate) && !isCreate
              ? intl.formatMessage({ id: 'pages.model.noPermission', defaultMessage: 'You do not have permission to modify this setting' })
              : intl.formatMessage({ id: 'pages.model.providerPublicHint', defaultMessage: 'Other users can view this provider after making it public' })
          }
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.model.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.model.private', defaultMessage: 'Private' })}
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate)}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default ProviderForm;
