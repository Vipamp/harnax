import React from 'react';
import { Button, Tooltip, Popconfirm } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 删除按钮组件属性
 */
export interface DeleteButtonProps {
  /** 删除确认后的回调 */
  onConfirm: () => void;
  /** 自定义确认标题 */
  confirmTitle?: string;
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
 * 统一的删除按钮组件
 * 
 * 自带 Popconfirm 确认弹窗
 * 
 * @example
 * // 基础用法(仅图标)
 * <DeleteButton onConfirm={() => handleDelete(record.id)} />
 * 
 * @example
 * // 带文字
 * <DeleteButton 
 *   onConfirm={handleDelete} 
 *   showText 
 * />
 * 
 * @example
 * // 自定义确认标题
 * <DeleteButton 
 *   onConfirm={handleDelete} 
 *   confirmTitle="确定要删除这个用户吗?" 
 * />
 */
const DeleteButton: React.FC<DeleteButtonProps> = ({
  onConfirm,
  confirmTitle,
  tooltip,
  size = 'small',
  showText = false,
  text,
  stopPropagation = true,
}) => {
  const intl = useIntl();

  const tooltipText = tooltip || intl.formatMessage({
    id: 'pages.common.delete',
    defaultMessage: 'Delete',
  });

  const confirmText = confirmTitle || intl.formatMessage({
    id: 'pages.message.deleteConfirm',
    defaultMessage: 'Are you sure to delete?',
  });

  const buttonText = text || intl.formatMessage({
    id: 'pages.common.delete',
    defaultMessage: '删除',
  });

  return (
    <Popconfirm
      title={confirmText}
      onConfirm={(e) => {
        if (e) {
          onConfirm();
        }
      }}
    >
      <Tooltip title={tooltipText}>
        <Button
          type="link"
          size={size}
          danger
          icon={<DeleteOutlined />}
          onClick={(e) => {
            if (stopPropagation) e.stopPropagation();
          }}
          style={{ padding: '4px' }}
        >
          {showText && buttonText}
        </Button>
      </Tooltip>
    </Popconfirm>
  );
};

export default DeleteButton;
