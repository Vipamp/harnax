import { ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useIntl, useModel } from '@umijs/max';
import { Button, Card, Col, Row, Typography } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import { getDashboardOverview } from '@/services/ant-design-pro/dashboard';
import RankList from './welcome/RankList';
import TrendCard from './welcome/TrendCard';
import {
  AssetStrip,
  GreetingBar,
  TodayStatsRow,
  TodoStrip,
  glassCardStyle,
} from './welcome/sections';

const { Text } = Typography;

/**
 * initialState.currentUser 由登录页写入，运行时带 tenants 与 currentTenantId；
 * `API.CurrentUser` 只声明了账号本身那几项，所以这里就地补上这两项，不再去读第三份 localStorage。
 */
type CurrentUserWithTenant = API.CurrentUser & {
  currentTenantId?: number | null;
  tenants?: { id?: number; name?: string }[];
};

/**
 * 首页运营总览：这一屏只回答「我这个工作区现在怎么样」，不做产品介绍（D1）。
 *
 * 页面只发一个请求（D2：`GET /api/admin/dashboard/overview`），租户由后端从请求态解析，
 * 时间窗在后端的一次时钟读取上推好（今日、昨日同时段、近 14 天）。页面因此没有窗口选择器、
 * 没有轮询：刷新靠问候条那个文字链（D5、§4.4）。
 */
const Welcome: React.FC = () => {
  const intl = useIntl();
  const { initialState } = useModel('@@initialState');
  const user = initialState?.currentUser as CurrentUserWithTenant | undefined;
  const nickname = user?.nickname || user?.username || '';
  const tenantName = user?.tenants?.find((item) => item.id === user?.currentTenantId)?.name;

  const [data, setData] = useState<API.DashboardOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // skipErrorHandler：这一屏只有一处错误态（D12），全局那条红 toast 会把它变成两处
      const response = await getDashboardOverview({ skipErrorHandler: true });
      // code !== 200 也算失败：后端把异常统一吞成 HTTP 200 + code 500，与 token-monitor 同一判定
      if (response.code === 200 && response.data) {
        setData(response.data);
      } else {
        setError(intl.formatMessage({ id: 'pages.welcome.loadFailed' }));
      }
    } catch (requestError: any) {
      if (requestError?.response?.status === 401) {
        // 关掉全局处理也关掉了它的 401 跳转，所以这条链路自己接上（设计 §5 第一行）
        localStorage.removeItem('currentUser');
        localStorage.removeItem('tokenInfo');
        history.push({ pathname: '/login', search: `?redirect=${encodeURIComponent('/welcome')}` });
        return;
      }
      // 服务端那句是写死的英文常量，原因在 admin 日志里；页面只说自己那句
      setError(intl.formatMessage({ id: 'pages.welcome.loadFailed' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => {
    load();
    // 只在挂载时发这一次请求（§4.4）：不轮询、不 setInterval，重新取数靠问候条的刷新文字链
  }, []);

  // 分区只在「一个读数都还没有」时给骨架：手里已经有一屏数还退回骨架，刷新一次就闪一次。
  // 刷新中的反馈由问候条那个转动图标承担。
  const firstLoad = loading && data === null;

  const rankTitles = {
    agents: intl.formatMessage({ id: 'pages.welcome.rank.agents' }),
    models: intl.formatMessage({ id: 'pages.welcome.rank.models' }),
  };

  return (
    <PageContainer
      header={{
        title: '',
        breadcrumb: {},
      }}
    >
      <GreetingBar
        nickname={nickname}
        tenantName={tenantName}
        serverTime={data?.serverTime}
        onRefresh={load}
        refreshing={loading}
      />

      {error ? (
        /* 整页一个错误态：分区降级要六份独立状态与六份重试，收益远低于成本（D12） */
        <Card style={glassCardStyle} styles={{ body: { padding: '32px 24px', textAlign: 'center' } }}>
          <div style={{ marginBottom: 12 }}>
            <Text type="danger">{error}</Text>
          </div>
          <Button type="primary" icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {intl.formatMessage({ id: 'pages.common.retry' })}
          </Button>
        </Card>
      ) : (
        <>
          <TodayStatsRow
            today={data?.today ?? null}
            yesterday={data?.yesterdaySameSpan ?? null}
            loading={firstLoad}
          />

          <TrendCard
            trend={data?.trend ?? []}
            recent14dFee={data?.recent14dFee ?? null}
            loading={firstLoad}
          />

          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} lg={12}>
              <RankList title={rankTitles.agents} items={data?.topAgents ?? []} loading={firstLoad} />
            </Col>
            <Col xs={24} lg={12}>
              <RankList title={rankTitles.models} items={data?.topModels ?? []} loading={firstLoad} />
            </Col>
          </Row>

          <AssetStrip
            assets={data?.assets ?? null}
            platform={data?.platform ?? null}
            loading={firstLoad}
          />

          {/* 待办与入口只在拿到数之后出现：拿 0 当「没有待审草稿」是一个会说谎的读数 */}
          {data ? (
            <TodoStrip
              pendingSkillDrafts={data.pendingSkillDrafts}
              activeUsersLast7Days={data.activeUsersLast7Days}
              totalUsers={data.totalUsers}
            />
          ) : null}
        </>
      )}
    </PageContainer>
  );
};

export default Welcome;
