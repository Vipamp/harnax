import React from 'react';
import { useIntl } from '@umijs/max';
import { Form, Input, Select, Switch, Button, message } from 'antd';
import { createModelProvider, updateModelProvider } from '@/services/ant-design-pro/modelProvider';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { ApartmentOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';

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

  const handleReset = () => {
    if (values) {
      form.setFieldsValue({
        type: values.type,
        name: values.name,
        apiKey: '',
        baseUrl: values.baseUrl,
        isPublic: values.isPublic === 1,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({
        isPublic: true,
      });
    }
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: isCreate
          ? intl.formatMessage({ id: 'pages.model.provider.create', defaultMessage: 'Create Provider' })
          : intl.formatMessage({ id: 'pages.model.provider.edit', defaultMessage: 'Edit Provider' }),
        subtitle: isCreate
          ? intl.formatMessage({ id: 'pages.model.provider.create.subtitle', defaultMessage: 'Configure model provider connection and API settings' })
          : intl.formatMessage({ id: 'pages.model.provider.edit.subtitle', defaultMessage: 'Modify provider configuration, changes take effect immediately' }),
        icon: <ApartmentOutlined />,
        iconGradient: isCreate
          ? 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)'
          : 'linear-gradient(135deg, var(--vip-warning) 0%, var(--vip-warning-light) 100%)',
        iconShadowColor: isCreate
          ? 'rgba(79, 110, 247, 0.25)'
          : 'rgba(250, 173, 20, 0.25)',
      }}
    >
      <Form 
        form={form} 
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
      >
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
          <Input placeholder={intl.formatMessage({ id: 'pages.model.provider.name.placeholder', defaultMessage: 'Please enter provider name' })} />
        </Form.Item>

        <Form.Item
          name="apiKey"
          label={intl.formatMessage({ id: 'pages.model.apiKey', defaultMessage: 'API Key' })}
          extra={values ? intl.formatMessage({ id: 'pages.model.apiKeyHint', defaultMessage: 'Leave blank to keep unchanged' }) : ''}
        >
          <Input.Password placeholder={intl.formatMessage({ id: 'pages.model.apiKey.placeholder', defaultMessage: 'Please enter API Key' })} />
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
          <Input placeholder={intl.formatMessage({ id: 'pages.model.apiUrl.placeholder', defaultMessage: 'e.g.: https://dashscope.aliyuncs.com/compatible-mode/v1' })} />
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
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate)}
          />
        </Form.Item>

        {/* 按钮区域 */}
        <Form.Item wrapperCol={{ span: 24 }} style={{ marginBottom: 0 }}>
          <div style={{ 
            display: 'flex', 
            justifyContent: 'flex-end', 
            gap: '10px',
            marginTop: '12px',
            paddingTop: '10px',
            paddingLeft: '168px',  // 与表单项保持一致的左侧间距
            borderTop: '1px solid var(--vip-border)'
          }}>
            <Button 
              onClick={handleReset}
              style={{
                fontSize: '12px',
                fontWeight: 500,
                height: '32px',
                padding: '4px 20px',
                borderRadius: '6px',
              }}
            >
              {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
            </Button>
            <Button 
              type="primary" 
              onClick={handleSubmit}
              loading={loading}
              style={{
                fontSize: '12px',
                fontWeight: 500,
                height: '32px',
                padding: '4px 20px',
                borderRadius: '6px',
              }}
            >
              {intl.formatMessage({ id: 'pages.common.submit', defaultMessage: 'Submit' })}
            </Button>
          </div>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default ProviderForm;
