import React, { useState } from 'react';
import { Button, Switch, Input, Form, Select, InputNumber } from 'antd';
import { useIntl } from '@umijs/max';
import { ToolOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import ConfigEntriesEditor from '@/pages/mcp/components/ConfigEntriesEditor';

export interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
}

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [toolType, setToolType] = useState<string>('BUILTIN');
  const [form] = Form.useForm();
  const [status, setStatus] = useState<number>(1);

  const toolTypeOptions = [
    { label: intl.formatMessage({ id: 'pages.tool.type.builtin', defaultMessage: 'BUILTIN' }), value: 'BUILTIN' },
    { label: intl.formatMessage({ id: 'pages.tool.type.custom', defaultMessage: 'CUSTOM' }), value: 'CUSTOM' },
    { label: intl.formatMessage({ id: 'pages.tool.type.http', defaultMessage: 'HTTP' }), value: 'HTTP' },
  ];

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.tool.create', defaultMessage: 'Create Tool' }),
        subtitle: intl.formatMessage({ id: 'pages.tool.create.subtitle', defaultMessage: 'Configure tool parameters and settings' }),
        icon: <ToolOutlined />,
      }}
    >
      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        style={{ marginTop: 12 }}
        onFinish={(values) => onSubmit({ ...values, status })}
      >
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.tool.name', defaultMessage: 'Tool Name' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.tool.nameRequired', defaultMessage: 'Please enter tool name' }) },
            { max: 100, message: intl.formatMessage({ id: 'pages.tool.nameMax', defaultMessage: 'Name cannot exceed 100 characters' }) },
          ]}
        >
          <Input
            placeholder={intl.formatMessage({ id: 'pages.tool.namePlaceholder', defaultMessage: 'Enter tool name, e.g.: web-search' })}
          />
        </Form.Item>

        <Form.Item
          name="displayName"
          label={intl.formatMessage({ id: 'pages.tool.displayName', defaultMessage: 'Display Name' })}
        >
          <Input
            placeholder={intl.formatMessage({ id: 'pages.tool.displayNamePlaceholder', defaultMessage: 'Enter display name (optional)' })}
          />
        </Form.Item>

        <Form.Item
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <Input.TextArea
            rows={3}
            placeholder={intl.formatMessage({ id: 'pages.tool.descriptionPlaceholder', defaultMessage: 'Enter tool description (optional)' })}
            maxLength={500}
          />
        </Form.Item>

        <Form.Item
          name="type"
          label={intl.formatMessage({ id: 'pages.tool.type', defaultMessage: 'Type' })}
          initialValue="BUILTIN"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.tool.typeRequired', defaultMessage: 'Please select tool type' }) }]}
        >
          <Select
            placeholder={intl.formatMessage({ id: 'pages.tool.typePlaceholder', defaultMessage: 'Please select tool type' })}
            onChange={(val: string) => {
              setToolType(val);
              if (val === 'BUILTIN' || val === 'CUSTOM') {
                form.setFieldsValue({
                  httpUrl: undefined,
                  httpMethod: undefined,
                  httpHeaders: undefined,
                  inputSchema: undefined,
                });
              }
            }}
            options={toolTypeOptions}
          />
        </Form.Item>

        {(toolType === 'BUILTIN' || toolType === 'CUSTOM') && (
          <Form.Item
            name="beanName"
            label={intl.formatMessage({ id: 'pages.tool.beanName', defaultMessage: 'Bean Name' })}
          >
            <Input
              placeholder={intl.formatMessage({ id: 'pages.tool.beanNamePlaceholder', defaultMessage: 'Enter Spring bean name' })}
            />
          </Form.Item>
        )}

        {toolType === 'HTTP' && (
          <>
            <Form.Item
              name="httpUrl"
              label={intl.formatMessage({ id: 'pages.tool.httpUrl', defaultMessage: 'HTTP URL' })}
              rules={[
                { required: true, message: intl.formatMessage({ id: 'pages.tool.httpUrlRequired', defaultMessage: 'HTTP type requires service URL' }) },
                { type: 'url', message: intl.formatMessage({ id: 'pages.tool.httpUrlInvalid', defaultMessage: 'Please enter correct URL format' }) },
              ]}
            >
              <Input
                placeholder={intl.formatMessage({ id: 'pages.tool.httpUrlPlaceholder', defaultMessage: 'e.g.: http://localhost:8080/api/tool' })}
              />
            </Form.Item>

            <Form.Item
              name="httpMethod"
              label={intl.formatMessage({ id: 'pages.tool.httpMethod', defaultMessage: 'HTTP Method' })}
              initialValue="POST"
            >
              <Select
                options={[
                  { label: 'GET', value: 'GET' },
                  { label: 'POST', value: 'POST' },
                  { label: 'PUT', value: 'PUT' },
                  { label: 'DELETE', value: 'DELETE' },
                ]}
              />
            </Form.Item>

            <Form.Item
              name="httpHeaders"
              label={intl.formatMessage({ id: 'pages.tool.httpHeaders', defaultMessage: 'HTTP Headers' })}
              extra={intl.formatMessage({ id: 'pages.tool.httpHeadersExtra', defaultMessage: 'HTTP request header configuration, e.g. auth token' })}
            >
              <ConfigEntriesEditor
                placeholder={{ key: 'Authorization', value: 'Bearer sk-xxx' }}
              />
            </Form.Item>

            <Form.Item
              name="inputSchema"
              label={intl.formatMessage({ id: 'pages.tool.inputSchema', defaultMessage: 'Input Schema' })}
              extra={intl.formatMessage({ id: 'pages.tool.inputSchemaExtra', defaultMessage: 'JSON Schema for tool input parameters' })}
            >
              <Input.TextArea
                rows={4}
                placeholder={intl.formatMessage({ id: 'pages.tool.inputSchemaPlaceholder', defaultMessage: 'Enter JSON Schema, e.g.: {"type": "object", "properties": {...}}' })}
              />
            </Form.Item>
          </>
        )}

        <Form.Item
          name="needConfirm"
          label={intl.formatMessage({ id: 'pages.tool.needConfirm', defaultMessage: 'Need Confirm' })}
          valuePropName="checked"
          initialValue={false}
          extra={intl.formatMessage({ id: 'pages.tool.needConfirmExtra', defaultMessage: 'Require user confirmation before tool execution' })}
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.yes', defaultMessage: 'Yes' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.no', defaultMessage: 'No' })}
          />
        </Form.Item>

        <Form.Item
          name="readOnly"
          label={intl.formatMessage({ id: 'pages.tool.readOnly', defaultMessage: 'Read Only' })}
          valuePropName="checked"
          initialValue={false}
          extra={intl.formatMessage({ id: 'pages.tool.readOnlyExtra', defaultMessage: 'Mark this tool as read-only (no side effects)' })}
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.yes', defaultMessage: 'Yes' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.no', defaultMessage: 'No' })}
          />
        </Form.Item>

        <Form.Item
          name="timeoutSeconds"
          label={intl.formatMessage({ id: 'pages.tool.timeoutSeconds', defaultMessage: 'Timeout (s)' })}
          initialValue={30}
        >
          <InputNumber
            min={1}
            max={300}
            style={{ width: '100%' }}
            placeholder={intl.formatMessage({ id: 'pages.tool.timeoutSecondsPlaceholder', defaultMessage: 'Execution timeout in seconds (default: 30)' })}
          />
        </Form.Item>

        <Form.Item
          name="envs"
          label={intl.formatMessage({ id: 'pages.tool.envs', defaultMessage: 'Environment Variables' })}
          extra={intl.formatMessage({
            id: 'pages.tool.envsExtra',
            defaultMessage: 'Environment variables available to the tool at runtime',
          })}
        >
          <ConfigEntriesEditor placeholder={{ key: 'API_KEY', value: 'sk-xxx' }} />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
        >
          <Switch
            checked={status === 1}
            onChange={(checked) => setStatus(checked ? 1 : 0)}
            checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
          />
        </Form.Item>

        {/* 按钮区域 */}
        <Form.Item>
          <Button
            onClick={() => { form.resetFields(); setStatus(1); }}
          >
            {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
          </Button>
          <Button
            type="primary"
            onClick={() => form.submit()}
          >
            {intl.formatMessage({ id: 'pages.tool.submit', defaultMessage: 'Submit' })}
          </Button>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default CreateForm;
