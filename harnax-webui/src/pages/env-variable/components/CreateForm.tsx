import React, { useState } from 'react';
import { Button, Form, Input, Switch } from 'antd';
import { useIntl } from '@umijs/max';
import { SettingOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';

export interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
}

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [enabled, setEnabled] = useState<boolean>(true);

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="sm"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.env.create', defaultMessage: 'Create Env Variable' }),
        subtitle: intl.formatMessage({ id: 'pages.env.create.subtitle', defaultMessage: 'Add a new environment variable' }),
        icon: <SettingOutlined />,
      }}
    >
      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        style={{ marginTop: 12 }}
        onFinish={(formValues) => {
          onSubmit({ ...formValues, enabled: enabled ? 1 : 0 });
        }}
      >
        <Form.Item
          name="envKey"
          label={intl.formatMessage({ id: 'pages.env.key', defaultMessage: 'Key' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.env.keyRequired', defaultMessage: 'Please enter the key' }) },
            { max: 200, message: intl.formatMessage({ id: 'pages.env.keyMax', defaultMessage: 'Key cannot exceed 200 characters' }) },
            {
              pattern: /^[A-Za-z_][A-Za-z0-9_]*$/,
              message: intl.formatMessage({
                id: 'pages.env.keyPattern',
                defaultMessage: 'Key must start with a letter or underscore, containing only letters, digits, and underscores',
              }),
            },
          ]}
        >
          <Input
            placeholder={intl.formatMessage({ id: 'pages.env.keyPlaceholder', defaultMessage: 'e.g. API_KEY, DATABASE_URL' })}
          />
        </Form.Item>

        <Form.Item
          name="envValue"
          label={intl.formatMessage({ id: 'pages.env.value', defaultMessage: 'Value' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.env.valueRequired', defaultMessage: 'Please enter the value' }) },
          ]}
        >
          <Input.TextArea
            rows={2}
            placeholder={intl.formatMessage({ id: 'pages.env.valuePlaceholder', defaultMessage: 'Enter the value' })}
            maxLength={8192}
          />
        </Form.Item>

        <Form.Item
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <Input.TextArea
            rows={2}
            placeholder={intl.formatMessage({ id: 'pages.env.descriptionPlaceholder', defaultMessage: 'Describe the purpose (optional)' })}
            maxLength={500}
          />
        </Form.Item>

        <Form.Item
          name="sensitive"
          label={intl.formatMessage({ id: 'pages.env.sensitive', defaultMessage: 'Sensitive' })}
          valuePropName="checked"
          initialValue={false}
          extra={intl.formatMessage({ id: 'pages.env.sensitiveExtra', defaultMessage: 'Sensitive values will be masked in display' })}
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.yes', defaultMessage: 'Yes' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.no', defaultMessage: 'No' })}
          />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.env.enabled', defaultMessage: 'Enabled' })}
          extra={intl.formatMessage({
            id: 'pages.env.enabledExtra',
            defaultMessage: 'Disabled variables will not be available to agents',
          })}
        >
          <Switch
            checked={enabled}
            onChange={setEnabled}
            checkedChildren={intl.formatMessage({ id: 'pages.common.yes', defaultMessage: 'Yes' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.no', defaultMessage: 'No' })}
          />
        </Form.Item>

        <Form.Item>
          <Button onClick={() => form.resetFields()}>
            {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
          </Button>
          <Button type="primary" onClick={() => form.submit()}>
            {intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' })}
          </Button>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default CreateForm;
