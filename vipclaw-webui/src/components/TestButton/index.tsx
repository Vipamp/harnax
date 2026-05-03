import React from 'react';
import { Button, Tooltip } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 连接测试按钮组件属性
 */
export interface TestButtonProps {
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
 * 统一的连接测试按钮组件
 * 
 * 用于 MCP 等服务的连通性测试
 * 
 * @example
 * // 基础用法(仅图标)
 * <TestButton onClick={() => handleTest(record.id)} />
 * 
 * @example
 * // 带文字
 * <TestButton 
 *   onClick={handleTest} 
 *   showText 
 * />
 * 
 * @example
 * // 自定义颜色
 * <TestButton 
 *   onClick={handleTest} 
 *   color="#faad14" 
 * />
 */
const TestButton: React.FC<TestButtonProps> = ({
  onClick,
  tooltip,
  color = 'var(--vip-warning)',
  size = 'small',
  showText = false,
  text,
  stopPropagation = true,
}) => {
  const intl = useIntl();

  const tooltipText = tooltip || intl.formatMessage({
    id: 'pages.common.test',
    defaultMessage: 'Test',
  });

  const buttonText = text || intl.formatMessage({
    id: 'pages.common.test',
    defaultMessage: '测试',
  });

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={<ThunderboltOutlined />}
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

export default TestButton;
