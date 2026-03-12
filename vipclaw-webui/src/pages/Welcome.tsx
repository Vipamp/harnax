import { PageContainer } from '@ant-design/pro-components';
import { useModel } from '@umijs/max';
import { Card, Col, Row, Statistic, theme, Typography } from 'antd';
import React from 'react';

const { Title, Paragraph, Text } = Typography;

const StatCard: React.FC<{
  title: string;
  value: string | number;
  suffix?: string;
  color: string;
  icon: string;
}> = ({ title, value, suffix, color, icon }) => {
  const { token } = theme.useToken();
  return (
    <Card
      style={{
        borderRadius: '12px',
        border: 'none',
        background: `linear-gradient(135deg, ${color}15 0%, ${color}08 100%)`,
        boxShadow: `0 4px 20px ${color}18`,
        overflow: 'hidden',
        position: 'relative',
      }}
      styles={{ body: { padding: '24px' } }}
    >
      <div style={{
        position: 'absolute', right: 16, top: 16,
        width: 48, height: 48,
        borderRadius: '12px',
        background: `${color}20`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: '22px',
      }}>
        {icon}
      </div>
      <Text style={{ fontSize: '13px', color: '#888', display: 'block', marginBottom: 8 }}>{title}</Text>
      <Statistic
        value={value}
        suffix={suffix}
        valueStyle={{ fontSize: '28px', fontWeight: 700, color: color }}
      />
    </Card>
  );
};

const FeatureCard: React.FC<{
  title: string;
  desc: string;
  icon: string;
  color: string;
}> = ({ title, desc, icon, color }) => {
  const { token } = theme.useToken();
  return (
    <Card
      hoverable
      style={{
        borderRadius: '12px',
        border: '1px solid #f0f0f8',
        boxShadow: '0 2px 12px rgba(79,110,247,0.06)',
        transition: 'all 0.3s ease',
        cursor: 'default',
      }}
      styles={{ body: { padding: '24px' } }}
    >
      <div style={{
        width: 52, height: 52,
        borderRadius: '14px',
        background: `linear-gradient(135deg, ${color} 0%, ${color}cc 100%)`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: '24px',
        marginBottom: 16,
        boxShadow: `0 8px 20px ${color}40`,
      }}>
        {icon}
      </div>
      <Title level={5} style={{ margin: '0 0 8px', fontSize: '16px', color: '#1a1a2e' }}>{title}</Title>
      <Paragraph style={{ margin: 0, color: '#666', fontSize: '14px', lineHeight: 1.7 }}>{desc}</Paragraph>
    </Card>
  );
};

const Welcome: React.FC = () => {
  const { token } = theme.useToken();
  const { initialState } = useModel('@@initialState');
  const username = initialState?.currentUser?.nickname || initialState?.currentUser?.username || '管理员';

  const features = [
    { title: '多模型集成', desc: '支持 GPT、Claude、Gemini 等主流大模型，自由切换和组合使用', icon: '🤖', color: '#4f6ef7' },
    { title: 'MCP 协议', desc: '基于 Model Context Protocol，实现工具与模型的高效交互', icon: '🔗', color: '#52c41a' },
    { title: 'Skills 中心', desc: '可扩展的技能库，让智能体尅性化处理复杂任务', icon: '⚡', color: '#fa8c16' },
    { title: '企业级安全', desc: '完整的身份认证、权限管理和审计日志', icon: '🔒', color: '#eb2f96' },
    { title: '实时监控', desc: '系统运行状态全面可视，性能指标实时注入', icon: '📊', color: '#1890ff' },
    { title: '开放 API', desc: '提供标准 RESTful API和 OpenAPI 文档，快速接入业务系统', icon: '🚀', color: '#722ed1' },
  ];

  return (
    <PageContainer
      header={{
        title: '',
        breadcrumb: {},
      }}
    >
      {/* 顶部欢迎横幅 */}
      <Card
        style={{
          borderRadius: '16px',
          border: 'none',
          background: 'linear-gradient(135deg, #1a1a2e 0%, #16213e 40%, #0f3460 70%, #4f6ef7 100%)',
          boxShadow: '0 8px 32px rgba(79, 110, 247, 0.25)',
          marginBottom: 24,
          overflow: 'hidden',
          position: 'relative',
        }}
        styles={{ body: { padding: '40px 48px' } }}
      >
        {/* 背景装饰 */}
        <div style={{
          position: 'absolute', right: -40, top: -40,
          width: 280, height: 280,
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(255,255,255,0.08) 0%, transparent 70%)',
        }} />
        <div style={{
          position: 'absolute', right: 80, bottom: -60,
          width: 200, height: 200,
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(102,126,234,0.15) 0%, transparent 70%)',
        }} />

        <div style={{ position: 'relative', zIndex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 16 }}>
            <div style={{
              width: 56, height: 56,
              borderRadius: '16px',
              background: 'rgba(255,255,255,0.15)',
              backdropFilter: 'blur(10px)',
              border: '1px solid rgba(255,255,255,0.2)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: '26px',
            }}>
              🐾
            </div>
            <div>
              <Text style={{ color: 'rgba(255,255,255,0.6)', fontSize: '14px', display: 'block' }}>
                欢迎回来
              </Text>
              <Title level={3} style={{ color: '#fff', margin: 0, fontSize: '24px' }}>
                {username} 👋
              </Title>
            </div>
          </div>
          <Paragraph style={{
            color: 'rgba(255,255,255,0.75)',
            fontSize: '15px',
            margin: '0 0 28px',
            maxWidth: 560,
            lineHeight: 1.8,
          }}>
            VipClaw 是一个整合了多种大模型、工具、MCP、Skills 的智能体平台，为企业和开发者提供一站式的解决方案。
          </Paragraph>
          <div style={{ display: 'flex', gap: 12 }}>
            {['🤖 大模型集成', '🛠️ 工具平台', '🔗 MCP 协议', '⚡ Skills 库'].map(tag => (
              <div key={tag} style={{
                padding: '6px 14px',
                background: 'rgba(255,255,255,0.12)',
                backdropFilter: 'blur(8px)',
                borderRadius: '20px',
                border: '1px solid rgba(255,255,255,0.18)',
                color: 'rgba(255,255,255,0.9)',
                fontSize: '13px',
                fontWeight: 500,
              }}>
                {tag}
              </div>
            ))}
          </div>
        </div>
      </Card>

      {/* 统计数据卡片行 */}
      <Row gutter={[20, 20]} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="已集成模型" value={12} suffix="个" color="#4f6ef7" icon="🤖" />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="工具数量" value={48} suffix="个" color="#52c41a" icon="🛠️" />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="今日请求" value={1024} color="#fa8c16" icon="📊" />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="活跃用户" value={256} color="#eb2f96" icon="👥" />
        </Col>
      </Row>

      {/* 功能特性卡片 */}
      <Card
        style={{
          borderRadius: '16px',
          border: '1px solid #f0f0f8',
          boxShadow: '0 2px 16px rgba(79,110,247,0.06)',
        }}
        styles={{ body: { padding: '28px 28px 8px' } }}
      >
        <Title level={5} style={{ margin: '0 0 20px', fontSize: '17px', color: '#1a1a2e' }}>
          🚀 平台能力
        </Title>
        <Row gutter={[20, 20]}>
          {features.map(f => (
            <Col xs={24} sm={12} lg={8} key={f.title}>
              <FeatureCard {...f} />
            </Col>
          ))}
        </Row>
      </Card>
    </PageContainer>
  );
};

export default Welcome;
