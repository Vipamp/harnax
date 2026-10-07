import { Line } from '@ant-design/plots';
import { LineChartOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { Card, Empty, Spin, Typography } from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useRef, useState } from 'react';
import { useIsMobile } from '@/utils/responsive';
import { formatCount, formatTokens } from './metrics';
import { glassCardStyle } from './sections';

const { Text } = Typography;

/** 两条序列的取色：调用次数与 Token，与今日四卡同一族色系。 */
const CALLS_COLOR = '#4f6ef7';
const TOKENS_COLOR = '#0ea5e9';

export interface TrendCardProps {
  /** 后端已经补齐 14 个日点，缺数据的天为 0，前端不再插值。 */
  trend: API.DashboardTrendPoint[];
  /** 取数未完成时为 null：合计那一行整段不出现，而不是先显示 ¥0 再跳成真值。 */
  recent14dFee: number | null;
  loading: boolean;
}

/**
 * 近 14 天调用次数与 Token 两张折线 + 右下角的费用合计。
 *
 * 两张而不是两条同轴序列：两者差着四个数量级，共用一根线性轴会把调用次数压成贴底的直线。
 *
 * 费用只在合计里出现，今日四卡里没有费用卡：`token_stats.fee` 是 decimal(10,0)，只有整数元，
 * 「今日费用 ¥0.00」会假装一个并不存在的精度。窗口也不给选择器——首页是免配置的一屏，
 * 带筛选器的深度分析在「监控与治理 → Token 监控」。
 */
const TrendCard: React.FC<TrendCardProps> = ({ trend, recent14dFee, loading }) => {
  const intl = useIntl();
  const isMobile = useIsMobile();

  const callsLabel = intl.formatMessage({ id: 'pages.welcome.trend.calls' });
  const tokensLabel = intl.formatMessage({ id: 'pages.welcome.trend.tokens' });

  const data = trend ?? [];

  const seriesHeight = isMobile ? 108 : 128;

  /**
   * 侧栏在首屏带一段宽度过渡，而图表只在挂载那一刻量一次容器：量到过渡中途的值，画布就会一直
   * 比卡片宽出一截，把整页顶出横向滚动条。所以自己盯着容器宽度，把宽度显式交给图表。
   */
  const hostRef = useRef<HTMLDivElement>(null);
  const [hostWidth, setHostWidth] = useState(0);
  useEffect(() => {
    const el = hostRef.current;
    if (!el) return undefined;
    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width ?? 0;
      setHostWidth((prev) => (Math.abs(prev - width) > 0.5 ? width : prev));
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const seriesConfig = (yField: string, color: string, formatter: (value: number) => string) => ({
    data,
    xField: 'date',
    yField,
    autoFit: false,
    width: hostWidth,
    height: seriesHeight,
    shapeField: 'smooth',
    style: { lineWidth: 2, stroke: color },
    point: { shapeField: 'circle', sizeField: 3 },
    axis: {
      x: {
        labelFormatter: (date: string) => (date ? dayjs(date).format('MM-DD') : date),
        labelAutoRotate: false,
        // 窄屏上调用次数这张的 y 轴标签很短，G2 会排下 7 个日期、彼此贴成一串，所以手动隔三取一。
        tickFilter: isMobile ? (_: unknown, i: number) => i % 3 === 0 : undefined,
      },
      y: { labelFormatter: formatter },
    },
    legend: false,
    tooltip: {
      title: 'date',
      items: [{ channel: 'y', valueFormatter: formatter }],
    },
    scale: { y: { nice: true } },
    interactions: [{ type: 'tooltip' }],
  });

  const series = (
    label: string,
    color: string,
    yField: string,
    formatter: (value: number) => string,
  ) => (
    <div style={{ marginTop: 8 }}>
      <Text type="secondary" style={{ fontSize: 12 }}>
        <span style={{ color, marginRight: 6 }}>●</span>
        {label}
      </Text>
      <div style={{ height: seriesHeight }}>
        {hostWidth > 0 ? <Line {...seriesConfig(yField, color, formatter)} /> : null}
      </div>
    </div>
  );

  return (
    <Card
      title={
        <span style={{ fontSize: 15, fontWeight: 600 }}>
          <LineChartOutlined style={{ marginRight: 8, color: CALLS_COLOR }} />
          {intl.formatMessage({ id: 'pages.welcome.trend.title' })}
        </span>
      }
      style={{ ...glassCardStyle, marginBottom: 16 }}
      styles={{ body: { padding: '12px 20px 16px' } }}
    >
      <Spin spinning={loading}>
        <div ref={hostRef}>
          {data.length > 0 ? (
            <>
              {series(callsLabel, CALLS_COLOR, 'calls', formatCount)}
              {series(tokensLabel, TOKENS_COLOR, 'tokens', formatTokens)}
            </>
          ) : loading ? (
            <div style={{ height: (seriesHeight + 26) * 2 }} />
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: '48px 0' }} />
          )}
        </div>
      </Spin>
      {/* 合计只在拿到数之后出现：先画一个 ¥0 再跳成真值是噪声（D9） */}
      {recent14dFee !== null ? (
        <div style={{ textAlign: 'right', marginTop: 8 }}>
          <Text type="secondary" style={{ fontSize: 13 }}>
            {intl.formatMessage(
              { id: 'pages.welcome.trend.fee14d' },
              { fee: formatCount(recent14dFee) },
            )}
          </Text>
        </div>
      ) : null}
    </Card>
  );
};

export default TrendCard;
