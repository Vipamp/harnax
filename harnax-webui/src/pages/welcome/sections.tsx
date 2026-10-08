import { RedoOutlined } from '@ant-design/icons';
import { history, useIntl } from '@umijs/max';
import { Card, Col, Divider, Row, Skeleton, Space, Typography } from 'antd';
import React from 'react';
import { deltaOf, deltaTone, formatCount, formatTokens, type DeltaTone } from './metrics';

/**
 * 总览页的四个展示分区。全部 props 进、不发请求、不持状态：取数与编排只在 Welcome.tsx 里，
 * 这样失败态只需要在一处处理（设计 §4.4 与 D12：整页一个错误态，不做分区块降级）。
 */

/** 玻璃拟态卡：与 SearchFilterBar 同形状（设计 §4.6）。 */
export const glassCardStyle: React.CSSProperties = {
  borderRadius: 12,
  background: 'var(--glass-bg)',
  backdropFilter: 'blur(16px)',
  WebkitBackdropFilter: 'blur(16px)',
  border: '1px solid var(--glass-border)',
  boxShadow: 'var(--glass-shadow)',
};

const { Text, Link } = Typography;

const separator = <Text type="secondary">·</Text>;

export interface GreetingBarProps {
  nickname: string;
  /** 租户名。initialState 里没有租户信息时（例如旧登录态）这一项整段不渲染，而不是编一个名字。 */
  tenantName?: string;
  serverTime?: string;
  onRefresh: () => void;
  refreshing: boolean;
}

