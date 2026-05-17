import React from 'react';
import { Button, Space, Tooltip } from 'antd';
import { EditOutlined, DeleteOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 卡片操作按钮组件属性
 */
export interface CardActionsProps {
  /** 是否显示编辑按钮 */
  showEdit?: boolean;
  /** 是否显示删除按钮 */
  showDelete?: boolean;
  /** 是否显示测试按钮 */
  showTest?: boolean;
  /** 编辑按钮点击回调 */
  onEdit?: () => void;
  /** 删除按钮点击回调 */
  onDelete?: () => void;
  /** 测试按钮点击回调 */
  onTest?: () => void;
  /** 额外的操作按钮 */
  extraActions?: React.ReactNode;
  /** 按钮间距 */
  size?: number;
  /** 编辑按钮自定义颜色 */
  editColor?: string;
  /** 测试按钮自定义颜色 */
  testColor?: string;
  /** 阻止事件冒泡 */
  stopPropagation?: boolean;
}

/**
 * 卡片操作按钮组件
 * 
 * 统一的卡片编辑、删除、测试操作按钮样式
 * 用于 Agent、MCP 等卡片布局
 * 
 * @example
 * // 基础用法
 * <CardActions
 *   onEdit={() => handleEdit(item)}
 *   onDelete={() => handleDelete(item.id)}
 * />
 * 
 * @example
 * // 带测试按钮
 * <CardActions
 *   showTest
 *   onTest={() => handleTest(item.id)}
 *   onEdit={() => handleEdit(item)}
 *   onDelete={() => handleDelete(item.id)}
 * />
 */
const CardActions: React.FC<CardActionsProps> = ({
  showEdit = true,
  showDelete = true,
  showTest = false,
  onEdit,
  onDelete,
  onTest,
  extraActions,
  size = 8,
  editColor = 'var(--vip-primary)',
  testColor = 'var(--vip-warning)',
  stopPropagation = true,
}) => {
  const intl = useIntl();

  const handleClick = (callback?: () => void) => {
    if (stopPropagation) {
      // 阻止事件冒泡的逻辑由调用方在 onClick 中处理
    }
    callback?.();
  };

  return (
    <Space size={size}>
      {/* 额外操作按钮 */}
      {extraActions}

      {/* 测试按钮 */}
      {showTest && onTest && (
        <Tooltip title={intl.formatMessage({ id: 'pages.common.test', defaultMessage: 'Test' })}>
          <Button
            type="link"
            size="small"
            icon={<ThunderboltOutlined />}
            onClick={(e) => {
              if (stopPropagation) e.stopPropagation();
              onTest();
            }}
            style={{ color: testColor, padding: '4px' }}
          />
        </Tooltip>
      )}

      {/* 编辑按钮 */}
      {showEdit && onEdit && (
        <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={(e) => {
              if (stopPropagation) e.stopPropagation();
              onEdit();
            }}
            style={{ color: editColor, padding: '4px' }}
          />
        </Tooltip>
      )}

      {/* 删除按钮 */}
      {showDelete && onDelete && (
        <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
          <Button
            type="link"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={(e) => {
              if (stopPropagation) e.stopPropagation();
              onDelete();
            }}
            style={{ padding: '4px' }}
          />
        </Tooltip>
      )}
    </Space>
  );
};

export default CardActions;
