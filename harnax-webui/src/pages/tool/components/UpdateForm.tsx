import React, { useState, useEffect } from 'react';
import { Button, Switch, Input, Form, Select, InputNumber, message } from 'antd';
import { useIntl } from '@umijs/max';
import { ToolOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import ConfigEntriesEditor from '@/pages/mcp/components/ConfigEntriesEditor';
import ToolEnvEntriesEditor from './ToolEnvEntriesEditor';

export interface UpdateFormProps {
  visible: boolean;
  values: any;
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
}

const UpdateForm: React.FC<UpdateFormProps> = ({ visible, values, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [toolType, setToolType] = useState<string>(values?.type || 'BUILTIN');
  const [form] = Form.useForm();
  const [status, setStatus] = useState<number>(values?.status ?? 1);

  // 初始化表单数据
  useEffect(() => {
    if (visible && values?.id) {
      form.setFieldsValue({
        id: values.id,
        name: values.name,
        displayName: values.displayName,
        description: values.description,
        type: values.type,
        beanName: values.beanName,
        httpUrl: values.httpUrl,
        httpMethod: values.httpMethod || 'POST',
        httpHeaders: values.httpHeaders || [],
        envParams: values.envParams || [],
        inputSchema: values.inputSchema,
        needConfirm: values.needConfirm || false,
        readOnly: values.readOnly || false,
        timeoutSeconds: values.timeoutSeconds || 30,
      });
    }
  }, [visible, values]);

  // 当外部 values 变化时更新 toolType 和 status
  useEffect(() => {
    if (values?.type) {
      setToolType(values.type);
    }
    if (values?.status !== undefined) {
      setStatus(values.status);
    }
  }, [values]);

  const toolTypeOptions = [
    { label: intl.formatMessage({ id: 'pages.tool.type.builtin', defaultMessage: 'BUILTIN' }), value: 'BUILTIN' },
    { label: intl.formatMessage({ id: 'pages.tool.type.custom', defaultMessage: 'CUSTOM' }), value: 'CUSTOM' },
    { label: intl.formatMessage({ id: 'pages.tool.type.http', defaultMessage: 'HTTP' }), value: 'HTTP' },
  ];

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.tool.edit', defaultMessage: 'Edit Tool' }),
        subtitle: intl.formatMessage({ id: 'pages.tool.edit.subtitle', defaultMessage: 'Modify tool configuration, changes take effect immediately' }),
        icon: <ToolOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(79, 110, 247, 0.25)',
      }}
    >
      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        style={{ marginTop: 12 }}
        onFinish={(formValues) => {
          // Validate: required envs must have defaultValue
          const envParams: any[] = formValues.envParams || [];
          const invalidEnv = envParams.find((e: any) => e.required && !e.defaultValue?.trim());
          if (invalidEnv) {
            message.error(intl.formatMessage({
              id: 'pages.tool.envRequiredError',
              defaultMessage: 'Required environment parameter "{name}" must have a default value',
            }, { name: invalidEnv.envParamName || '' }));
            return;
          }
          onSubmit({
            ...formValues,
            needConfirm: formValues.needConfirm ? 1 : 0,
            readOnly: formValues.readOnly ? 1 : 0,
            status,
          });
        }}
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
            placeholder={intl.formatMessage({ id: 'pages.tool.namePlaceholder', defaultMessage: 'Enter tool name' })}
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
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.tool.typeRequired', defaultMessage: 'Please select tool type' }) }]}
        >
          <Select
            placeholder={intl.formatMessage({ id: 'pages.tool.typePlaceholder', defaultMessage: 'Please select tool type' })}
            onChange={(val: string) => setToolType(val)}
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
        >
          <InputNumber
            min={1}
            max={300}
            style={{ width: '100%' }}
            placeholder={intl.formatMessage({ id: 'pages.tool.timeoutSecondsPlaceholder', defaultMessage: 'Execution timeout in seconds (default: 30)' })}
          />
        </Form.Item>

        <Form.Item
          name="envParams"
          label={intl.formatMessage({ id: 'pages.tool.envParams', defaultMessage: 'Environment Parameters' })}
          extra={intl.formatMessage({
            id: 'pages.tool.envParamsExtra',
            defaultMessage: 'Environment parameters available to the tool at runtime',
          })}
        >
          <ToolEnvEntriesEditor />
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

export default UpdateForm;
