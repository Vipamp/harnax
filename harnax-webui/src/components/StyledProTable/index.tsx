import React from 'react';
import { ProTable } from '@ant-design/pro-components';
import type { ProTableProps } from '@ant-design/pro-components';

/**
 * 统一表格组件
 * 
 * 封装了统一的表格样式规范,所有管理页面应使用此组件以保持样式一致
 * 
 * 样式特性:
 * - 紧凑尺寸 (size="small")
 * - 统一字体大小 (12px)
 * - 文字居中对齐 (操作栏除外)
 * - 操作栏左对齐
 * - 优化行高和内边距
 * - 悬停效果
 * 
 * @example
 * // 基础用法
 * <StyledProTable
 *   columns={columns}
 *   dataSource={data}
 *   loading={loading}
 * />
 * 
 * @example
 * // 带分页
 * <StyledProTable
 *   columns={columns}
 *   dataSource={data}
 *   pagination={{
 *     current: pageNum,
 *     pageSize,
 *     total,
 *     onChange: handlePageChange,
 *   }}
 * />
 */
const StyledProTable: <T extends Record<string, any>>(
  props: ProTableProps<T, Record<string, any>>
) => React.ReactNode = (props) => {
  const { className, style, scroll, ...restProps } = props;

  return (
    <ProTable
      size="small"
      className={`styled-pro-table ${className || ''}`}
      style={{
        fontSize: '12px',
        ...style,
      }}
      scroll={scroll || { x: 'max-content' }}
      tableAlertRender={false}
      options={{
        density: false,
        fullScreen: false,
        setting: true,
        reload: true,
      }}
      {...restProps}
    />
  );
};

export default StyledProTable;
