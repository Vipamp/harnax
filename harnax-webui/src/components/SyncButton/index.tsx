import React from 'react';
import { Button, Tooltip } from 'antd';
import { SyncOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 仓库同步按钮组件属性
 */
export interface SyncButtonProps {
  /** 点击回调 */
  onClick: () => void;
  /** 自定义提示文本 */
  tooltip?: string;
  /** 自定义颜色 */
  color?: string;
  /** 按钮尺寸 */
  size?: 'small' | 'middle' | 'large';
  /** 是否显示文字 */
  showText?: boolean;
  /** 按钮文字 */
  text?: string;
  /** 阻止事件冒泡 */
  stopPropagation?: boolean;
  /** 是否加载中 */
  loading?: boolean;
}

/**
 * 统一的仓库同步按钮组件
 * 
 * 用于 Skill 仓库的同步操作
 * 
 * @example
 * // 基础用法(仅图标)
 * <SyncButton onClick={() => handleSync(repository)} />
 * 
 * @example
 * // 带文字
 * <SyncButton 
 *   onClick={handleSync} 
 *   showText 
 * />
 * 
 * @example
 * // 加载中状态
 * <SyncButton 
 *   onClick={handleSync} 
 *   loading 
 * />
 */
const SyncButton: React.FC<SyncButtonProps> = ({
  onClick,
  tooltip,
  color = '#1890ff',
  size = 'small',
  showText = false,
  text,
  stopPropagation = true,
  loading = false,
}) => {
  const intl = useIntl();

  const tooltipText = tooltip || intl.formatMessage({
    id: 'pages.skill.repository.sync',
    defaultMessage: 'Sync',
  });

  const buttonText = text || intl.formatMessage({
    id: 'pages.skill.repository.sync',
    defaultMessage: '同步',
  });

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={<SyncOutlined spin={loading} />}
        onClick={(e) => {
          if (stopPropagation) e.stopPropagation();
          onClick();
        }}
        style={{ color, padding: '4px' }}
        loading={loading}
      >
        {showText && buttonText}
      </Button>
    </Tooltip>
  );
};

export default SyncButton;
