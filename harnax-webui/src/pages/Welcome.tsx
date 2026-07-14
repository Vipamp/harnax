import { PageContainer } from '@ant-design/pro-components';
import { Card, Col, Row, Statistic, theme, Typography, Badge, Space } from 'antd';
import React, { useEffect, useState, useRef } from 'react';
import { useIsMobile } from '@/utils/responsive';

const { Title, Paragraph, Text } = Typography;

// 模拟获取用户数据
const useCurrentUser = () => {
  const [user, setUser] = useState<any>(null);
  useEffect(() => {
    // 从 localStorage 获取用户信息
    const stored = localStorage.getItem('currentUser');
    if (stored) {
      try {
        setUser(JSON.parse(stored));
      } catch (e) {
        console.error('Failed to parse user info:', e);
      }
    }
  }, []);
  return user;
};

// 数字动画钩子
const useCountUp = (end: number, duration: number = 2000): number => {
  const [count, setCount] = useState<number>(0);
  const countRef = useRef<number>(0);
  const frameRef = useRef<number | null>(null);

  useEffect(() => {
    const startTime = Date.now();
    const animate = () => {
      const elapsed = Date.now() - startTime;
      const progress = Math.min(elapsed / duration, 1);
      // 使用 easeOutQuart 缓动函数
      const easeProgress = 1 - Math.pow(1 - progress, 4);
      const currentCount = Math.floor(easeProgress * end);
      
      if (currentCount !== countRef.current) {
        countRef.current = currentCount;
        setCount(currentCount);
      }
      
      if (progress < 1) {
        frameRef.current = requestAnimationFrame(animate);
      }
    };
    
    frameRef.current = requestAnimationFrame(animate);
    
    return () => {
      if (frameRef.current) {
        cancelAnimationFrame(frameRef.current);
      }
    };
  }, [end, duration]);

  return count;
};

// 带动画的统计卡片
const AnimatedStatCard: React.FC<{
  title: string;
  value: number;
  suffix?: string;
  color: string;
  icon: string;
  delay?: number;
}> = ({ title, value, suffix, color, icon, delay = 0 }) => {
  const animatedValue = useCountUp(value, 2000);
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => setIsVisible(true), delay);
    return () => clearTimeout(timer);
  }, [delay]);

  return (
    <Card
      className="vip-hover-lift"
      style={{
        borderRadius: '16px',
        background: `linear-gradient(135deg, ${color}12 0%, ${color}05 100%), var(--glass-bg)`,
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        border: '1px solid var(--glass-border)',
        boxShadow: `0 4px 20px ${color}15`,
        overflow: 'hidden',
        position: 'relative',
        opacity: isVisible ? 1 : 0,
        transform: isVisible ? 'translateY(0)' : 'translateY(20px)',
        transition: `all 0.6s cubic-bezier(0.4, 0, 0.2, 1) ${delay}ms`,
      }}
      styles={{ body: { padding: '24px' } }}
    >
      {/* 背景装饰 */}
      <div style={{
        position: 'absolute', right: -20, top: -20,
        width: 100, height: 100,
        borderRadius: '50%',
        background: `radial-gradient(circle, ${color}10 0%, transparent 70%)`,
      }} />
      
      <div style={{
        position: 'absolute', right: 16, top: 16,
        width: 52, height: 52,
        borderRadius: '14px',
        background: `linear-gradient(135deg, ${color}20 0%, ${color}10 100%)`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: '24px',
        boxShadow: `0 4px 12px ${color}25`,
        zIndex: 1,
      }}>
        {icon}
      </div>
      
      <Text style={{ 
        fontSize: '13px', 
        color: '#666', 
        display: 'block', 
        marginBottom: 12,
        fontWeight: 500,
      }}>
        {title}
      </Text>
      
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
        <span style={{ 
          fontSize: '32px', 
          fontWeight: 700, 
          color: color,
          fontFamily: '"SF Mono", Monaco, monospace',
        }}>
          {animatedValue}
        </span>
        {suffix && (
          <span style={{ fontSize: '16px', color: color, fontWeight: 500 }}>
            {suffix}
          </span>
        )}
      </div>
      
      {/* 底部装饰线 */}
      <div style={{
        position: 'absolute', bottom: 0, left: 0, right: 0,
        height: 3,
        background: `linear-gradient(90deg, ${color} 0%, ${color}60 50%, transparent 100%)`,
        opacity: 0.6,
      }} />
    </Card>
  );
};

