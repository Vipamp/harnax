import { TrophyOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { Card, Empty, Skeleton, Tooltip, Typography } from 'antd';
import React from 'react';
import { formatTokens, rankLabels } from './metrics';
import { glassCardStyle } from './sections';

const { Text } = Typography;

export interface RankListProps {
  title: string;
  /** 服务端已按消耗降序并截到 5 条，前端不再排序（I6）。 */
  items: API.DashboardRankItem[];
  loading: boolean;
}

/**
 * Top 榜：行宽按榜内最大值归一。
 *
 * name 为空是归属行已被删除的那笔消耗——榜里可能同时出现好几条空名，所以 React key 取数组下标，
 * 取 name 会撞车（设计 §3.2）。这类行保留数值、名字显示「（已删除）」，整条丢弃会把真实消耗抹掉。
 *
 * 标签取 `rankLabels` 而不是裸 name：榜按行 id 分组，同一个模型名挂在两个 provider 下就是两行同名，
 * 那几行会带上 provider 限定，免得页面上读出「重复渲染」。
 */
const RankList: React.FC<RankListProps> = ({ title, items, loading }) => {
  const intl = useIntl();
  const rows = items ?? [];
  const labels = rankLabels(rows);
  const max = rows.reduce((acc, item) => (item.tokens > acc ? item.tokens : acc), 0);

  return (
    <Card
      title={
        <span style={{ fontSize: 15, fontWeight: 600 }}>
          <TrophyOutlined style={{ marginRight: 8, color: '#f59e0b' }} />
          {title}
        </span>
      }
      style={glassCardStyle}
      styles={{ body: { padding: '12px 20px 16px' } }}
    >
      {loading ? (
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : rows.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: '24px 0' }} />
      ) : (
        rows.map((item, index) => {
          const named = !!item.name;
          const label = labels[index] ?? item.name;
          const ratio = max > 0 ? Math.round((item.tokens / max) * 100) : 0;

          return (
            // 同一榜里可以有好几条空名（归属行已删除的消耗），name 不能当 key；
            // 榜在服务端已截到 5 条且顺序固定，下标就是这一行的身份（设计 §3.2）。
            // biome-ignore lint/suspicious/noArrayIndexKey: empty names repeat, index is the only stable key
            <div key={index} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '6px 0' }}>
              <Text type="secondary" style={{ width: 18, fontSize: 12 }}>
                {index + 1}
              </Text>
              <div style={{ flex: 1, minWidth: 0 }}>
                <Tooltip title={named ? label : intl.formatMessage({ id: 'pages.welcome.rank.deleted' })}>
                  <Text
                    ellipsis
                    style={{
                      display: 'block',
                      fontSize: 13,
                      color: named ? 'var(--vip-text-primary)' : 'var(--vip-text-tertiary)',
                    }}
                  >
                    {named ? label : intl.formatMessage({ id: 'pages.welcome.rank.deleted' })}
                  </Text>
                </Tooltip>
                <div
                  style={{
                    marginTop: 4,
                    height: 6,
                    borderRadius: 3,
                    background: 'rgba(148, 163, 184, 0.18)',
                    overflow: 'hidden',
                  }}
                >
                  {/* 条用十六进制字面量拼透明度后缀；CSS 变量拼上去无效 */}
                  <div
                    style={{
                      width: `${ratio}%`,
                      height: '100%',
                      borderRadius: 3,
                      background: 'linear-gradient(90deg, #4f6ef7 0%, #7a93ff 100%)',
                    }}
                  />
                </div>
              </div>
              <Text style={{ fontSize: 13, whiteSpace: 'nowrap' }}>{formatTokens(item.tokens)}</Text>
            </div>
          );
        })
      )}
    </Card>
  );
};

export default RankList;
