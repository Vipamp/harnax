import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { Card, Switch, Tag, Typography, Tooltip, Popover, List } from 'antd';
import { DeleteOutlined, EditOutlined, ExperimentOutlined } from '@ant-design/icons';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import DeleteButton from '@/components/DeleteButton';

const { Text } = Typography;

/**
 * 标签项
 */
export interface TagItem {
  label: string;
  color?: string;
  icon?: React.ReactNode;
}

/**
 * 统计指标项
 */
export interface StatItem {
  label: string;
  value: number | string;
  color?: string;
  /** Popover 内容（hover 时显示的列表，可选） */
  popoverContent?: React.ReactNode;
  /** Popover 最大宽度（默认 320px） */
  popoverMaxWidth?: number;
}

/**
 * 操作按钮配置
 */
export interface ActionConfig {
  showTest?: boolean;
  showEdit?: boolean;
  showDelete?: boolean;
  onTest?: () => void;
  onEdit?: () => void;
  onDelete?: () => void;
}

/**
 * 实体卡片属性
 * 
 * @important 颜色配置规范：
 * - tagColor 必须使用十六进制颜色值（如 '#4f6ef7'）
 * - 不能使用 CSS 变量（如 'var(--vip-primary)'）
 * - 原因：组件内部需要通过模板字符串拼接透明度（如 '#4f6ef715'）
 * - CSS 变量无法与透明度后缀拼接，会导致样式失效
 */
export interface EntityCardProps<T = any> {
  /** 实体数据 */
  entity: T;
  /** 卡片索引（用于动画延迟） */
  index: number;
  /** 图标（ReactNode） */
  icon: React.ReactNode;
  /** 实体名称 */
  name: string;
  /** 类型标签文本 */
  tagLabel: string;
  /** 类型标签颜色 */
  tagColor: string;
  /** 类型标签背景色（hover 时） */
  tagBgHover?: string;
  /** 额外标签列表（显示在 type 标签右边，可选） */
  tags?: TagItem[];
  /** 描述信息（字符串或自定义 ReactNode） */
  description?: string | React.ReactNode;
  /** 状态（0:禁用, 1:启用） */
  status: number;
  /** 是否公开（0:私有, 1:公开） */
  isPublic?: number;
  /** 创建人 */
  creator?: string;
  /** 创建时间 */
  createTime?: string;
  /** 统计指标列表 */
  stats?: StatItem[];
  /** 操作按钮配置 */
  actions: ActionConfig;
  /** 状态切换回调 */
  onToggle: (id: number, status: number) => void;
  /** 卡片点击回调（可选） */
  onClick?: () => void;
  /** 底部自定义渲染（在分割线和底部信息栏之间，可选） */
  renderExtraBottom?: React.ReactNode;
}

/**
 * 统一实体卡片组件
 * 
 * 布局结构：
 * - 顶部：渐变标识条
 * - 头部：图标 + 名称 + 类型标签 + 启停开关（右上角）
 * - 中部：描述信息（无描述显示"暂无描述"）
 * - 统计区：统计指标网格（可选）
 * - 底部：分割线 + 左侧（公开标签、创建人、创建时间）+ 右侧（操作按钮）
 */
