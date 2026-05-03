import React from 'react';
import { Button, Tooltip } from 'antd';
import { CaretRightOutlined, PauseOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

export interface StartPauseButtonProps {
  status: number; // 0: 停止, 1: 运行中
  onToggle: (newStatus: number) => void;
  tooltip?: string;
  startColor?: string;
  pauseColor?: string;
  size?: 'small' | 'middle' | 'large';
  showText?: boolean;
  stopPropagation?: boolean;
}

/**
 * 启动/暂停切换按钮组件
 * 用于定时任务等需要启动/暂停控制的场景
 */
const StartPauseButton: React.FC<StartPauseButtonProps> = ({
  status,
  onToggle,
  tooltip,
  startColor = 'var(--vip-success)',
  pauseColor = 'var(--vip-warning)',
  size = 'small',
  showText = false,
  stopPropagation = true,
}) => {
  const intl = useIntl();
  const isRunning = status === 1;

  const tooltipText = tooltip || (isRunning
    ? intl.formatMessage({ id: 'pages.job.pause', defaultMessage: 'Pause' })
    : intl.formatMessage({ id: 'pages.job.start', defaultMessage: 'Start' })
  );

  const buttonText = isRunning
    ? intl.formatMessage({ id: 'pages.job.pause', defaultMessage: 'Pause' })
    : intl.formatMessage({ id: 'pages.job.start', defaultMessage: 'Start' });

  const handleClick = (e: React.MouseEvent) => {
    if (stopPropagation) {
      e.stopPropagation();
    }
    // 切换状态: 0 -> 1, 1 -> 0
    onToggle(isRunning ? 0 : 1);
  };

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={isRunning ? <PauseOutlined /> : <CaretRightOutlined />}
        onClick={handleClick}
        style={{ color: isRunning ? pauseColor : startColor, padding: '4px' }}
      >
        {showText && buttonText}
      </Button>
    </Tooltip>
  );
};

export default StartPauseButton;
