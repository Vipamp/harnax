import { Modal, Descriptions, Tag, Typography, Collapse, Empty, Divider } from 'antd';
import React from 'react';
import { InfoCircleOutlined, DatabaseOutlined, ToolOutlined, ThunderboltOutlined } from '@ant-design/icons';

const { Text, Paragraph } = Typography;
const { Panel } = Collapse;

interface DetailModalProps {
  visible: boolean;
  session: API.SessionItem | null;
  onCancel: () => void;
}

const DetailModal: React.FC<DetailModalProps> = ({ visible, session, onCancel }) => {
  if (!session) return null;

  // 状态标签颜色
  const getStatusTag = (status: number) => {
    return status === 1 
      ? <Tag color="green">启用</Tag> 
      : <Tag color="red">禁用</Tag>;
  };

  // 公开状态标签
  const getPublicTag = (isPublic: number) => {
    return isPublic === 1 
      ? <Tag color="blue">公开</Tag> 
      : <Tag color="default">私有</Tag>;
  };

  return (
    <Modal
      title={
        <span>
          <InfoCircleOutlined style={{ marginRight: 8 }} />
          会话详情
        </span>
      }
      open={visible}
      onCancel={onCancel}
      footer={null}
      width={800}
      destroyOnClose
    >
      <Collapse 
        defaultActiveKey={['basic', 'agent', 'mcp', 'skill']} 
        bordered={false}
        style={{ background: '#fff' }}
      >
        {/* 基本信息 */}
        <Panel 
          header={<span><DatabaseOutlined style={{ marginRight: 8 }} />基本信息</span>} 
          key="basic"
        >
          <Descriptions column={2} size="small">
            <Descriptions.Item label="会话ID" span={2}>
              <Text copyable style={{ fontFamily: 'monospace', fontSize: 12 }}>
                {session.sessionId}
              </Text>
            </Descriptions.Item>
            <Descriptions.Item label="会话名称">
              <Text strong>{session.title}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              {getStatusTag(session.status)}
            </Descriptions.Item>
            <Descriptions.Item label="会话描述" span={2}>
              {session.sessionDescription || <Text type="secondary">无</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="是否公开">
              {getPublicTag(session.isPublic || 0)}
            </Descriptions.Item>
            <Descriptions.Item label="所有者">
              {session.owner || <Text type="secondary">无</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="创建人">
              {session.creator || <Text type="secondary">未知</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {session.createTime || <Text type="secondary">未知</Text>}
            </Descriptions.Item>
          </Descriptions>
        </Panel>

        {/* 关联智能体信息 */}
        <Panel 
          header={<span><ThunderboltOutlined style={{ marginRight: 8 }} />关联智能体</span>} 
          key="agent"
        >
          <Descriptions column={2} size="small">
            <Descriptions.Item label="智能体ID">
              {session.agentId}
            </Descriptions.Item>
            <Descriptions.Item label="智能体名称">
              <Tag color="purple">{session.name || '未知'}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="智能体描述" span={2}>
              {session.description || <Text type="secondary">无</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="对话模型">
              {session.modelName ? (
                <Tag color="cyan">{session.modelName}</Tag>
              ) : (
                <Text type="secondary">未配置</Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="模型ID">
              {session.modelId || <Text type="secondary">无</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="系统提示词" span={2}>
              <Paragraph 
                ellipsis={{ rows: 4, expandable: true, symbol: '展开' }}
                style={{ 
                  marginBottom: 0,
                  padding: 8,
                  background: '#f6f8fa',
                  borderRadius: 6,
                  fontFamily: 'monospace',
                  fontSize: 12,
                  whiteSpace: 'pre-wrap'
                }}
              >
                {session.systemPrompt || '无'}
              </Paragraph>
            </Descriptions.Item>
          </Descriptions>
        </Panel>

        {/* MCP 服务列表 */}
        <Panel 
          header={
            <span>
              <ToolOutlined style={{ marginRight: 8 }} />
              MCP 服务 
              {session.mcpList && session.mcpList.length > 0 && (
                <Tag color="blue" style={{ marginLeft: 8 }}>{session.mcpList.length} 个</Tag>
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
                    background: '#fafafa',
                    borderRadius: 8,
                    marginBottom: index < session.mcpList!.length - 1 ? 8 : 0,
                    border: '1px solid #f0f0f0'
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
                      {mcp.enableSkip === 'true' ? '允许跳过' : '不可跳过'}
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
            <Empty description="未配置 MCP 服务" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>

        {/* 技能列表 */}
        <Panel 
          header={
            <span>
              <ToolOutlined style={{ marginRight: 8 }} />
              技能配置
              {session.skillList && session.skillList.length > 0 && (
                <Tag color="green" style={{ marginLeft: 8 }}>{session.skillList.length} 个</Tag>
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
                    background: '#f6ffed',
                    borderRadius: 8,
                    marginBottom: index < session.skillList!.length - 1 ? 8 : 0,
                    border: '1px solid #b7eb8f'
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
                      <Tag color="default">仓库: {skill.repositoryName}</Tag>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <Empty description="未配置技能" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </Panel>
      </Collapse>

      <Divider style={{ margin: '12px 0' }} />
      
      <div style={{ textAlign: 'right', color: '#999', fontSize: 12 }}>
        更新时间：{session.updateTime || '未知'}
      </div>
    </Modal>
  );
};

export default DetailModal;