const EntityCard: React.FC<EntityCardProps> = ({
  entity,
  index,
  icon,
  name,
  tagLabel,
  tagColor,
  tagBgHover,
  tags,
  description,
  status,
  isPublic,
  creator,
  createTime,
  stats,
  actions,
  onToggle,
  onClick,
  renderExtraBottom,
}) => {
  const intl = useIntl();
  const [isHovered, setIsHovered] = useState(false);
  const [config, setConfig] = useState({
    padding: '20px',
    iconSize: 52,
    titleSize: 'clamp(14px, 1.8vw, 15px)',
    statValueSize: 'clamp(22px, 2.5vw, 24px)',
    statLabelSize: 'clamp(11px, 1.4vw, 12px)',
  });

  const { isAdmin } = getCurrentUserInfo();
  const currentUser = typeof window !== 'undefined' ? localStorage.getItem('username') || '' : '';

  // 响应式配置
  useEffect(() => {
    const updateConfig = () => {
      const width = window.innerWidth;
      if (width < 576) {
        setConfig({ padding: '14px', iconSize: 40, titleSize: 'clamp(13px, 3vw, 14px)', statValueSize: 'clamp(18px, 4vw, 20px)', statLabelSize: 'clamp(9px, 2vw, 10px)' });
      } else if (width < 768) {
        setConfig({ padding: '16px', iconSize: 44, titleSize: 'clamp(13px, 2.5vw, 14px)', statValueSize: 'clamp(20px, 3.5vw, 22px)', statLabelSize: 'clamp(10px, 1.8vw, 11px)' });
      } else if (width < 992) {
        setConfig({ padding: '18px', iconSize: 48, titleSize: 'clamp(14px, 2vw, 15px)', statValueSize: 'clamp(22px, 3vw, 24px)', statLabelSize: 'clamp(10px, 1.6vw, 11px)' });
      } else if (width < 1200) {
        setConfig({ padding: '20px', iconSize: 52, titleSize: 'clamp(14px, 1.8vw, 15px)', statValueSize: 'clamp(22px, 2.5vw, 24px)', statLabelSize: 'clamp(11px, 1.4vw, 12px)' });
      } else if (width < 1600) {
        setConfig({ padding: '20px', iconSize: 52, titleSize: 'clamp(14px, 1.5vw, 15px)', statValueSize: 'clamp(22px, 2vw, 24px)', statLabelSize: 'clamp(11px, 1.2vw, 12px)' });
      } else {
        setConfig({ padding: '22px', iconSize: 56, titleSize: 'clamp(15px, 1.2vw, 16px)', statValueSize: 'clamp(24px, 1.8vw, 26px)', statLabelSize: 'clamp(11px, 1vw, 12px)' });
      }
    };

    updateConfig();
    window.addEventListener('resize', updateConfig);
    return () => window.removeEventListener('resize', updateConfig);
  }, []);

  // 确保 hover 时背景色有透明度
  const hoverBg = tagBgHover || tagColor;

  return (
    <Card
      style={{
        borderRadius: '20px',
        border: isHovered ? `1px solid ${tagColor}40` : '1px solid var(--glass-border)',
        boxShadow: isHovered 
          ? `0 20px 40px ${tagColor}25, var(--glass-glow-primary)`
          : 'var(--glass-shadow)',
        overflow: 'hidden',
        position: 'relative',
        transition: 'all 0.5s cubic-bezier(0.4, 0, 0.2, 1)',
        transform: isHovered ? 'translateY(-8px) scale(1.02)' : 'translateY(0) scale(1)',
        animation: `vipSlideUp 0.6s ease-out ${index * 60}ms both`,
        cursor: onClick ? 'pointer' : 'default',
        width: '100%',
        minHeight: '280px',
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--glass-bg)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
      }}
      styles={{ body: { padding: 0, flex: 1, display: 'flex', flexDirection: 'column' } }}
      onClick={onClick}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      {/* 顶部渐变标识条 */}
      <div
        style={{
          height: '5px',
          background: `linear-gradient(135deg, ${tagColor} 0%, ${tagColor}aa 50%, ${tagColor}66 100%)`,
          backgroundSize: '200% 100%',
          animation: isHovered ? 'gradientShift 3s ease-in-out infinite' : 'none',
          position: 'relative',
        }}
      >
        {/* 光泽效果 */}
        <div
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'linear-gradient(90deg, transparent 0%, rgba(255,255,255,0.3) 50%, transparent 100%)',
            transform: isHovered ? 'translateX(100%)' : 'translateX(-100%)',
            transition: 'transform 0.6s ease',
          }}
        />
      </div>

      <div style={{ padding: config.padding, flex: 1, display: 'flex', flexDirection: 'column', position: 'relative' }}>
        {/* 头部：图标 + 名称 + 类型标签 + 启停开关 */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14, marginBottom: 14 }}>
          <div
            style={{
              width: config.iconSize,
              height: config.iconSize,
              borderRadius: '16px',
              background: isHovered 
                ? `linear-gradient(135deg, ${tagColor} 0%, ${tagColor}cc 100%)`
                : `linear-gradient(135deg, ${tagColor}15 0%, ${tagColor}10 100%)`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: config.iconSize * 0.48,
              color: isHovered ? '#fff' : tagColor,
              flexShrink: 0,
              transition: 'all 0.4s cubic-bezier(0.4, 0, 0.2, 1)',
              boxShadow: isHovered ? `0 12px 24px ${tagColor}40` : `0 4px 12px ${tagColor}20`,
              border: `1px solid ${tagColor}${isHovered ? '00' : '30'}`,
            }}
          >
            {icon}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <Text 
                strong 
                style={{ 
                  fontSize: config.titleSize, 
                  color: 'var(--vip-text-primary)',
                  letterSpacing: '-0.01em',
                  lineHeight: 1.3,
                }}
              >
                {name}
              </Text>
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {/* Type 标签 */}
              {tagLabel && (
                <Tag
                  style={{
                    background: isHovered 
                      ? `linear-gradient(135deg, ${hoverBg} 0%, ${hoverBg}dd 100%)` 
                      : `linear-gradient(135deg, ${tagColor}18 0%, ${tagColor}12 100%)`,
                    color: isHovered ? '#fff' : tagColor,
                    border: `1px solid ${tagColor}${isHovered ? '00' : '35'}`,
                    borderRadius: '8px',
                    fontSize: config.statLabelSize,
                    fontWeight: 600,
                    padding: '3px 12px',
                    transition: 'all 0.3s ease',
                    boxShadow: isHovered ? `0 4px 12px ${tagColor}30` : 'none',
                  }}
                >
                  {tagLabel}
                </Tag>
              )}
              {/* 额外标签 */}
              {tags && tags.map((tag, idx) => (
                <Tag
                  key={idx}
                  color={tag.color}
                  icon={tag.icon}
                  style={{
                    borderRadius: '8px',
                    fontSize: config.statLabelSize,
                    fontWeight: 500,
                    padding: '3px 10px',
                    margin: 0,
                  }}
                >
                  {tag.label}
                </Tag>
              ))}
            </div>
          </div>
          {/* 状态开关 - 右上角 */}
          <div onClick={(e) => e.stopPropagation()}>
            <Switch
              checked={status === 1}
              onChange={() => onToggle(entity.id, status === 1 ? 0 : 1)}
              checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
              unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
              style={{
                backgroundColor: status === 1 ? tagColor : 'var(--vip-border)',
                boxShadow: status === 1 ? `0 2px 8px ${tagColor}30` : 'none',
              }}
            />
          </div>
        </div>

        {/* 描述信息区域 - 固定高度 */}
        <div 
          style={{
            marginBottom: '14px',
            // 有指标时：40px，无指标时：60px
            height: stats && stats.length > 0 ? '40px' : '60px',
            overflow: 'hidden',
            width: '100%',
            position: 'relative',
          }}
        >
          <Tooltip 
            title={typeof description === 'string' && description && description.trim() !== '' ? description : undefined}
            placement="topLeft"
            mouseEnterDelay={0.3}
            overlayStyle={{ 
              maxWidth: '400px',
              fontSize: '12px',
              lineHeight: 1.6,
            }}
          >
            {/* 判断 description 类型 */}
            {typeof description === 'string' ? (
              <div
                style={{
                  width: '100%',
                  overflow: 'hidden',
                  display: '-webkit-box',
                  WebkitLineClamp: stats && stats.length > 0 ? 2 : 3,
                  WebkitBoxOrient: 'vertical',
                  textOverflow: 'ellipsis',
                }}
              >
                <Text 
                  type="secondary"
                  style={{ 
                    fontSize: '12px',
                    color: description && description.trim() !== '' 
                      ? 'var(--vip-text-secondary)' 
                      : 'var(--vip-text-tertiary)',
                    lineHeight: 1.5,
                    wordBreak: 'break-word',
                    cursor: description && description.trim() !== '' ? 'pointer' : 'default',
                    display: 'inline',
                  }}
                >
                  {description && description.trim() !== '' 
                    ? description 
                    : intl.formatMessage({ 
                        id: 'pages.common.noDescription', 
                        defaultMessage: '暂无描述' 
                      })
                  }
                </Text>
              </div>
            ) : (
              <div 
                style={{
                  width: '100%',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  display: '-webkit-box',
                  WebkitLineClamp: stats && stats.length > 0 ? 2 : 3,
                  WebkitBoxOrient: 'vertical',
                }}
              >
                {description || (
                  <Text 
                    type="secondary"
                    style={{ 
                      fontSize: '12px',
                      color: 'var(--vip-text-tertiary)',
                      lineHeight: 1.5,
                    }}
                  >
                    {intl.formatMessage({ 
                      id: 'pages.common.noDescription', 
                      defaultMessage: '暂无描述' 
                    })}
                  </Text>
                )}
              </div>
            )}
          </Tooltip>
        </div>

        {/* 统计指标 */}
        {stats && stats.length > 0 && (
          <div 
            style={{
              display: 'grid',
              // auto-fit + minmax：窄屏自动换行，避免多指标（如 5 个）被挤压变形
              gridTemplateColumns: `repeat(auto-fit, minmax(64px, 1fr))`,
              gap: '8px',
              marginBottom: '12px',
            }}
          >
            {stats.map((stat, idx) => {
              const statContent = (
                <div
                  style={{
                    background: `linear-gradient(135deg, ${stat.color || tagColor}06 0%, ${stat.color || tagColor}03 100%)`,
                    borderRadius: '10px',
                    padding: '8px 6px',
                    textAlign: 'center',
                    border: `1px solid ${stat.color || tagColor}15`,
                    transition: 'all 0.3s ease',
                    transform: isHovered ? 'translateY(-2px)' : 'translateY(0)',
                    cursor: stat.popoverContent ? 'pointer' : 'default',
                  }}
                >
                  <div 
                    style={{ 
                      fontSize: config.statLabelSize, 
                      color: 'var(--vip-text-tertiary)',
                      marginBottom: '4px',
                      fontWeight: 500,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {stat.label}
                  </div>
                  <div 
                    style={{ 
                      fontSize: config.statValueSize, 
                      fontWeight: 800, 
                      color: stat.color || 'var(--vip-text-primary)',
                      lineHeight: 1,
                      letterSpacing: '-0.02em',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {stat.value}
                  </div>
                </div>
              );

              // 如果有 popoverContent，使用 Popover 包裹
              if (stat.popoverContent) {
                return (
                  <Popover
                    key={idx}
                    content={stat.popoverContent}
                    title={null}
                    trigger="hover"
                    placement="bottom"
                    overlayStyle={{ maxWidth: stat.popoverMaxWidth || 320 }}
                  >
                    {statContent}
                  </Popover>
                );
              }

              // 否则使用 Tooltip
              return (
                <Tooltip 
                  key={idx}
                  title={`${stat.label}: ${stat.value}`}
                  placement="top"
                >
                  {statContent}
                </Tooltip>
              );
            })}
          </div>
        )}

        {/* 底部自定义区域（可选，在分割线之前） */}
        {renderExtraBottom && (
          <div style={{ marginBottom: '8px', flex: '0 0 auto' }}>
            {renderExtraBottom}
          </div>
        )}

        {/* 底部：分割线 + 信息 + 操作按钮 */}
        <div style={{ 
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'space-between',
          gap: '10px',
          paddingTop: '10px',
          borderTop: '1px solid var(--vip-border)',
          marginTop: 'auto',
          position: 'relative',
        }}>
          {/* 分隔线光泽效果 */}
          <div
            style={{
              position: 'absolute',
              top: '-1px',
              left: 0,
              width: isHovered ? '100%' : '0%',
              height: '1px',
              background: `linear-gradient(90deg, transparent, ${tagColor}, transparent)`,
              transition: 'width 0.5s ease',
            }}
          />
          
          {/* 左侧：公开标签、创建人、创建时间 */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flex: 1 }}>
            {isPublic !== undefined && (
              <Tag
                color={isPublic === 1 ? 'green' : 'default'}
                style={{ 
                  margin: 0,
                  fontSize: '11px',
                  padding: '2px 8px',
                }}
              >
                {isPublic === 1 
                  ? intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })
                  : intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })
                }
              </Tag>
            )}
            {creator && (
              <Text type="secondary" style={{ fontSize: '11px' }}>
                {creator}
              </Text>
            )}
            {createTime && (
              <Text type="secondary" style={{ fontSize: '11px' }}>
                {createTime?.replace('T', ' ').substring(0, 16)}
              </Text>
            )}
          </div>

          {/* 右侧：操作按钮 */}
          <div onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', gap: '8px' }}>
              {actions.showTest && (
                <Tooltip title={intl.formatMessage({ id: 'pages.common.connectivityTest', defaultMessage: 'Connectivity Test' })}>
                  <ExperimentOutlined
                    onClick={actions.onTest}
                    style={{
                      fontSize: '16px',
                      color: 'var(--vip-warning)',
                      cursor: 'pointer',
                      transition: 'all 0.3s ease',
                    }}
                  />
                </Tooltip>
              )}
              {actions.showEdit && hasOperationPermission(isAdmin, currentUser, entity.creator) && (
                <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
                  <EditOutlined
                    onClick={actions.onEdit}
                    style={{
                      fontSize: '16px',
                      color: 'var(--vip-primary)',
                      cursor: 'pointer',
                      transition: 'all 0.3s ease',
                    }}
                  />
                </Tooltip>
              )}
              {actions.showDelete && hasOperationPermission(isAdmin, currentUser, entity.creator) && (
                <DeleteButton onConfirm={actions.onDelete!} />
              )}
            </div>
          </div>
        </div>
      </div>
    </Card>
  );
};

export default EntityCard;
