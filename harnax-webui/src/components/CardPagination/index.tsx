import { Pagination } from 'antd';
import React, { useMemo } from 'react';
import { useIntl } from '@umijs/max';

/**
 * Card grid pagination component
 *
 * Features:
 * - Auto-calculates pageSize options based on cards per row
 * - Refined floating pill-style design
 * - Smooth transitions and hover effects
 * - Unified i18n support
 * - Works with ResponsiveCardGrid
 */
export interface CardPaginationProps {
  /** Current page number */
  current: number;
  /** Page size */
  pageSize: number;
  /** Total record count */
  total: number;
  /** Cards per row (calculated by ResponsiveCardGrid) */
  cardsPerRow: number;
  /** Page change callback */
  onChange: (page: number, pageSize: number) => void;
  /** Container style override */
  style?: React.CSSProperties;
}

/**
 * Calculate default pageSize based on cards per row
 * @param cardsPerRow cards per row
 * @returns default pageSize
 */
export const getDefaultPageSize = (cardsPerRow: number): number => {
  return cardsPerRow * 2;
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

  const pageSizeOptions = useMemo(() => {
    const base = cardsPerRow * 2;
    const options = [
      base * 1,
      base * 2,
      base * 3,
      base * 5,
      base * 10,
    ];
    return options.map(opt => opt.toString());
  }, [cardsPerRow]);

  // Only show pagination when there are items
  if (total === 0) return null;

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        padding: '20px 0 8px',
        ...style,
      }}
    >
      <div
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: '16px',
          padding: '10px 24px',
          background: 'var(--vip-bg-container, #fff)',
          borderRadius: '40px',
          border: '1px solid var(--vip-border, #f0f0f0)',
          boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
          transition: 'box-shadow 0.3s ease',
        }}
        className="card-pagination-wrapper"
      >
        {/* Total count badge */}
        <span
          style={{
            fontSize: '13px',
            color: 'var(--vip-text-secondary, #8c8c8c)',
            fontWeight: 500,
            letterSpacing: '0.2px',
            whiteSpace: 'nowrap',
            flexShrink: 0,
          }}
        >
          {intl.formatMessage(
            { id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' },
            { total }
          )}
        </span>

        {/* Divider */}
        <span
          style={{
            width: '1px',
            height: '16px',
            background: 'var(--vip-border, #e8e8e8)',
            flexShrink: 0,
          }}
        />

        {/* Pagination */}
        <Pagination
          current={current}
          pageSize={pageSize}
          total={total}
          showSizeChanger
          showQuickJumper
          size="small"
          pageSizeOptions={pageSizeOptions}
          onChange={(page, size) => onChange(page, size)}
          onShowSizeChange={(page, size) => onChange(page, size)}
          style={{
            '--ant-pagination-item-active-border-color': 'var(--vip-primary)',
            '--ant-pagination-item-active-color': 'var(--vip-primary)',
          } as React.CSSProperties}
        />
      </div>

      {/* Scoped styles for refined pagination appearance */}
      <style>{`
        .card-pagination-wrapper:hover {
          box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08) !important;
        }
        .card-pagination-wrapper .ant-pagination-item {
          border-radius: 8px;
          border-color: transparent;
          background: transparent;
          font-weight: 500;
          transition: all 0.2s ease;
        }
        .card-pagination-wrapper .ant-pagination-item:hover {
          background: var(--vip-primary-light, #f0f5ff);
          border-color: transparent;
        }
        .card-pagination-wrapper .ant-pagination-item-active {
          background: var(--vip-primary);
          border-color: var(--vip-primary);
          box-shadow: 0 2px 6px rgba(79, 110, 247, 0.3);
        }
        .card-pagination-wrapper .ant-pagination-item-active a {
          color: #fff !important;
        }
        .card-pagination-wrapper .ant-pagination-item-active:hover {
          background: var(--vip-primary);
          border-color: var(--vip-primary);
        }
        .card-pagination-wrapper .ant-pagination-prev,
        .card-pagination-wrapper .ant-pagination-next {
          border-radius: 8px;
        }
        .card-pagination-wrapper .ant-pagination-prev .ant-pagination-item-link,
        .card-pagination-wrapper .ant-pagination-next .ant-pagination-item-link {
          border-radius: 8px;
          border-color: var(--vip-border, #e8e8e8);
          transition: all 0.2s ease;
        }
        .card-pagination-wrapper .ant-pagination-prev:hover .ant-pagination-item-link,
        .card-pagination-wrapper .ant-pagination-next:hover .ant-pagination-item-link {
          border-color: var(--vip-primary);
          color: var(--vip-primary);
          background: var(--vip-primary-light, #f0f5ff);
        }
        .card-pagination-wrapper .ant-pagination-disabled .ant-pagination-item-link {
          opacity: 0.35;
        }
        .card-pagination-wrapper .ant-select-selector {
          border-radius: 8px !important;
          height: 28px !important;
          font-size: 13px !important;
        }
        .card-pagination-wrapper .ant-pagination-options-quick-jumper {
          font-size: 13px;
          color: var(--vip-text-secondary, #8c8c8c);
        }
        .card-pagination-wrapper .ant-pagination-options-quick-jumper input {
          border-radius: 8px;
          height: 28px;
          font-size: 13px;
        }
      `}</style>
    </div>
  );
};

export default CardPagination;
