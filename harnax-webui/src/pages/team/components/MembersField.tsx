import React, { useEffect, useRef, useState } from 'react';
import { Button, Form, Input, Select, Space, Tooltip, Typography } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { getAgentPage } from '@/services/ant-design-pro/agent';

const { Text } = Typography;

interface MembersFieldProps {
  /** 当前用户可用的智能体（已启用、同租户） */
  agents: API.AgentItem[];
  /** 编辑时已有的成员，用于给失效引用留出可读的名字 */
  currentMembers?: API.TeamMemberItem[];
}

/**
 * 成员编排区：一行一个成员（选智能体 + 写分工），顺序即主管看到的顺序。
 */
const MembersField: React.FC<MembersFieldProps> = ({ agents, currentMembers }) => {
  const intl = useIntl();
  const form = Form.useFormInstance();

  // `agents` is one page of the catalogue, so typing searches the rest of it instead of
  // silently capping whom a team can contain.
  const [candidates, setCandidates] = useState<API.AgentItem[] | null>(null);
  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(
    () => () => {
      if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    },
    [],
  );

  const handleSearch = (keyword: string) => {
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    if (!keyword) {
      setCandidates(null);
      return;
    }
    searchTimerRef.current = setTimeout(async () => {
      try {
        const res = await getAgentPage({ pageNum: 1, pageSize: 50, status: 1, name: keyword });
        setCandidates(res.data?.records || []);
      } catch {
        // Fall back to the seed page instead of an empty dropdown: no results is a claim about the
        // catalogue, and a failed request does not entitle us to make it.
        setCandidates(null);
      }
    }, 300);
  };

  const pool = candidates || agents;

  const optionOf = (agent: API.AgentItem) => ({
    label: `${agent.name}${agent.description ? ` - ${agent.description}` : ''}`,
    value: agent.id,
  });

  const options = pool.map(optionOf);

  // A member whose agent is not among the candidates needs a row of its own, otherwise the
  // dropdown is left with a bare ID. Whether to call it "deleted or disabled" is answered by the
  // availability flags admin computed per member — not by membership of this page.
  (currentMembers || []).forEach((member) => {
    if (pool.some((agent) => agent.id === member.agentId)) {
      return;
    }
    const unavailable =
      member.agentAvailable === false || (member.agentStatus !== undefined && member.agentStatus !== 1);
    const name = member.agentName || `#${member.agentId}`;
    options.push({
      label: unavailable
        ? `${name} · ${intl.formatMessage({
            id: 'pages.team.memberUnavailable',
            defaultMessage: 'referenced agent is deleted or disabled',
          })}`
        : name,
      value: member.agentId,
    });
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
                    filterOption={false}
                    onSearch={handleSearch}
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