export const GreetingBar: React.FC<GreetingBarProps> = ({
  nickname,
  tenantName,
  serverTime,
  onRefresh,
  refreshing,
}) => {
  const intl = useIntl();

  return (
    <Card style={{ ...glassCardStyle, marginBottom: 16 }} styles={{ body: { padding: '14px 20px' } }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <Space split={separator} wrap size={8}>
          {nickname ? (
            <Text strong style={{ fontSize: 15 }}>
              {intl.formatMessage({ id: 'pages.welcome.greeting' }, { nickname })}
            </Text>
          ) : null}
          {tenantName ? (
            <Text type="secondary">
              {intl.formatMessage({ id: 'pages.welcome.tenant' }, { name: tenantName })}
            </Text>
          ) : null}
          {serverTime ? (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {intl.formatMessage({ id: 'pages.welcome.dataAsOf' }, { time: serverTime })}
            </Text>
          ) : null}
        </Space>
        {/* 刷新只在这一处：成功后不轮询、不设 setInterval（设计 §4.4） */}
        <Link onClick={onRefresh} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <RedoOutlined spin={refreshing} />
          {intl.formatMessage({ id: 'pages.welcome.refresh' })}
        </Link>
      </div>
    </Card>
  );
};

export interface TodayStatsRowProps {
  /** 取数未完成时为 null：这时整张卡给 Skeleton，不拿 0 冒充一个真实读数。 */
  today: API.DashboardWindowStats | null;
  yesterday: API.DashboardWindowStats | null;
  loading: boolean;
}

const todayDefs: {
  id: string;
  color: string;
  format: (value: number) => string;
  pick: (window: API.DashboardWindowStats) => number;
}[] = [
  { id: 'pages.welcome.today.calls', color: '#4f6ef7', format: formatCount, pick: (w) => w.calls },
  { id: 'pages.welcome.today.tokens', color: '#0ea5e9', format: formatTokens, pick: (w) => w.tokens },
  { id: 'pages.welcome.today.sessions', color: '#10b981', format: formatCount, pick: (w) => w.sessions },
  { id: 'pages.welcome.today.agents', color: '#8b5cf6', format: formatCount, pick: (w) => w.agents },
];

/** 环比四种说法到 i18n key 的映射（设计 §4.5 的 pages.welcome.delta.*）。 */
const deltaMessageKey = (tone: DeltaTone): string => {
  if (tone === 'up') return 'pages.welcome.delta.up';
  if (tone === 'down') return 'pages.welcome.delta.down';
  if (tone === 'new') return 'pages.welcome.delta.new';
  return 'pages.welcome.delta.flat';
};

const TodayStatCard: React.FC<{
  def: (typeof todayDefs)[number];
  today: API.DashboardWindowStats | null;
  yesterday: API.DashboardWindowStats | null;
  loading: boolean;
}> = ({ def, today, yesterday, loading }) => {
  const intl = useIntl();
  // 两个窗口都到位才有读数；否则整张卡是骨架，不拿 0 冒充一个数
  const current = today && yesterday && !loading ? def.pick(today) : null;
  const delta = today && yesterday && !loading ? deltaOf(def.pick(today), def.pick(yesterday)) : null;

  return (
    <Col xs={24} sm={12} lg={6}>
      <Card style={glassCardStyle} styles={{ body: { padding: '18px 20px' } }}>
        <Text type="secondary" style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>
          {intl.formatMessage({ id: def.id })}
        </Text>
        {current !== null && delta ? (
          <>
            <div style={{ fontSize: 30, fontWeight: 700, lineHeight: 1.2, color: def.color }}>
              {def.format(current)}
            </div>
            <div
              style={{
                marginTop: 8,
                fontSize: 12,
                color: deltaTone(delta.tone),
                background: `${deltaTone(delta.tone)}15`,
                borderRadius: 8,
                padding: '3px 8px',
                display: 'inline-block',
              }}
            >
              {intl.formatMessage(
                { id: deltaMessageKey(delta.tone) },
                { percent: delta.percentText ?? '' },
              )}
            </div>
          </>
        ) : (
          <Skeleton active title={false} paragraph={{ rows: 2 }} />
        )}
      </Card>
    </Col>
  );
};

/**
 * 今日四卡。环比基准是「昨日同时段」而不是昨天全天（D6）：拿今日至今对整日比必然虚假下滑。
 * 昨值为 0 的两种形状由 deltaOf 给「持平 / 新增」，这里不把缺基线画成 100%。
 */
export const TodayStatsRow: React.FC<TodayStatsRowProps> = ({ today, yesterday, loading }) => (
  <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
    {todayDefs.map((def) => (
      <TodayStatCard key={def.id} def={def} today={today} yesterday={yesterday} loading={loading} />
    ))}
  </Row>
);

export interface AssetStripProps {
  /** 取数未完成时为 null：格子给 Skeleton，不显示一个看起来像读数的 0。 */
  assets: API.DashboardAssetCounts | null;
  platform: API.DashboardPlatformCounts | null;
  loading: boolean;
}

const assetCells: { id: string; pick: (assets: API.DashboardAssetCounts) => number }[] = [
  { id: 'pages.welcome.assets.agents', pick: (a) => a.agents },
  { id: 'pages.welcome.assets.skills', pick: (a) => a.skills },
  { id: 'pages.welcome.assets.models', pick: (a) => a.models },
  { id: 'pages.welcome.assets.mcp', pick: (a) => a.mcpServers },
  { id: 'pages.welcome.assets.channels', pick: (a) => a.channels },
  { id: 'pages.welcome.assets.teams', pick: (a) => a.teams },
  { id: 'pages.welcome.assets.sessions', pick: (a) => a.sessions },
  { id: 'pages.welcome.assets.users', pick: (a) => a.users },
];

/**
 * 资产条分两排（D7）：上面 8 格是当前租户的，下面工具数与 CLI 包数来自两张没有 tenant_id 列的
 * 注册表，是平台级的数，混进同一排会让人以为本租户只有那么几个工具。
 */
export const AssetStrip: React.FC<AssetStripProps> = ({ assets, platform, loading }) => {
  const intl = useIntl();
  const ready = !loading && !!assets && !!platform;

  return (
    <Card
      title={
        <Text strong style={{ fontSize: 15 }}>
          {intl.formatMessage({ id: 'pages.welcome.assets.title' })}
        </Text>
      }
      style={{ ...glassCardStyle, marginBottom: 16 }}
      styles={{ body: { padding: '4px 20px 16px' } }}
    >
      <Row gutter={[12, 12]}>
        {assetCells.map((cell) => (
          <Col xs={12} sm={6} lg={3} key={cell.id}>
            <div style={{ padding: '8px 10px', borderRadius: 10, background: 'rgba(148, 163, 184, 0.08)' }}>
              <Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
                {intl.formatMessage({ id: cell.id })}
              </Text>
              {ready && assets ? (
                <div style={{ fontSize: 20, fontWeight: 600, lineHeight: 1.4 }}>
                  {formatCount(cell.pick(assets))}
                </div>
              ) : (
                <Skeleton active title={false} paragraph={{ rows: 1 }} />
              )}
            </div>
          </Col>
        ))}
      </Row>

      <Divider style={{ margin: '14px 0' }} />

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <Text strong style={{ fontSize: 13 }}>
          {intl.formatMessage({ id: 'pages.welcome.platform.title' })}
        </Text>
        {/* 说明文案紧跟在标题后：这两个数不属于本租户，不写清楚就是误导 */}
        <Text type="secondary" style={{ fontSize: 12 }}>
          {intl.formatMessage({ id: 'pages.welcome.platform.note' })}
        </Text>
        {ready && platform ? (
          <Space split={separator} size={8} wrap>
            <Text>
              {intl.formatMessage(
                { id: 'pages.welcome.platform.tools' },
                { count: formatCount(platform.tools) },
              )}
            </Text>
            <Text>
              {intl.formatMessage(
                { id: 'pages.welcome.platform.cli' },
                { count: formatCount(platform.cliPackages) },
              )}
            </Text>
          </Space>
        ) : (
          <Skeleton active title={false} paragraph={{ rows: 1 }} style={{ width: 160 }} />
        )}
      </div>
    </Card>
  );
};

export interface TodoStripProps {
  pendingSkillDrafts: number;
  activeUsersLast7Days: number;
  totalUsers: number;
}

/**
 * 待办与入口。三个快速入口是文字链而不是一排新按钮：这一屏已经够多动作了，
 * 首页要回答的是「现在怎么样」，不是「这里有多少个按钮」。
 */
export const TodoStrip: React.FC<TodoStripProps> = ({
  pendingSkillDrafts,
  activeUsersLast7Days,
  totalUsers,
}) => {
  const intl = useIntl();

  // 菜单重排已合入，/monitor/* 是正地址；旧的两条只剩 redirect 保活，页面不再往 redirect 上挂
  const quickLinks = [
    { id: 'pages.welcome.quick.agent', path: '/agent/manager' },
    { id: 'pages.welcome.quick.skill', path: '/context/skill' },
    { id: 'pages.welcome.quick.tokenMonitor', path: '/monitor/token-monitor' },
  ];

  return (
    <Card style={glassCardStyle} styles={{ body: { padding: '14px 20px' } }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
        <Space split={separator} size={8} wrap>
          <Link onClick={() => history.push('/monitor/skill-drafts')}>
            {intl.formatMessage(
              { id: 'pages.welcome.todo.drafts' },
              { count: formatCount(pendingSkillDrafts) },
            )}
          </Link>
          <Text type="secondary">
            {intl.formatMessage(
              { id: 'pages.welcome.todo.activeUsers7d' },
              { active: formatCount(activeUsersLast7Days), total: formatCount(totalUsers) },
            )}
          </Text>
        </Space>
        <span style={{ marginLeft: 'auto' }}>
          <Space split={separator} size={8} wrap>
            {quickLinks.map((link) => (
              <Link key={link.path} onClick={() => history.push(link.path)}>
                {intl.formatMessage({ id: link.id })}
              </Link>
            ))}
          </Space>
        </span>
      </div>
    </Card>
  );
};