// 带动效的功能特性卡片
const AnimatedFeatureCard: React.FC<{
  title: string;
  desc: string;
  icon: string;
  color: string;
  delay?: number;
}> = ({ title, desc, icon, color, delay = 0 }) => {
  const [isHovered, setIsHovered] = useState(false);
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => setIsVisible(true), delay);
    return () => clearTimeout(timer);
  }, [delay]);

  return (
    <Card
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      style={{
        borderRadius: '16px',
        border: isHovered ? `1px solid ${color}40` : '1px solid var(--glass-border)',
        background: isHovered
          ? `linear-gradient(135deg, ${color}08 0%, var(--glass-bg) 100%)`
          : 'var(--glass-bg)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        boxShadow: isHovered 
          ? `0 12px 32px ${color}20, var(--glass-glow-primary)` 
          : 'var(--glass-shadow)',
        transition: 'all 0.4s cubic-bezier(0.4, 0, 0.2, 1)',
        cursor: 'default',
        transform: isHovered ? 'translateY(-4px)' : 'translateY(0)',
        opacity: isVisible ? 1 : 0,
        overflow: 'hidden',
        position: 'relative',
      }}
      styles={{ body: { padding: '24px' } }}
    >
      {/* 悬停时的背景渐变 */}
      <div style={{
        position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
        background: isHovered 
          ? `linear-gradient(135deg, ${color}08 0%, transparent 60%)` 
          : 'transparent',
        transition: 'all 0.4s ease',
        pointerEvents: 'none',
      }} />
      
      {/* 右上角装饰 */}
      <div style={{
        position: 'absolute', top: -30, right: -30,
        width: 100, height: 100,
        borderRadius: '50%',
        background: `radial-gradient(circle, ${color}15 0%, transparent 70%)`,
        opacity: isHovered ? 1 : 0.5,
        transition: 'opacity 0.4s ease',
      }} />
      
      <div style={{
        width: 56, height: 56,
        borderRadius: '16px',
        background: `linear-gradient(135deg, ${color} 0%, ${color}dd 100%)`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: '26px',
        marginBottom: 16,
        boxShadow: isHovered 
          ? `0 12px 28px ${color}50` 
          : `0 8px 20px ${color}40`,
        transform: isHovered ? 'scale(1.05) rotate(-2deg)' : 'scale(1) rotate(0)',
        transition: 'all 0.4s cubic-bezier(0.4, 0, 0.2, 1)',
        position: 'relative',
        zIndex: 1,
      }}>
        {icon}
      </div>
      
      <Title level={5} style={{ 
        margin: '0 0 10px', 
        fontSize: '16px', 
        color: '#1a1a2e',
        fontWeight: 600,
        position: 'relative',
        zIndex: 1,
      }}>
        {title}
      </Title>
      
      <Paragraph style={{ 
        margin: 0, 
        color: '#666', 
        fontSize: '14px', 
        lineHeight: 1.7,
        position: 'relative',
        zIndex: 1,
      }}>
        {desc}
      </Paragraph>
      
      {/* 底部渐变线 */}
      <div style={{
        position: 'absolute', bottom: 0, left: 0,
        width: isHovered ? '100%' : '0%',
        height: 3,
        background: `linear-gradient(90deg, ${color} 0%, ${color}60 100%)`,
        transition: 'width 0.4s cubic-bezier(0.4, 0, 0.2, 1)',
      }} />
    </Card>
  );
};

const Welcome: React.FC = () => {
  const { token } = theme.useToken();
  const currentUser = useCurrentUser();
  const username = currentUser?.nickname || currentUser?.username || '管理员';
  const isMobile = useIsMobile();

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
        styles={{ body: { padding: isMobile ? '24px 20px' : '40px 48px' } }}
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
            Harnax 是一个整合了多种大模型、工具、MCP、Skills 的智能体平台，为企业和开发者提供一站式的解决方案。
          </Paragraph>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
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
          <AnimatedStatCard title="已集成模型" value={12} suffix="个" color="#4f6ef7" icon="🤖" delay={0} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <AnimatedStatCard title="工具数量" value={48} suffix="个" color="#52c41a" icon="🛠️" delay={100} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <AnimatedStatCard title="今日请求" value={1024} color="#fa8c16" icon="📊" delay={200} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <AnimatedStatCard title="活跃用户" value={256} color="#eb2f96" icon="👥" delay={300} />
        </Col>
      </Row>

      {/* 功能特性卡片 */}
      <Card
        style={{
          borderRadius: '16px',
          background: 'var(--glass-bg)',
          backdropFilter: 'blur(16px)',
          WebkitBackdropFilter: 'blur(16px)',
          border: '1px solid var(--glass-border)',
          boxShadow: 'var(--glass-shadow)',
        }}
        styles={{ body: { padding: isMobile ? '16px 16px 8px' : '28px 28px 8px' } }}
      >
        <Title level={5} style={{ margin: '0 0 20px', fontSize: '17px', color: '#1a1a2e' }}>
          🚀 平台能力
        </Title>
        <Row gutter={[20, 20]}>
          {features.map((f, index) => (
            <Col xs={24} sm={12} lg={8} key={f.title}>
              <AnimatedFeatureCard {...f} delay={index * 100} />
            </Col>
          ))}
        </Row>
      </Card>
    </PageContainer>
  );
};

export default Welcome;
