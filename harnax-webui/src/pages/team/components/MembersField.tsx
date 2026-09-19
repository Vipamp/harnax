import React from 'react';
import { Button, Form, Input, Select, Space, Tooltip, Typography } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

const { Text } = Typography;

interface MembersFieldProps {
  /** 当前用户可用的智能体（已启用、同租户） */
  agents: API.AgentItem[];
  /** 主管 ID：后端拒绝主管兼任成员，这里同步把它从成员候选里去掉 */
  leadAgentId?: number;
  /** 编辑时已有的成员，用于给失效引用留出可读的名字 */
  currentMembers?: API.TeamMemberItem[];
}

/**
 * 成员编排区：一行一个成员（选智能体 + 写分工），顺序即主管看到的顺序。
 */
const MembersField: React.FC<MembersFieldProps> = ({ agents, leadAgentId, currentMembers }) => {
  const intl = useIntl();
  const form = Form.useFormInstance();

  const optionOf = (agent: API.AgentItem) => ({
    label: `${agent.name}${agent.description ? ` - ${agent.description}` : ''}`,
    value: agent.id,
  });

  const options = agents.filter((agent) => agent.id !== leadAgentId).map(optionOf);

  // 团队里引用了已删除或已停用的智能体时，保留一行带名字的禁用项，
  // 否则下拉框只剩一个裸 ID，用户不知道该换掉谁。
  (currentMembers || []).forEach((member) => {
    if (member.agentAvailable === false || !agents.some((agent) => agent.id === member.agentId)) {
      options.push({
        label: `${member.agentName || `#${member.agentId}`} · ${intl.formatMessage({
          id: 'pages.team.memberUnavailable',
          defaultMessage: 'referenced agent is deleted or disabled',
        })}`,
        value: member.agentId,
      });
    }
  });

  return (
    <Form.Item
      label={intl.formatMessage({ id: 'pages.team.members', defaultMessage: 'Members' })}
      required
      extra={intl.formatMessage({
        id: 'pages.team.membersExtra',
        defaultMessage: 'The lead only delegates to these agents and reports their results back',
      })}
      style={{ marginBottom: 8 }}
    >
      <Form.List name="members">
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => (
              <Space
                key={field.key}
                align="baseline"
                style={{ display: 'flex', marginBottom: 8, width: '100%' }}
              >
                <Form.Item
                  {...field}
                  key={`${field.key}-agent`}
                  name={[field.name, 'agentId']}
                  rules={[
                    {
                      required: true,
                      message: intl.formatMessage({
                        id: 'pages.team.memberAgentRequired',
                        defaultMessage: 'Please select a member agent',
                      }),
                    },
                    {
                      validator: async (_: any, value: number) => {
                        if (!value) return Promise.resolve();
                        if (value === leadAgentId) {
                          return Promise.reject(
                            new Error(
                              intl.formatMessage({
                                id: 'pages.team.leadCannotBeMember',
                                defaultMessage: 'The lead agent cannot also be a member',
                              }),
                            ),
                          );
                        }
                        const ids: any[] = (form.getFieldValue('members') || []).map((m: any) => m?.agentId);
                        if (ids.filter((id) => id === value).length > 1) {
                          return Promise.reject(
                            new Error(
                              intl.formatMessage({
                                id: 'pages.team.duplicateMember',
                                defaultMessage: 'The same agent cannot be added twice',
                              }),
                            ),
                          );
                        }
                        return Promise.resolve();
                      },
                    },
                  ]}
                  style={{ marginBottom: 0, width: 240 }}
                >
                  <Select
                    showSearch
                    optionFilterProp="label"
                    placeholder={intl.formatMessage({
                      id: 'pages.team.memberAgentPlaceholder',
                      defaultMessage: 'Select member agent',
                    })}
                    options={options}
                  />
                </Form.Item>

                <Form.Item
                  {...field}
                  key={`${field.key}-delegation`}
                  name={[field.name, 'delegationDescription']}
                  rules={[
                    {
                      max: 500,
                      message: intl.formatMessage({
                        id: 'pages.team.delegationMax',
                        defaultMessage: 'Delegation description cannot exceed 500 characters',
                      }),
                    },
                  ]}
                  style={{ marginBottom: 0, width: 280 }}
                >
                  <Input
                    placeholder={intl.formatMessage({
                      id: 'pages.team.delegationPlaceholder',
                      defaultMessage: 'What this member is responsible for (optional)',
                    })}
                    maxLength={500}
                  />
                </Form.Item>

                {fields.length > 1 && (
                  <Tooltip
                    title={intl.formatMessage({ id: 'pages.team.removeMember', defaultMessage: 'Remove' })}
                  >
                    <MinusCircleOutlined onClick={() => remove(field.name)} />
                  </Tooltip>
                )}
              </Space>
            ))}

            <Button
              type="dashed"
              block
              icon={<PlusOutlined />}
              onClick={() => add({})}
              style={{ marginBottom: 8 }}
            >
              {intl.formatMessage({ id: 'pages.team.addMember', defaultMessage: 'Add member' })}
            </Button>
          </>
        )}
      </Form.List>
      <Text type="secondary" style={{ fontSize: 12 }}>
        {intl.formatMessage({
          id: 'pages.team.memberOrderHint',
          defaultMessage: 'Members are listed to the lead in this order',
        })}
      </Text>
    </Form.Item>
  );
};

export default MembersField;
