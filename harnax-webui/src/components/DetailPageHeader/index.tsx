import React from 'react';
import { Card, Tag, Typography, Space } from 'antd';
import { 
  LinkOutlined, 
  UserOutlined, 
  ClockCircleOutlined 
} from '@ant-design/icons';

const { Text } = Typography;

export interface DetailPageHeaderProps {
  /** 图标组件 */
  icon: React.ReactNode;
  /** 图标背景渐变色 */
  iconGradient: string;
  /** 图标阴影颜色 */
  iconShadowColor?: string;
  /** 名称 */
  name: string;
  /** 仓库URL（可选） */
  repositoryUrl?: string;
  /** 仓库名称（可选） */
  repositoryName?: string;
  /** 仓库分支（可选） */
  repositoryBranch?: string;
  /** 标签列表 */
  tags: Array<{
    color: string;
    label: string;
  }>;
  /** 信息项列表（键值对形式） */
  infoItems?: Array<{
    label: string;      // 标签（已国际化）
    value: string;      // 值
    icon?: React.ReactNode; // 可选图标
  }>;
  /** 创建人 */
  creator?: string;
  /** 更新时间 */
  updateTime?: string;
  /** 创建时间 */
  createTime?: string;
  /** 下边距 */
  marginBottom?: number;
  /** 国际化函数 */
  intl: any;
}

/**
 * 详情页头部信息卡片 - 公共组件
 * 用于 Skill 和 MCP 等详情页的头部信息展示
 */
const DetailPageHeader: React.FC<DetailPageHeaderProps> = ({
  icon,
  iconGradient,
  iconShadowColor = 'rgba(114, 46, 209, 0.2)',
  name,
  repositoryUrl,
  repositoryName,
  repositoryBranch,
  tags,
  infoItems,
  creator,
  updateTime,
  createTime,
  marginBottom = 16,
  intl
}) => {
  // 优先使用 updateTime，其次使用 createTime
  const displayTime = updateTime || createTime;

  return (
    <Card
      style={{
        marginBottom,
        borderRadius: '12px',
        border: '1px solid var(--vip-border)',
        boxShadow: 'var(--vip-shadow-sm)',
        overflow: 'hidden'
      }}
      styles={{ body: { padding: 0 } }}
    >
      {/* 顶部标题栏 */}
      <div style={{ 
        padding: '12px 16px', 
        background: 'var(--vip-primary-light)',
        borderBottom: '1px solid var(--vip-border)'
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {/* 左侧：图标 + 名称 + 仓库 */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{
              width: 36,
              height: 36,
              borderRadius: '8px',
              background: iconGradient,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: `0 2px 8px ${iconShadowColor}`
            }}>
              {icon}
            </div>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
                <Text strong style={{ fontSize: 15, color: 'var(--vip-text-primary)' }}>
                  {name}
                </Text>
                {/* 仓库链接 - 紧跟名称 */}
                {repositoryUrl && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <LinkOutlined style={{ color: 'var(--vip-text-tertiary)', fontSize: 11 }} />
                    <a
                      href={repositoryBranch 
                        ? `${repositoryUrl}/tree/${repositoryBranch}`
                        : repositoryUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{ 
                        color: 'var(--vip-primary)',
                        textDecoration: 'none',
                        fontSize: 11,
                        fontWeight: 500
                      }}
                    >
                      {repositoryName || repositoryUrl.split('/').pop()}
                    </a>
                    {repositoryBranch && (
                      <Tag color="blue" style={{ margin: 0, fontSize: 10, borderRadius: '3px', padding: '0 4px' }}>
                        {repositoryBranch}
                      </Tag>
                    )}
                  </div>
                )}
              </div>
              {/* 标签列表 */}
              {tags.length > 0 && (
                <div style={{ display: 'flex', gap: 6 }}>
                  {tags.map((tag, index) => (
                    <Tag 
                      key={index}
                      color={tag.color} 
                      style={{ borderRadius: '4px', fontWeight: 500, margin: 0, fontSize: 11 }}
                    >
                      {tag.label}
                    </Tag>
                  ))}
                </div>
              )}
            </div>
          </div>
          
          {/* 右侧：创建人和时间 */}
          {(creator || displayTime) && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexShrink: 0 }}>
              {creator && (
                <span style={{ fontSize: 11, color: 'var(--vip-text-tertiary)', display: 'flex', alignItems: 'center', gap: 4 }}>
                  <UserOutlined />
                  {creator}
                </span>
              )}
              {displayTime && (
                <span style={{ fontSize: 11, color: 'var(--vip-text-tertiary)', display: 'flex', alignItems: 'center', gap: 4 }}>
                  <ClockCircleOutlined />
                  {displayTime.replace('T', ' ').substring(0, 16)}
                </span>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 信息项列表 - 紧凑多行 */}
      {infoItems && infoItems.length > 0 && (
        <div style={{ padding: '8px 16px', borderBottom: '1px solid var(--vip-border)' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {infoItems.map((item, index) => (
              <div key={index} style={{ display: 'flex', alignItems: 'flex-start', gap: 6 }}>
                {item.icon && (
                  <span style={{ color: 'var(--vip-primary)', flexShrink: 0, marginTop: 1 }}>
                    {item.icon}
                  </span>
                )}
                <Text 
                  style={{ 
                    fontSize: 12, 
                    color: 'var(--vip-text-secondary)',
                    flex: 1,
                    lineHeight: '1.5'
                  }}
                >
                  <span style={{ color: 'var(--vip-text-tertiary)', marginRight: 4 }}>{item.label}：</span>
                  {item.value}
                </Text>
              </div>
            ))}
          </div>
        </div>
      )}
    </Card>
  );
};

export default DetailPageHeader;
