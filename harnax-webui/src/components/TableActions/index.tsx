import React from 'react';
import { Button, Space, Tooltip, Popconfirm } from 'antd';
import { EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import StatusSwitch from '@/components/StatusSwitch';

/**
 * 表格操作按钮组件属性
 */
export interface TableActionsProps {
  /** 是否显示编辑按钮 */
  showEdit?: boolean;
  /** 是否显示删除按钮 */
  showDelete?: boolean;
  /** 是否显示启停开关 */
  showSwitch?: boolean;
  /** 编辑按钮点击回调 */
  onEdit?: () => void;
  /** 删除按钮确认回调 */
  onDelete?: () => void;
  /** 状态切换回调 */
  onToggle?: () => void;
  /** 当前状态 (1: 启用, 0: 禁用) */
  status?: number;
  /** 删除确认标题 */
  deleteConfirmTitle?: string;
  /** 编辑按钮自定义颜色 */
  editColor?: string;
  /** 按钮间距 */
  size?: number;
  /** 是否有操作权限 */
  hasPermission?: boolean;
}

/**
 * 表格操作列通用组件
 * 
 * 统一的表格编辑、删除、启停开关样式
 * 
 * @example
 * // 基础用法
 * <TableActions
 *   onEdit={() => handleEdit(record)}
 *   onDelete={() => handleDelete(record.id)}
 *   onToggle={() => handleToggle(record.id, record.status)}
 *   status={record.status}
 * />
 * 
 * @example
 * // 带删除确认
 * <TableActions
 *   onEdit={handleEdit}
 *   onDelete={handleDelete}
 *   deleteConfirmTitle="确定要删除这个项目吗？"
 *   onToggle={handleToggle}
 *   status={status}
 * />
 * 
 * @example
 * // 无权限时只显示禁用的开关
 * <TableActions
 *   hasPermission={false}
 *   status={record.status}
 * />
 */
const TableActions: React.FC<TableActionsProps> = ({
  showEdit = true,
  showDelete = true,
  showSwitch = true,
  onEdit,
  onDelete,
  onToggle,
  status = 0,
  deleteConfirmTitle,
  editColor = '#1890ff',
  size = 8,
  hasPermission = true,
}) => {
  const intl = useIntl();

  // 默认的删除确认标题
  const confirmTitle = deleteConfirmTitle || intl.formatMessage({
    id: 'pages.message.deleteConfirm',
    defaultMessage: 'Are you sure to delete?',
  });

  // 无权限时只显示禁用的开关
  if (!hasPermission && showSwitch) {
    return (
      <StatusSwitch
        status={status}
        onChange={() => {}}
        disabled
      />
    );
  }

  return (
    <Space size={size}>
      {/* 编辑按钮 */}
      {showEdit && onEdit && (
        <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={onEdit}
            style={{ padding: '4px', color: editColor }}
          />
        </Tooltip>
      )}

      {/* 删除按钮 */}
      {showDelete && onDelete && (
        <Popconfirm
          title={confirmTitle}
          onConfirm={onDelete}
        >
          <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
              style={{ padding: '4px' }}
            />
          </Tooltip>
        </Popconfirm>
      )}

      {/* 启停开关 */}
      {showSwitch && onToggle && (
        <StatusSwitch
          status={status}
          onChange={onToggle}
        />
      )}
    </Space>
  );
};

export default TableActions;
