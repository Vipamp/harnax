import React from 'react';
import { Button, Tooltip } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 详情按钮组件属性
 */
export interface DetailButtonProps {
  /** 点击详情按钮后的回调 */
  onClick: () => void;
  /** 自定义提示文本 */
  tooltip?: string;
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
 * 统一的详情按钮组件
 * 
 * 用于查看详细信息
 * 
 * @example
 * // 基础用法(仅图标)
 * <DetailButton onClick={() => handleViewDetail(record)} />
 * 
 * @example
 * // 带文字
 * <DetailButton 
 *   onClick={handleViewDetail} 
 *   showText 
 * />
 * 
 * @example
 * // 自定义提示文本
 * <DetailButton 
 *   onClick={handleViewDetail} 
 *   tooltip="查看详情" 
 * />
 */
const DetailButton: React.FC<DetailButtonProps> = ({
  onClick,
  tooltip,
  size = 'small',
  showText = false,
  text,
  stopPropagation = true,
}) => {
  const intl = useIntl();

  const tooltipText = tooltip || intl.formatMessage({
    id: 'pages.common.detail',
    defaultMessage: 'Detail',
  });

  const buttonText = text || intl.formatMessage({
    id: 'pages.common.detail',
    defaultMessage: '详情',
  });

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={<InfoCircleOutlined />}
        onClick={(e) => {
          if (stopPropagation) e.stopPropagation();
          onClick();
        }}
        style={{ padding: '4px' }}
      >
        {showText && buttonText}
      </Button>
    </Tooltip>
  );
};

export default DetailButton;
