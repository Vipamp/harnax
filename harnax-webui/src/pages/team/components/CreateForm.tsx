import React, { useEffect, useState } from 'react';
import { Button, Form, Input, Select, Switch } from 'antd';
import { useIntl } from '@umijs/max';
import { TeamOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import MembersField from './MembersField';

export interface TeamCreateFormProps {
  visible: boolean;
  agents: API.AgentItem[];
  onCancel: () => void;
  onSubmit: (values: API.TeamCreateRequest) => Promise<void>;
}

const TeamCreateForm: React.FC<TeamCreateFormProps> = ({ visible, agents, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const leadAgentId = Form.useWatch('leadAgentId', form);

  useEffect(() => {
    if (visible) {
      form.resetFields();
    }
  }, [visible, form]);

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.team.create', defaultMessage: 'Create Team' }),
        subtitle: intl.formatMessage({
          id: 'pages.team.create.subtitle',
          defaultMessage: 'One lead agent orchestrates several existing agents',
        }),
        icon: <TeamOutlined />,
      }}
    >
      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 5 }}
        wrapperCol={{ span: 19 }}
        style={{ marginTop: 12 }}
        initialValues={{ members: [{}], status: 1, isPublic: 0 }}
        onFinish={async (formValues) => {
          setSubmitting(true);
          try {
            await onSubmit({
              name: formValues.name,
              description: formValues.description,
              leadAgentId: formValues.leadAgentId,
              instructions: formValues.instructions,
              members: (formValues.members || []).map((m: any) => ({
                agentId: m.agentId,
                delegationDescription: m.delegationDescription,
              })),
              status: formValues.status ? 1 : 0,
              isPublic: formValues.isPublic ? 1 : 0,
            });
          } finally {
            setSubmitting(false);
          }
        }}
      >
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.team.name', defaultMessage: 'Team Name' })}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.team.nameRequired',
                defaultMessage: 'Please enter the team name',
              }),
            },
            {
              max: 100,
              message: intl.formatMessage({
                id: 'pages.team.nameMax',
                defaultMessage: 'Team name cannot exceed 100 characters',
              }),
            },
          ]}
        >
          <Input
            placeholder={intl.formatMessage({
              id: 'pages.team.namePlaceholder',
              defaultMessage: 'e.g. Research report team',
            })}
          />
        </Form.Item>

        <Form.Item name="description" label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}>
          <Input.TextArea
            rows={2}
            maxLength={500}
            placeholder={intl.formatMessage({
              id: 'pages.team.descriptionPlaceholder',
              defaultMessage: 'What this team is for (optional)',
            })}
          />
        </Form.Item>

        <Form.Item
          name="leadAgentId"
          label={intl.formatMessage({ id: 'pages.team.leadAgent', defaultMessage: 'Lead Agent' })}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.team.leadAgentRequired',
                defaultMessage: 'Please select the lead agent',
              }),
            },
          ]}
          extra={intl.formatMessage({
            id: 'pages.team.leadAgentExtra',
            defaultMessage: 'The lead only delegates and summarizes; its own tools stay as configured',
          })}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder={intl.formatMessage({
              id: 'pages.team.leadAgentPlaceholder',
              defaultMessage: 'Select lead agent',
            })}
            options={agents.map((agent) => ({
              label: `${agent.name}${agent.description ? ` - ${agent.description}` : ''}`,
              value: agent.id,
            }))}
          />
        </Form.Item>

        <MembersField agents={agents} leadAgentId={leadAgentId} />

        <Form.Item
          name="instructions"
          label={intl.formatMessage({ id: 'pages.team.instructions', defaultMessage: 'Orchestration Notes' })}
          rules={[
            {
              max: 2000,
              message: intl.formatMessage({
                id: 'pages.team.instructionsMax',
                defaultMessage: 'Instructions cannot exceed 2000 characters',
              }),
            },
          ]}
        >
          <Input.TextArea
            rows={2}
            maxLength={2000}
            placeholder={intl.formatMessage({
              id: 'pages.team.instructionsPlaceholder',
              defaultMessage: 'Extra guidance appended to the lead prompt (optional)',
            })}
          />
        </Form.Item>

        <Form.Item
          name="status"
          label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
          valuePropName="checked"
          extra={intl.formatMessage({
            id: 'pages.team.statusExtra',
            defaultMessage: 'A disabled team cannot open new sessions',
          })}
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.enable', defaultMessage: 'Enabled' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.disable', defaultMessage: 'Disabled' })}
          />
        </Form.Item>

        <Form.Item
          name="isPublic"
          label={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
          valuePropName="checked"
          extra={intl.formatMessage({
            id: 'pages.team.publicExtra',
            defaultMessage: 'Public teams can be used by other users in this tenant',
          })}
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
          />
        </Form.Item>

        <Form.Item wrapperCol={{ offset: 5, span: 19 }}>
          <Button onClick={() => form.resetFields()}>
            {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
          </Button>
          <Button type="primary" htmlType="submit" loading={submitting} style={{ marginLeft: 8 }}>
            {intl.formatMessage({ id: 'pages.common.create', defaultMessage: 'Create' })}
          </Button>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default TeamCreateForm;
