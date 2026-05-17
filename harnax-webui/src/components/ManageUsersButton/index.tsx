import React from 'react';
import { Button, Tooltip } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

export interface ManageUsersButtonProps {
  onClick: (e?: React.MouseEvent) => void;
  tooltip?: string;
  color?: string;
  size?: 'small' | 'middle' | 'large';
  showText?: boolean;
  text?: string;
  stopPropagation?: boolean;
}

/**
 * 管理用户按钮组件
 * 用于租户管理中管理租户下用户的场景
 */
const ManageUsersButton: React.FC<ManageUsersButtonProps> = ({
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
    id: 'pages.tenant.management.manageUsers',
    defaultMessage: 'Manage Users',
  });

  const buttonText = text || intl.formatMessage({
    id: 'pages.tenant.management.manageUsers',
    defaultMessage: 'Manage Users',
  });

  return (
    <Tooltip title={tooltipText}>
      <Button
        type="link"
        size={size}
        icon={<UserOutlined />}
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

export default ManageUsersButton;
