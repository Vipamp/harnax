import { Modal, Descriptions, Tag, Typography, Collapse, Empty, Divider, Tooltip } from 'antd';
import React from 'react';
import { InfoCircleOutlined, DatabaseOutlined, ToolOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

const { Text, Paragraph } = Typography;
const { Panel } = Collapse;

interface DetailModalProps {
  visible: boolean;
  session: API.SessionItem | null;
  onCancel: () => void;
}

const DetailModal: React.FC<DetailModalProps> = ({ visible, session, onCancel }) => {
  const intl = useIntl();
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
        defaultActiveKey={['basic', 'agent', 'mcp', 'skill']} 
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

        {/* 关联智能体信息 */}
        <Panel 
          header={<span style={{ color: 'var(--vip-text-primary)' }}><ThunderboltOutlined style={{ marginRight: 8 }} />{intl.formatMessage({ id: 'pages.session.associatedAgent', defaultMessage: 'Associated Agent' })}</span>} 
          key="agent"
        >
          <Descriptions column={2} size="small">
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.agentName', defaultMessage: 'Agent Name' })}>
              <Tag color="purple">{session.name || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.session.agentDescription', defaultMessage: 'Agent Description' })}>
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
                  borderRadius: 6,
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

        {/* MCP 服务列表 */}
        <Panel 
          header={
            <span style={{ color: 'var(--vip-text-primary)' }}>
              <ToolOutlined style={{ marginRight: 8 }} />
              {intl.formatMessage({ id: 'pages.session.mcpServices', defaultMessage: 'MCP Services' })}
              {session.mcpList && session.mcpList.length > 0 && (
                <Tag color="blue" style={{ marginLeft: 8 }}>{session.mcpList.length} {intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}</Tag>
              )}
            </span>
          } 
          key="mcp"
        >
          {session.mcpList && session.mcpList.length > 0 ? (
            <div>
              {session.mcpList.map((mcp, index) => (
                <div 
                  key={mcp.mcpId || index}
                  style={{ 
                    padding: '12px 16px',
                    background: 'var(--vip-bg-layout)',
                    borderRadius: 8,
                    marginBottom: index < session.mcpList!.length - 1 ? 8 : 0,
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
                    <Tag color={mcp.enableSkip === 'true' ? 'orange' : 'default'}>
                      {mcp.enableSkip === 'true' ? intl.formatMessage({ id: 'pages.session.allowSkip', defaultMessage: 'Allow Skip' }) : intl.formatMessage({ id: 'pages.session.notAllowSkip', defaultMessage: 'Not Allow Skip' })}
                    </Tag>
                  </div>
                  {mcp.mcpDescription && (
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
                      {mcp.mcpDescription}
                    </Text>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.session.noMcpConfig', defaultMessage: 'No MCP configuration' })} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>

        {/* 技能列表 */}
        <Panel 
          header={
            <span style={{ color: 'var(--vip-text-primary)' }}>
              <ToolOutlined style={{ marginRight: 8 }} />
              {intl.formatMessage({ id: 'pages.session.skillConfig', defaultMessage: 'Skill Configuration' })}
              {session.skillList && session.skillList.length > 0 && (
                <Tag color="green" style={{ marginLeft: 8 }}>{session.skillList.length} {intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}</Tag>
              )}
            </span>
          } 
          key="skill"
        >
          {session.skillList && session.skillList.length > 0 ? (
            <div>
              {session.skillList.map((skill, index) => (
                <div 
                  key={skill.skillId || index}
                  style={{ 
                    padding: '12px 16px',
                    background: 'var(--vip-bg-layout)',
                    borderRadius: 8,
                    marginBottom: index < session.skillList!.length - 1 ? 8 : 0,
                    border: '1px solid var(--vip-border)'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <Tag color="green">{skill.skillName}</Tag>
                      <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                        ID: {skill.skillId}
                      </Text>
                    </div>
                    {skill.repositoryName && (
                      <Tag color="default">{intl.formatMessage({ id: 'pages.session.repository', defaultMessage: 'Repository' })}: {skill.repositoryName}</Tag>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.session.noSkillConfig', defaultMessage: 'No skill configuration' })} image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>
      </Collapse>

      <Divider style={{ margin: '12px 0', borderColor: 'var(--vip-border)' }} />
      
      <div style={{ textAlign: 'right', color: 'var(--vip-text-tertiary)', fontSize: 12 }}>
        {intl.formatMessage({ id: 'pages.session.updateTime', defaultMessage: 'Update Time' })}：{session.updateTime || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}
      </div>
    </Modal>
  );
};

export default DetailModal;
