import React from 'react';
import { Button, Tooltip } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 编辑按钮组件属性
 */
export interface EditButtonProps {
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
}

/**
 * 统一的编辑按钮组件
 * 
 * @example
 * // 基础用法(仅图标)
 * <EditButton onClick={() => handleEdit(record)} />
 * 
 * @example
 * // 带文字
 * <EditButton 
 *   onClick={handleEdit} 
 *   showText 
 * />
 * 
 * @example
 * // 自定义颜色
 * <EditButton 
 *   onClick={handleEdit} 
 *   color="#52c41a" 
 * />
 */
const EditButton: React.FC<EditButtonProps> = ({
  onClick,
  tooltip,
  color = 'var(--vip-primary)',
  size = 'small',
  showText = false,
  text,
  stopPropagation = true,
}) => {
  const intl = useIntl();

  const tooltipText = tooltip || intl.formatMessage({
    id: 'pages.common.edit',
    defaultMessage: 'Edit',
  });

  const buttonText = text || intl.formatMessage({
    id: 'pages.common.edit',
    defaultMessage: '编辑',
  });

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={<EditOutlined />}
        onClick={(e) => {
          if (stopPropagation) e.stopPropagation();
          onClick();
        }}
        style={{ color, padding: '4px' }}
      >
        {showText && buttonText}
      </Button>
    </Tooltip>
  );
};

export default EditButton;
