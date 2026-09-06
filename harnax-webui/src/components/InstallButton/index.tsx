import React from 'react';
import { Button, Tooltip } from 'antd';
import { CloudDownloadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 技能源重新安装按钮组件属性
 */
export interface InstallButtonProps {
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
 * 统一的技能源重新安装按钮组件
 *
 * 与 SyncButton 的区别：SyncButton 打开勾选弹窗做增量同步，
 * 本按钮直接触发全量重装（POST /skill-sources/{id}/install），
 * 用源里的最新内容覆盖已落库的技能。
 *
 * @example
 * // 基础用法(仅图标)
 * <InstallButton onClick={() => handleInstall(repository)} />
 *
 * @example
 * // 加载中状态
 * <InstallButton onClick={handleInstall} loading />
 */
const InstallButton: React.FC<InstallButtonProps> = ({
  onClick,
  tooltip,
  color = '#722ed1',
  size = 'small',
  showText = false,
  text,
  stopPropagation = true,
  loading = false,
}) => {
  const intl = useIntl();

  const tooltipText =
    tooltip ||
    intl.formatMessage({
      id: 'pages.skill.repository.install',
      defaultMessage: 'Reinstall from source',
    });

  const buttonText =
    text ||
    intl.formatMessage({
      id: 'pages.skill.repository.install',
      defaultMessage: '重新安装',
    });

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={<CloudDownloadOutlined spin={loading} />}
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

export default InstallButton;
