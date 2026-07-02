import React from 'react';
import { Button, Tooltip } from 'antd';
import { PlayCircleOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

export interface RunButtonProps {
  onClick: (e?: React.MouseEvent) => void;
  tooltip?: string;
  color?: string;
  size?: 'small' | 'middle' | 'large';
  showText?: boolean;
  text?: string;
  stopPropagation?: boolean;
}

/**
 * 立即执行按钮组件
 * 用于任务管理等需要立即执行操作的场景
 */
const RunButton: React.FC<RunButtonProps> = ({
  onClick,
  tooltip,
  color = 'var(--vip-info)',
  size = 'small',
  showText = false,
  text,
  stopPropagation = true,
}) => {
  const intl = useIntl();

  const tooltipText = tooltip || intl.formatMessage({
    id: 'components.runButton.runOnce',
    defaultMessage: 'Run Once',
  });

  const buttonText = text || intl.formatMessage({
    id: 'components.runButton.runOnce',
    defaultMessage: 'Run Once',
  });

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={<PlayCircleOutlined />}
        onClick={(e) => {
          if (stopPropagation) {
            e.stopPropagation();
          }
          onClick(e);
        }}
        style={{ color, padding: '4px' }}
      >
        {showText && buttonText}
      </Button>
    </Tooltip>
  );
};

export default RunButton;
