import React, { useEffect, useState } from 'react';
import { Form, Input, Select, InputNumber, Switch, message, Button } from 'antd';
import { createAgentTask, updateAgentTask, getAvailableAgents } from '@/services/ant-design-pro/agentTask';
import { useIntl } from '@umijs/max';
import { ScheduleOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';

const { TextArea } = Input;

interface TaskFormProps {
  visible: boolean;
  values: API.AgentTaskItem | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const TaskForm: React.FC<TaskFormProps> = ({ visible, values, onCancel, onSuccess }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [agents, setAgents] = useState<API.AgentOption[]>([]);
  const isCreate = !values;

  useEffect(() => {
    if (visible) {
      loadAgents();
      if (values) {
        form.setFieldsValue({
          ...values,
          concurrent: values.concurrent === 1,
          isPublic: values.isPublic === 1,
        });
      } else {
        form.resetFields();
        form.setFieldsValue({
          concurrent: false,
          timeoutSeconds: 300,
          isPublic: false,
        });
      }
    }
  }, [visible, values, form]);

  const loadAgents = async () => {
    try {
      const response = await getAvailableAgents();
      if (response.code === 200 && response.data) {
        setAgents(response.data);
      }
    } catch (error) {
      message.error('Failed to load agents');
    }
  };

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);

      const data = {
        ...formValues,
        concurrent: formValues.concurrent ? 1 : 0,
        isPublic: formValues.isPublic ? 1 : 0,
      };

      let response;
      if (values) {
        response = await updateAgentTask(values.id, data);
      } else {
        response = await createAgentTask(data);
      }

      if (response.code === 200) {
        message.success(values ? 'Update successful' : 'Create successful');
        onSuccess();
      } else {
        message.error(response.message || 'Operation failed');
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || 'Operation failed';
      message.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  const cronPresets = [
    { label: 'Every 5 minutes', value: '0 */5 * * * ?' },
    { label: 'Every hour', value: '0 0 * * * ?' },
    { label: 'Every day at 9:00', value: '0 0 9 * * ?' },
    { label: 'Every Monday at 9:00', value: '0 0 9 ? * MON' },
    { label: 'Every day at 0:00', value: '0 0 0 * * ?' },
  ];

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: isCreate
          ? intl.formatMessage({ id: 'pages.agentTask.create', defaultMessage: 'Create Agent Task' })
          : intl.formatMessage({ id: 'pages.agentTask.edit', defaultMessage: 'Edit Agent Task' }),
        subtitle: isCreate
          ? intl.formatMessage({ id: 'pages.agentTask.create.subtitle', defaultMessage: 'Configure scheduled agent execution' })
          : intl.formatMessage({ id: 'pages.agentTask.edit.subtitle', defaultMessage: 'Modify task configuration' }),
        icon: <ScheduleOutlined />,
      }}
    >
      <Form
        form={form}
        layout="vertical"
      >
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.agentTask.name', defaultMessage: 'Task Name' })}
          rules={[{ required: true, message: 'Please enter task name' }]}
        >
          <Input placeholder="e.g., Daily News Summary" style={{ fontSize: '12px' }} />
        </Form.Item>

        <Form.Item
          name="agentId"
          label={intl.formatMessage({ id: 'pages.agentTask.agent', defaultMessage: 'Agent' })}
          rules={[{ required: true, message: 'Please select an agent' }]}
        >
          <Select
            placeholder="Select an agent"
            showSearch
            optionFilterProp="children"
            style={{ fontSize: '12px' }}
          >
            {agents.map((agent) => (
              <Select.Option key={agent.id} value={agent.id}>
                {agent.name}
              </Select.Option>
            ))}
          </Select>
        </Form.Item>

        <Form.Item
          name="prompt"
          label={intl.formatMessage({ id: 'pages.agentTask.prompt', defaultMessage: 'Prompt' })}
          rules={[{ required: true, message: 'Please enter prompt' }]}
        >
          <TextArea
            rows={4}
            placeholder="Enter the task to be executed by the agent..."
            style={{ fontSize: '12px' }}
          />
        </Form.Item>

        <Form.Item
          name="cronExpression"
          label={intl.formatMessage({ id: 'pages.agentTask.cron', defaultMessage: 'Cron Expression' })}
          rules={[{ required: true, message: 'Please enter cron expression' }]}
          extra={
            <div style={{ marginTop: 4 }}>
              <span style={{ fontSize: '11px', color: '#888', marginRight: 8 }}>Presets:</span>
              {cronPresets.map((preset) => (
                <a
                  key={preset.value}
                  onClick={() => form.setFieldsValue({ cronExpression: preset.value })}
                  style={{ fontSize: '11px', marginRight: 8 }}
                >
                  {preset.label}
                </a>
              ))}
            </div>
          }
        >
          <Input placeholder="0 0 9 * * ?" style={{ fontSize: '12px' }} />
        </Form.Item>

        <div style={{ display: 'flex', gap: 16 }}>
          <Form.Item
            name="timeoutSeconds"
            label={intl.formatMessage({ id: 'pages.agentTask.timeout', defaultMessage: 'Timeout (s)' })}
            style={{ flex: 1 }}
          >
            <InputNumber min={30} max={3600} style={{ width: '100%', fontSize: '12px' }} />
          </Form.Item>

          <Form.Item
            name="concurrent"
            label={intl.formatMessage({ id: 'pages.agentTask.concurrent', defaultMessage: 'Concurrent' })}
            valuePropName="checked"
            style={{ flex: 1 }}
          >
            <Switch />
          </Form.Item>
        </div>

        <Form.Item
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <TextArea
            rows={2}
            placeholder="Optional description"
            style={{ fontSize: '12px' }}
          />
        </Form.Item>

        <Form.Item
          name="isPublic"
          label={intl.formatMessage({ id: 'pages.common.isPublic', defaultMessage: 'Public' })}
          valuePropName="checked"
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
          />
        </Form.Item>

        <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
          <Button onClick={onCancel} style={{ marginRight: 8 }}>
            {intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
          </Button>
          <Button type="primary" onClick={handleSubmit} loading={loading}>
            {intl.formatMessage({ id: 'pages.common.submit', defaultMessage: 'Submit' })}
          </Button>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default TaskForm;
