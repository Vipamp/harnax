import React, { useEffect, useState } from 'react';
import { Button, Form, Input, Select, Switch } from 'antd';
import { useIntl } from '@umijs/max';
import { TeamOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import MembersField from './MembersField';

export interface TeamUpdateFormProps {
  visible: boolean;
  values: API.TeamItem;
  agents: API.AgentItem[];
  onCancel: () => void;
  onSubmit: (values: API.TeamUpdateRequest) => Promise<void>;
}

const TeamUpdateForm: React.FC<TeamUpdateFormProps> = ({ visible, values, agents, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const leadAgentId = Form.useWatch('leadAgentId', form);

  useEffect(() => {
    if (visible && values) {
      form.setFieldsValue({
        name: values.name,
        description: values.description,
        leadAgentId: values.leadAgentId,
        instructions: values.instructions,
        isPublic: values.isPublic === 1,
        members: (values.memberList || []).map((member) => ({
          agentId: member.agentId,
          delegationDescription: member.delegationDescription,
        })),
      });
    }
  }, [visible, values, form]);

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.team.edit', defaultMessage: 'Edit Team' }),
        subtitle: intl.formatMessage({
          id: 'pages.team.edit.subtitle',
          defaultMessage: 'Running team sessions keep the previous members until refreshed',
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
        onFinish={async (formValues) => {
          setSubmitting(true);
          try {
            await onSubmit({
              name: formValues.name,
              description: formValues.description,
              leadAgentId: formValues.leadAgentId,
              instructions: formValues.instructions,
              isPublic: formValues.isPublic ? 1 : 0,
              // 成员整体替换：表单里删掉的行就是要在团队里消失
              members: (formValues.members || []).map((m: any) => ({
                agentId: m.agentId,
                delegationDescription: m.delegationDescription,
              })),
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
          <Input />
        </Form.Item>

        <Form.Item name="description" label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}>
          <Input.TextArea rows={2} maxLength={500} />
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
            options={agents.map((agent) => ({
              label: `${agent.name}${agent.description ? ` - ${agent.description}` : ''}`,
              value: agent.id,
            }))}
          />
        </Form.Item>

        <MembersField agents={agents} leadAgentId={leadAgentId} currentMembers={values.memberList} />

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
          <Input.TextArea rows={2} maxLength={2000} />
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
          <Button onClick={onCancel}>
            {intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
          </Button>
          <Button type="primary" htmlType="submit" loading={submitting} style={{ marginLeft: 8 }}>
            {intl.formatMessage({ id: 'pages.common.save', defaultMessage: 'Save' })}
          </Button>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default TeamUpdateForm;
