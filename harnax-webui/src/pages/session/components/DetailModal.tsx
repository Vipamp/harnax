import { Modal, Descriptions, Tag, Typography, Collapse, Empty, Divider, Tooltip, Spin } from 'antd';
import React, { useState, useEffect } from 'react';
import { InfoCircleOutlined, DatabaseOutlined, ToolOutlined, ThunderboltOutlined, ApiOutlined, RightOutlined, DownOutlined, EnvironmentOutlined, TeamOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { getAgentById } from '@/services/ant-design-pro/agent';
import { getTeamById } from '@/services/ant-design-pro/team';

const { Text, Paragraph } = Typography;
const { Panel } = Collapse;

interface DetailModalProps {
  visible: boolean;
  session: API.SessionItem | null;
  onCancel: () => void;
}

/** Skill card payload: an agent skill or a team lead skill. */
type SkillCardItem = {
  skillId?: number;
  skillName?: string;
  skillDescription?: string;
  repositoryName?: string;
  /** 引用已失效（技能被删或停用）时为 false，运行侧会跳过它 */
  skillAvailable?: boolean;
};

type MemberCardItem = {
  agentId: number;
  agentName?: string;
  agentDescription?: string;
  delegationDescription?: string;
  /** 引用已失效（agent 被删或停用）时为 false */
  agentAvailable?: boolean;
};

/** Collapsible environment bindings table for a single tool/MCP. */
const EnvBindingsTable: React.FC<{ bindings: API.EnvBinding[]; intl: any }> = ({ bindings, intl }) => {
  const [envCollapsed, setEnvCollapsed] = useState(true);
  if (!bindings || bindings.length === 0) return null;
  return (
    <div style={{ marginTop: 8 }}>
      <div
        onClick={() => setEnvCollapsed(!envCollapsed)}
        style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, userSelect: 'none' }}
      >
        {envCollapsed ? <RightOutlined style={{ fontSize: 10 }} /> : <DownOutlined style={{ fontSize: 10 }} />}
        <EnvironmentOutlined style={{ fontSize: 12, color: 'var(--vip-primary)' }} />
        <span style={{ fontSize: 11, fontWeight: 500, color: 'var(--vip-text-secondary)' }}>
          {intl.formatMessage({ id: 'pages.session.envBindings', defaultMessage: 'Environment Bindings' })} ({bindings.length})
        </span>
      </div>
      {!envCollapsed && (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12, marginTop: 4 }}>
          <thead>
            <tr style={{ background: 'var(--vip-bg-container)', borderBottom: '1px solid var(--vip-border)' }}>
              <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)', width: '30%' }}>
                {intl.formatMessage({ id: 'pages.session.envParamName', defaultMessage: 'Param Name' })}
              </th>
              <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)', width: '35%' }}>
                {intl.formatMessage({ id: 'pages.session.envVarName', defaultMessage: 'Env Variable' })}
              </th>
              <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)', width: '35%' }}>
                {intl.formatMessage({ id: 'pages.session.envVarValue', defaultMessage: 'Value' })}
              </th>
            </tr>
          </thead>
          <tbody>
            {bindings.map((env, i) => {
              const isCustom = !env.envVarId && !!env.customValue;
              return (
                <tr key={i} style={{ borderBottom: '1px solid var(--vip-border)' }}>
                  <td style={{ padding: '4px 8px', fontFamily: 'monospace', color: 'var(--vip-text-primary)' }}>{env.envKey || '-'}</td>
                  <td style={{ padding: '4px 8px' }}>
                    {isCustom
                      ? <Tag color="cyan" style={{ fontSize: 10 }}>{intl.formatMessage({ id: 'pages.session.custom', defaultMessage: 'Custom' })}</Tag>
                      : (env.envVarName || '-')
                    }
                  </td>
                  <td style={{ padding: '4px 8px', color: 'var(--vip-text-secondary)' }}>
                    {isCustom ? env.customValue : (env.envValue || '-')}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
};

const DetailModal: React.FC<DetailModalProps> = ({ visible, session, onCancel }) => {
  const intl = useIntl();
  const [toolList, setToolList] = useState<API.AgentToolConfig[]>([]);
  const [mcpList, setMcpList] = useState<API.AgentMcpConfig[]>([]);
  const [cliList, setCliList] = useState<API.AgentCliConfig[]>([]);
  const [skillList, setSkillList] = useState<SkillCardItem[]>([]);
  const [memberList, setMemberList] = useState<MemberCardItem[]>([]);
  const [loadingConfig, setLoadingConfig] = useState(false);

  const isTeam = !!session?.teamId;
  const agentId = session?.agentId;

  const clearConfig = () => {
    setToolList([]);
    setMcpList([]);
    setCliList([]);
    setSkillList([]);
    setMemberList([]);
  };

  useEffect(() => {
    if (!visible || !session) return;
    if (session.teamId) {
      // 团队会话没有 agent 行可查，主管的技能与成员都来自团队自身的配置
      setLoadingConfig(true);
      getTeamById(session.teamId)
        .then((res) => {
          const team = res?.code === 200 ? (res.data as API.TeamItem | undefined) : undefined;
          setToolList([]);
          setMcpList([]);
          setCliList([]);
          setSkillList(team?.skillList || []);
          setMemberList(team?.memberList || []);
        })
        .catch(clearConfig)
        .finally(() => setLoadingConfig(false));
      return;
    }
    if (agentId) {
      setLoadingConfig(true);
      getAgentById(agentId)
        .then((res) => {
          const agent = res?.code === 200 ? (res.data as API.AgentItem | undefined) : undefined;
          setToolList(agent?.toolList || []);
          setMcpList(agent?.mcpList || []);
          setCliList(agent?.cliList || []);
          setSkillList(agent?.skillList || []);
          setMemberList([]);
        })
        .catch(clearConfig)
        .finally(() => setLoadingConfig(false));
      return;
    }
    // 既没有 agent 也没有 team：只剩会话快照里的技能可展示
    clearConfig();
    setSkillList(session.skillList || []);
  }, [visible, session?.id, agentId, session?.teamId]);

  if (!session) return null;

  // 状态标签颜色
  const getStatusTag = (status: number) => {
    return status === 1 
      ? <Tag color="green">{intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}</Tag> 
      : <Tag color="red">{intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}</Tag>;
  };

  // 公开状态标签
  const getPublicTag = (isPublic: number) => {
    return isPublic === 1 
      ? <Tag color="blue">{intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}</Tag> 
      : <Tag color="default">{intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}</Tag>;
  };

  return (
    <Modal
      title={
        <span style={{ color: 'var(--vip-text-primary)' }}>
          <InfoCircleOutlined style={{ marginRight: 8 }} />
          {intl.formatMessage({ id: 'pages.session.detailTitle', defaultMessage: 'Session Detail' })}
        </span>
      }
      open={visible}
      onCancel={onCancel}
      footer={null}
      width={800}
      destroyOnClose
      styles={{
        body: { 
          padding: '24px',
          background: 'var(--vip-bg-layout)',
        },
      }}
    >
      <Collapse 
        defaultActiveKey={['basic', 'agent', 'tools', 'mcp', 'skill', 'cli', 'members']}
        bordered={false}
        style={{ background: 'var(--vip-bg-container)' }}
      >
        {/* 基本信息 */}
        <Panel 
          header={<span style={{ color: 'var(--vip-text-primary)' }}><DatabaseOutlined style={{ marginRight: 8 }} />{intl.formatMessage({ id: 'pages.session.basicInfo', defaultMessage: 'Basic Info' })}</span>} 
          key="basic"
        >
          <Descriptions column={2} size="small">
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.sessionId', defaultMessage: 'Session ID' })} span={2}>
              <Text copyable style={{ fontFamily: 'monospace', fontSize: 12 }}>
                {session.sessionId}
              </Text>
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.sessionName', defaultMessage: 'Session Name' })}>
              <Text strong style={{ color: 'var(--vip-text-primary)' }}>{session.title}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.sessionDescription', defaultMessage: 'Session Description' })} span={2}>
              {session.sessionDescription || <Text type="secondary">{intl.formatMessage({ id: 'pages.common.none', defaultMessage: 'None' })}</Text>}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.isPublic', defaultMessage: 'Is Public' })}>
              {getPublicTag(session.isPublic || 0)}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.owner', defaultMessage: 'Owner' })}>
              {session.owner || <Text type="secondary">{intl.formatMessage({ id: 'pages.common.none', defaultMessage: 'None' })}</Text>}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.creator', defaultMessage: 'Creator' })}>
              {session.creator || <Text type="secondary">{intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}</Text>}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.createTime', defaultMessage: 'Create Time' })}>
              {session.createTime || <Text type="secondary">{intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}</Text>}
            </Descriptions.Item>
          </Descriptions>
        </Panel>

        {/* 关联智能体信息；团队会话这一栏是团队自带的主管 */}
        <Panel 
          header={<span style={{ color: 'var(--vip-text-primary)' }}><ThunderboltOutlined style={{ marginRight: 8 }} />{intl.formatMessage({ id: isTeam ? 'pages.session.associatedTeamLead' : 'pages.session.associatedAgent', defaultMessage: isTeam ? 'Team Lead' : 'Associated Agent' })}</span>}
          key="agent"
        >
          <Descriptions column={2} size="small">
            <Descriptions.Item label={intl.formatMessage({ id: isTeam ? 'pages.session.leadName' : 'pages.session.agentName', defaultMessage: isTeam ? 'Lead Name' : 'Agent Name' })}>
              <Tag color="purple">{session.name || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: isTeam ? 'pages.session.leadDescription' : 'pages.session.agentDescription', defaultMessage: isTeam ? 'Lead Description' : 'Agent Description' })}>
              <Tooltip title={session.description || intl.formatMessage({ id: 'pages.common.none', defaultMessage: 'None' })}>
                <Text 
                  type="secondary" 
                  style={{ 
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                    display: 'block'
                  }}
                >
                  {session.description || intl.formatMessage({ id: 'pages.common.none', defaultMessage: 'None' })}
                </Text>
              </Tooltip>
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.model', defaultMessage: 'Model' })}>
              {session.modelName ? (
                <Tag color="blue">{session.modelName}</Tag>
              ) : (
                <Text type="secondary">{intl.formatMessage({ id: 'pages.session.notConfigured', defaultMessage: 'Not configured' })}</Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.modelPrice', defaultMessage: 'Model Price' })}>
              {session.modelPrice !== undefined && session.modelPrice !== null ? (
                <Tag color="cyan">¥{session.modelPrice}/M</Tag>
              ) : (
                <Text type="secondary">{intl.formatMessage({ id: 'pages.session.notConfigured', defaultMessage: 'Not configured' })}</Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.systemPrompt', defaultMessage: 'System Prompt' })} span={2}>
              <Paragraph 
                ellipsis={{ rows: 4, expandable: true, symbol: intl.formatMessage({ id: 'pages.common.expand', defaultMessage: 'Expand' }) }}
                style={{ 
                  marginBottom: 0,
                  padding: 8,
                  background: 'var(--vip-bg-layout)',
                  borderRadius: 'var(--vip-radius-sm)',
                  fontFamily: 'monospace',
                  fontSize: 12,
                  whiteSpace: 'pre-wrap',
                  color: 'var(--vip-text-primary)'
                }}
              >
                {session.systemPrompt || intl.formatMessage({ id: 'pages.common.none', defaultMessage: 'None' })}
              </Paragraph>
            </Descriptions.Item>
          </Descriptions>
        </Panel>

        {/* 工具列表：主管不挂工具，团队会话没有这一栏 */}
        {!isTeam && (
        <Panel
          header={
            <span style={{ color: 'var(--vip-text-primary)' }}>
              <ApiOutlined style={{ marginRight: 8 }} />
              {intl.formatMessage({ id: 'pages.session.toolList', defaultMessage: 'Tool List' })}
              {toolList.length > 0 && (
                <Tag color="orange" style={{ marginLeft: 8 }}>{toolList.length} {intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}</Tag>
              )}
            </span>
          }
          key="tools"
        >
          {loadingConfig ? (
            <div style={{ textAlign: 'center', padding: 24 }}><Spin size="small" /></div>
          ) : toolList.length > 0 ? (
            <div>
              {toolList.map((tool, index) => (
                <div
                  key={tool.toolId || index}
                  style={{
                    padding: '12px 16px',
                    background: 'var(--vip-bg-layout)',
                    borderRadius: 8,
                    marginBottom: index < toolList.length - 1 ? 8 : 0,
                    border: '1px solid var(--vip-border)',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <Tag color="blue">
                        {intl.locale?.startsWith('zh')
                          ? (tool.toolDisplayNameZh || tool.toolDisplayName || tool.toolName)
                          : (tool.toolDisplayName || tool.toolName)}
                      </Tag>
                    </div>
                    <div style={{ display: 'flex', gap: 4 }}>
                      {tool.needConfirm && (
                        <Tag color="orange">{intl.formatMessage({ id: 'pages.session.needConfirm', defaultMessage: 'Need Confirm' })}</Tag>
                      )}
                    </div>
                  </div>
                  {tool.toolDescription && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
                      {tool.toolDescription}
                    </Text>
                  )}
                  <EnvBindingsTable bindings={tool.envBindings || []} intl={intl} />
                </div>
              ))}
            </div>
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.session.noToolConfig', defaultMessage: 'No tool configuration' })} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>
        )}

        {/* MCP 服务列表：主管不挂 MCP，团队会话没有这一栏 */}
        {!isTeam && (
        <Panel 
          header={
            <span style={{ color: 'var(--vip-text-primary)' }}>
              <ToolOutlined style={{ marginRight: 8 }} />
              {intl.formatMessage({ id: 'pages.session.mcpServices', defaultMessage: 'MCP Services' })}
              {mcpList.length > 0 && (
                <Tag color="blue" style={{ marginLeft: 8 }}>{mcpList.length} {intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}</Tag>
              )}
            </span>
          } 
          key="mcp"
        >
          {loadingConfig ? (
            <div style={{ textAlign: 'center', padding: 24 }}><Spin size="small" /></div>
          ) : mcpList.length > 0 ? (
            <div>
              {mcpList.map((mcp, index) => (
                <div 
                  key={mcp.mcpId || index}
                  style={{ 
                    padding: '12px 16px',
                    background: 'var(--vip-bg-layout)',
                    borderRadius: 8,
                    marginBottom: index < mcpList.length - 1 ? 8 : 0,
                    border: '1px solid var(--vip-border)'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <Tag color="geekblue">{mcp.mcpName}</Tag>
                      <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                        ID: {mcp.mcpId}
                      </Text>
                    </div>
                  </div>
                  {mcp.mcpDescription && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
                      {mcp.mcpDescription}
                    </Text>
                  )}
                  <EnvBindingsTable bindings={mcp.envBindings || []} intl={intl} />
                </div>
              ))}
            </div>
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.session.noMcpConfig', defaultMessage: 'No MCP configuration' })} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>
        )}

        {/* 技能列表：单 agent 会话是智能体的技能，团队会话是主管的技能 */}
        <Panel 
          header={
            <span style={{ color: 'var(--vip-text-primary)' }}>
              <ToolOutlined style={{ marginRight: 8 }} />
              {intl.formatMessage({ id: 'pages.session.skillConfig', defaultMessage: 'Skill Configuration' })}
              {skillList.length > 0 && (
                <Tag color="green" style={{ marginLeft: 8 }}>{skillList.length} {intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}</Tag>
              )}
            </span>
          } 
          key="skill"
        >
          {loadingConfig ? (
            <div style={{ textAlign: 'center', padding: 24 }}><Spin size="small" /></div>
          ) : skillList.length > 0 ? (
            <div>
              {skillList.map((skill, index) => (
                <div 
                  key={skill.skillId || index}
                  style={{ 
                    padding: '12px 16px',
                    background: 'var(--vip-bg-layout)',
                    borderRadius: 8,
                    marginBottom: index < skillList.length - 1 ? 8 : 0,
                    border: '1px solid var(--vip-border)'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <Tag color="green">{skill.skillName}</Tag>
                      {skill.skillId && (
                        <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                          ID: {skill.skillId}
                        </Text>
                      )}
                    </div>
                    {skill.repositoryName && (
                      <Tag color="default">{intl.formatMessage({ id: 'pages.session.repository', defaultMessage: 'Repository' })}: {skill.repositoryName}</Tag>
                    )}
                    {skill.skillAvailable === false && (
                      <Tag color="red">{intl.formatMessage({ id: 'pages.team.skillUnavailable', defaultMessage: 'This skill is disabled or deleted' })}</Tag>
                    )}
                  </div>
                  {skill.skillDescription && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
                      {skill.skillDescription}
                    </Text>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.session.noSkillConfig', defaultMessage: 'No skill configuration' })} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>

        {/* CLI 配置：只有单 agent 会话有，主管不挂 CLI */}
        {!isTeam && (
        <Panel
          header={
            <span style={{ color: 'var(--vip-text-primary)' }}>
              <ApiOutlined style={{ marginRight: 8 }} />
              {intl.formatMessage({ id: 'pages.session.cliConfig', defaultMessage: 'CLI Configuration' })}
              {cliList.length > 0 && (
                <Tag color="purple" style={{ marginLeft: 8 }}>{cliList.length} {intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}</Tag>
              )}
            </span>
          }
          key="cli"
        >
          {loadingConfig ? (
            <div style={{ textAlign: 'center', padding: 24 }}><Spin size="small" /></div>
          ) : cliList.length > 0 ? (
            <div>
              {cliList.map((cli, index) => (
                <div
                  key={cli.cliId || index}
                  style={{
                    padding: '12px 16px',
                    background: 'var(--vip-bg-layout)',
                    borderRadius: 8,
                    marginBottom: index < cliList.length - 1 ? 8 : 0,
                    border: '1px solid var(--vip-border)',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <Tag color="purple">{cli.cliName || `CLI #${cli.cliId}`}</Tag>
                      {cli.version && (
                        <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>v{cli.version}</Text>
                      )}
                    </div>
                  </div>
                  {cli.cliDescription && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
                      {cli.cliDescription}
                    </Text>
                  )}
                  {(cli.skillList || []).length > 0 && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
                      {intl.formatMessage({ id: 'pages.agent.cli.skills', defaultMessage: 'Skills' })}: {(cli.skillList || []).map((s) => s.skillName).join(', ')}
                    </Text>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.session.noCliConfig', defaultMessage: 'No CLI configuration' })} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>
        )}

        {/* 团队成员：主管按这个顺序看到可委派的成员 */}
        {isTeam && (
        <Panel
          header={
            <span style={{ color: 'var(--vip-text-primary)' }}>
              <TeamOutlined style={{ marginRight: 8 }} />
              {intl.formatMessage({ id: 'pages.session.teamMembers', defaultMessage: 'Team Members' })}
              {memberList.length > 0 && (
                <Tag color="blue" style={{ marginLeft: 8 }}>{memberList.length} {intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}</Tag>
              )}
            </span>
          }
          key="members"
        >
          {loadingConfig ? (
            <div style={{ textAlign: 'center', padding: 24 }}><Spin size="small" /></div>
          ) : memberList.length > 0 ? (
            <div>
              {memberList.map((member, index) => (
                <div
                  key={member.agentId || index}
                  style={{
                    padding: '12px 16px',
                    background: 'var(--vip-bg-layout)',
                    borderRadius: 8,
                    marginBottom: index < memberList.length - 1 ? 8 : 0,
                    border: '1px solid var(--vip-border)',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <Tag color="geekblue">{member.agentName || `Agent #${member.agentId}`}</Tag>
                      <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                        ID: {member.agentId}
                      </Text>
                    </div>
                    {member.agentAvailable === false && (
                      <Tag color="red">{intl.formatMessage({ id: 'pages.team.memberUnavailable', defaultMessage: 'This agent is disabled or deleted' })}</Tag>
                    )}
                  </div>
                  {member.agentDescription && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
                      {member.agentDescription}
                    </Text>
                  )}
                  {member.delegationDescription && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
                      {intl.formatMessage({ id: 'pages.session.delegation', defaultMessage: 'Delegation note' })}: {member.delegationDescription}
                    </Text>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.session.noTeamMembers', defaultMessage: 'No team members' })} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>
        )}
      </Collapse>

      <Divider style={{ margin: '12px 0', borderColor: 'var(--vip-border)' }} />
      
      <div style={{ textAlign: 'right', color: 'var(--vip-text-tertiary)', fontSize: 12 }}>
        {intl.formatMessage({ id: 'pages.session.updateTime', defaultMessage: 'Update Time' })}：{session.updateTime || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}
      </div>
    </Modal>
  );
};

export default DetailModal;
