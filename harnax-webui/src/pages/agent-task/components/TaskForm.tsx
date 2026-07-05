import React, { useEffect, useState } from 'react';
import { Form, Input, Select, InputNumber, Switch, message, Button, Row, Col, Divider, Space } from 'antd';
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
      message.error(intl.formatMessage({ id: 'pages.agentTask.loadAgentsFailed', defaultMessage: 'Failed to load agents' }));
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
        message.success(values
          ? intl.formatMessage({ id: 'pages.agentTask.updateSuccess', defaultMessage: 'Update successful' })
          : intl.formatMessage({ id: 'pages.agentTask.createSuccess', defaultMessage: 'Create successful' }));
        onSuccess();
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.operationFailed', defaultMessage: 'Operation failed' }));
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.agentTask.operationFailed', defaultMessage: 'Operation failed' });
      message.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  const cronPresets = [
    { label: intl.formatMessage({ id: 'pages.agentTask.cron.preset.every5min', defaultMessage: 'Every 5 minutes' }), value: '0 */5 * * * ?' },
    { label: intl.formatMessage({ id: 'pages.agentTask.cron.preset.everyHour', defaultMessage: 'Every hour' }), value: '0 0 * * * ?' },
    { label: intl.formatMessage({ id: 'pages.agentTask.cron.preset.everyDay9', defaultMessage: 'Every day at 9:00' }), value: '0 0 9 * * ?' },
    { label: intl.formatMessage({ id: 'pages.agentTask.cron.preset.everyMonday9', defaultMessage: 'Every Monday at 9:00' }), value: '0 0 9 ? * MON' },
    { label: intl.formatMessage({ id: 'pages.agentTask.cron.preset.everyDay0', defaultMessage: 'Every day at 0:00' }), value: '0 0 0 * * ?' },
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
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
      >
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.agentTask.name', defaultMessage: 'Task Name' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agentTask.name.required', defaultMessage: 'Please enter task name' }) }]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.agentTask.name.placeholder', defaultMessage: 'e.g., Daily News Summary' })} />
        </Form.Item>

        <Form.Item
          name="agentId"
          label={intl.formatMessage({ id: 'pages.agentTask.agent', defaultMessage: 'Agent' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agentTask.agent.required', defaultMessage: 'Please select an agent' }) }]}
        >
          <Select
            placeholder={intl.formatMessage({ id: 'pages.agentTask.agent.placeholder', defaultMessage: 'Select an agent' })}
            showSearch
            optionFilterProp="children"
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
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agentTask.prompt.required', defaultMessage: 'Please enter prompt' }) }]}
        >
          <TextArea
            rows={3}
            placeholder={intl.formatMessage({ id: 'pages.agentTask.prompt.placeholder', defaultMessage: 'Enter the task to be executed by the agent...' })}
          />
        </Form.Item>

        <Form.Item
          name="cronExpression"
          label={intl.formatMessage({ id: 'pages.agentTask.cron', defaultMessage: 'Cron Expression' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agentTask.cron.required', defaultMessage: 'Please enter cron expression' }) }]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.agentTask.cron.placeholder', defaultMessage: '0 0 9 * * ?' })} />
        </Form.Item>

        <Row>
          <Col span={6} />
          <Col span={18}>
            <div style={{ marginTop: -8, marginBottom: 12 }}>
              <span style={{ fontSize: '11px', color: '#999', marginRight: 6 }}>
                {intl.formatMessage({ id: 'pages.agentTask.cron.presets', defaultMessage: 'Presets:' })}
              </span>
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
          </Col>
        </Row>

        <Row gutter={16}>
          <Col span={12}>
            <Form.Item
              name="timeoutSeconds"
              label={intl.formatMessage({ id: 'pages.agentTask.timeout', defaultMessage: 'Timeout (s)' })}
              labelCol={{ span: 12 }}
              wrapperCol={{ span: 12 }}
            >
              <InputNumber min={30} max={3600} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="concurrent"
              label={intl.formatMessage({ id: 'pages.agentTask.concurrent', defaultMessage: 'Concurrent' })}
              valuePropName="checked"
              labelCol={{ span: 12 }}
              wrapperCol={{ span: 12 }}
            >
              <Switch />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <TextArea
            rows={2}
            placeholder={intl.formatMessage({ id: 'pages.agentTask.description.placeholder', defaultMessage: 'Optional description' })}
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

        <Divider style={{ margin: '16px 0 12px' }} />

        <Row>
          <Col span={6} />
          <Col span={18}>
            <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <Button onClick={onCancel}>
                {intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
              </Button>
              <Button type="primary" onClick={handleSubmit} loading={loading}>
                {intl.formatMessage({ id: 'pages.common.submit', defaultMessage: 'Submit' })}
              </Button>
            </Space>
          </Col>
        </Row>
      </Form>
    </FormModal>
  );
};

export default TaskForm;
