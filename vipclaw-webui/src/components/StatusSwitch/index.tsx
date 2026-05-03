import React from 'react';
import { Switch } from 'antd';
import { useIntl } from '@umijs/max';

/**
 * 启停开关组件属性
 */
export interface StatusSwitchProps {
  /** 当前状态 (1: 启用, 0: 禁用) */
  status: number;
  /** 状态切换回调 */
  onChange: (newStatus: number) => void;
  /** 是否禁用 */
  disabled?: boolean;
  /** 自定义启用状态颜色 */
  activeColor?: string;
  /** 自定义禁用状态颜色 */
  inactiveColor?: string;
  /** 自定义样式 */
  style?: React.CSSProperties;
}

/**
 * 统一的启停开关组件
 * 
 * 文案统一为"启用/停用",支持国际化
 * 
 * @example
 * // 基础用法
 * <StatusSwitch
 *   status={record.status}
 *   onChange={(newStatus) => handleToggle(record.id, newStatus)}
 * />
 * 
 * @example
 * // 禁用状态
 * <StatusSwitch
 *   status={record.status}
 *   onChange={handleToggle}
 *   disabled
 * />
 */
const StatusSwitch: React.FC<StatusSwitchProps> = ({
  status,
  onChange,
  disabled = false,
  activeColor = 'var(--vip-primary)',
  inactiveColor = 'var(--vip-border)',
  style,
}) => {
  const intl = useIntl();

  return (
    <Switch
      checked={status === 1}
      onChange={(checked) => onChange(checked ? 1 : 0)}
      checkedChildren={intl.formatMessage({
        id: 'pages.common.enabled',
        defaultMessage: '启用',
      })}
      unCheckedChildren={intl.formatMessage({
        id: 'pages.common.disabled',
        defaultMessage: '停用',
      })}
      disabled={disabled}
      style={{
        backgroundColor: status === 1 ? activeColor : inactiveColor,
        fontSize: '10px',
        border: 'none',
        ...style,
      }}
    />
  );
};

export default StatusSwitch;
