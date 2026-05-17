import { Pagination } from 'antd';
import React, { useMemo } from 'react';
import { useIntl } from '@umijs/max';

/**
 * 卡片网格分页组件
 * 
 * 特性：
 * - 自动根据每行卡片数量计算合适的pageSize选项
 * - pageSize选项为每行卡片数 * 2 的整数倍
 * - 提供5个pageSize选项
 * - 统一的国际化支持
 * - 与ResponsiveCardGrid配合使用
 * 
 * 使用示例：
 * ```tsx
 * <CardPagination
 *   current={pageNum}
 *   pageSize={pageSize}
 *   total={total}
 *   cardsPerRow={3}
 *   onChange={(page, size) => {
 *     setPageNum(page);
 *     setPageSize(size);
 *   }}
 * />
 * ```
 */
export interface CardPaginationProps {
  /** 当前页码 */
  current: number;
  /** 每页显示数量 */
  pageSize: number;
  /** 总记录数 */
  total: number;
  /** 每行卡片数量（由ResponsiveCardGrid计算） */
  cardsPerRow: number;
  /** 页码变化回调 */
  onChange: (page: number, pageSize: number) => void;
  /** 容器样式 */
  style?: React.CSSProperties;
}

/**
 * 根据每行卡片数量计算默认的pageSize（第一个选项）
 * @param cardsPerRow 每行卡片数量
 * @returns 默认的pageSize值
 */
export const getDefaultPageSize = (cardsPerRow: number): number => {
  return cardsPerRow * 2; // 第一个选项：每行卡片数 * 2
}

const CardPagination: React.FC<CardPaginationProps> = ({
  current,
  pageSize,
  total,
  cardsPerRow,
  onChange,
  style,
}) => {
  const intl = useIntl();

  // 根据每行卡片数量计算pageSize选项
  // 规则：每行卡片数 * 2 的整数倍，提供5个选项
  const pageSizeOptions = useMemo(() => {
    const base = cardsPerRow * 2; // 基础单位：每行卡片数的2倍
    const options = [
      base * 1,  // 1倍
      base * 2,  // 2倍
      base * 3,  // 3倍
      base * 5,  // 5倍
      base * 10, // 10倍
    ];
    return options.map(opt => opt.toString());
  }, [cardsPerRow]);

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        padding: '24px 0',
        background: 'var(--vip-bg-layout)',
        ...style,
      }}
    >
      <Pagination
        current={current}
        pageSize={pageSize}
        total={total}
        showSizeChanger
        showQuickJumper
        showTotal={(total) =>
          intl.formatMessage(
            { id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' },
            { total }
          )
        }
        pageSizeOptions={pageSizeOptions}
        onChange={(page, size) => onChange(page, size)}
        onShowSizeChange={(page, size) => onChange(page, size)}
        style={{
          '--ant-pagination-item-active-border-color': 'var(--vip-primary)',
          '--ant-pagination-item-active-color': 'var(--vip-primary)',
        } as React.CSSProperties}
      />
    </div>
  );
};

export default CardPagination;
