import React, { useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Card, Col, DatePicker, Descriptions, Drawer, Empty, Row, Select, Spin, Statistic, Table, Tabs, Tag, Tooltip, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Line } from '@ant-design/plots';
import { DashboardOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { getToolInvocations, getToolMetricsSummary, getToolMetricsTimeSeries } from '@/services/ant-design-pro/toolMetrics';
import SubjectDrawer from './SubjectDrawer';
import { formatLastSeen } from './lastSeen';
import { TAB_DIMENSIONS, readsAggregate } from './dimensions';

const CALLS_COLOR = '#4f6ef7';
const SUCCESS_COLOR = '#10b981';
const FAILURE_COLOR = '#ef4444';
const { RangePicker } = DatePicker;

/** The window the page opens on and the presets offer; the server defaults to the same span. */
const RANGE_PRESETS = [7, 30, 90, 365];
const DEFAULT_RANGE_DAYS = 30;

/**
 * Tab to origin bucket. `shell` and `framework` stay reachable inside the tool tab because the endpoint takes
 * exactly one `kind` and has no way to say "everything except mcp and cli" — a fourth tab would have to be
 * built out of exclusions the server does not support.
 */
const TAB_KINDS: Record<string, string> = { tool: 'builtin', mcp: 'mcp', cli: 'cli' };
const TOOL_TAB_KINDS: string[] = ['builtin', 'shell', 'framework'];

const OUTCOME_COLORS: Record<string, string> = {
  SUCCESS: 'green',
  ERROR: 'red',
  DENIED: 'orange',
  INTERRUPTED: 'default',
};

const formatRate = (rate: number) => `${(rate * 100).toFixed(1)}%`;

/**
 * Call metrics over `tool_invocation_log` / `tool_invocation_stats` (design section 8).
 *
 * Three tabs share one shape on purpose: the backend returns the same row for every origin bucket, so the
 * difference between a tool, an MCP server and a CLI package is only which subject id the row carries.
 */
const CallMetrics: React.FC = () => {
  const intl = useIntl();
  const [tab, setTab] = useState<string>('tool');
  const [toolKind, setToolKind] = useState<string>('builtin');
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => [
    dayjs().subtract(DEFAULT_RANGE_DAYS - 1, 'day').startOf('hour'),
    dayjs().startOf('hour'),
  ]);
  const [groupBy, setGroupBy] = useState<string>('tool');
  const [summary, setSummary] = useState<API.CallMetricsSummary | null>(null);
  const [points, setPoints] = useState<API.CallMetricsPoint[]>([]);
  /** The bucket size the trend endpoint answered with; the server folds it from the span, the page only reads it back. */
  const [granularity, setGranularity] = useState<string>('day');
  const [loading, setLoading] = useState(false);

  const [subject, setSubject] = useState<API.CallMetricsRow | null>(null);
  const [profileRow, setProfileRow] = useState<API.CallMetricsRow | null>(null);
  const [details, setDetails] = useState<API.CallInvocationRow[]>([]);
  const [detailTotal, setDetailTotal] = useState<number>(0);
  const [detailPage, setDetailPage] = useState<number>(1);
  const [detailLoading, setDetailLoading] = useState(false);

  const kind = tab === 'tool' ? toolKind : TAB_KINDS[tab];
  // One range drives every read on the page: cards, trend, table and the drill-down drawer alike.
  const start = range[0].format('YYYY-MM-DD HH:mm');
  const end = range[1].format('YYYY-MM-DD HH:mm');

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try {
        const [summaryRes, trendRes] = await Promise.all([
          getToolMetricsSummary({ start, end, kind, groupBy }),
          getToolMetricsTimeSeries({ start, end, kind }),
        ]);
        if (summaryRes.code === 200) {
          setSummary(summaryRes.data ?? null);
        } else {
          message.error(summaryRes.message || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
        }
        if (trendRes.code === 200) {
          setPoints(trendRes.data?.points ?? []);
          setGranularity(trendRes.data?.granularity ?? 'day');
        } else {
          // The cards and the table below already moved to the new window, so leaving the old buckets up draws
          // a line that is no one's trend.
          setPoints([]);
          setGranularity('day');
        }
      } catch (error: any) {
        message.error(error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [start, end, kind, groupBy]);

  const loadDetails = async (row: API.CallMetricsRow, page: number) => {
    setSubject(row);
    setDetailPage(page);
    // Open empty: a detail request that fails would otherwise leave the previous row's calls listed under
    // this row's title, which is the one thing a drill-down must never do.
    setDetails([]);
    setDetailTotal(0);
    setDetailLoading(true);
    try {
      const res = await getToolInvocations({
        start,
        end,
        // Detail-dimension rows carry no kind, so the tab's kind is the fallback; an empty string would make
        // the backend's <if> drop the predicate and the drawer would list every origin bucket.
        kind: row.kind || kind,
        toolName: groupBy === 'tool' ? row.toolName : undefined,
        mcpId: row.kind === 'mcp' ? row.subjectId : undefined,
        cliId: row.kind === 'cli' ? row.subjectId : undefined,
        agentId: groupBy === 'agent' ? row.subjectId : undefined,
        sessionId: groupBy === 'session' ? row.subjectKey : undefined,
        pageNum: page,
        pageSize: 20,
      });
      if (res.code === 200) {
        setDetails(res.data?.records ?? []);
        setDetailTotal(res.data?.total ?? 0);
      } else {
        message.error(res.message || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
      }
    } catch (error: any) {
      message.error(error?.message || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
    } finally {
      setDetailLoading(false);
    }
  };

  const statCards: { title: string; value: string | number; color: string; hint?: string }[] = [
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.totalCalls', defaultMessage: 'Calls' }),
      value: summary?.totalCalls ?? 0,
      color: CALLS_COLOR,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.successRate', defaultMessage: 'Success rate' }),
      value: formatRate(summary?.successRate ?? 0),
      color: SUCCESS_COLOR,
      hint: intl.formatMessage({ id: 'pages.callMetrics.successRateHint', defaultMessage: 'Share of calls ending SUCCESS; denials and interruptions count as failures' }),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.p95', defaultMessage: 'P95 duration' }),
      value: `${summary?.p95Operator ?? '<='}${summary?.p95Ms ?? 0} ms`,
      color: '#0ea5e9',
      hint: intl.formatMessage({ id: 'pages.callMetrics.p95Hint', defaultMessage: 'Approximated from six duration buckets, so the per-tool view gives a bucket boundary rather than an exact millisecond' }),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.failingCalls', defaultMessage: 'Failed calls' }),
      value: summary?.failingCalls ?? 0,
      color: FAILURE_COLOR,
      hint: intl.formatMessage({ id: 'pages.callMetrics.failingHint', defaultMessage: 'ERROR, DENIED and INTERRUPTED together' }),
    },
  ];

  /**
   * The endpoint answers one row per (timePoint, kind, subject, tool), so a day with 30 tools is 30 points
   * sharing one timePoint. Summing them here is the only way the three series mean what their labels say.
   */
  const trendData = useMemo(() => {
    const buckets = new Map<string, { calls: number; successes: number; failures: number }>();
    for (const point of points) {
      const bucket = buckets.get(point.timePoint) ?? { calls: 0, successes: 0, failures: 0 };
      bucket.calls += point.calls;
      bucket.successes += point.successes;
      bucket.failures += point.errors + point.denials + point.interruptions;
      buckets.set(point.timePoint, bucket);
    }
    return [...buckets.entries()].flatMap(([time, bucket]) => [
      { time, type: intl.formatMessage({ id: 'pages.callMetrics.series.calls', defaultMessage: 'Calls' }), value: bucket.calls },
      { time, type: intl.formatMessage({ id: 'pages.callMetrics.series.successes', defaultMessage: 'Successes' }), value: bucket.successes },
      { time, type: intl.formatMessage({ id: 'pages.callMetrics.series.failures', defaultMessage: 'Failures' }), value: bucket.failures },
    ]);
  }, [points, intl]);

  const lineConfig = {
    data: trendData,
    xField: 'time',
    yField: 'value',
    colorField: 'type',
    shapeField: 'smooth',
    height: 260,
    axis: {
      // Hour buckets all share a date, so a date-only label would print the same day across a two-day window.
      x: {
        labelFormatter: (time: string) => (time ? dayjs(time).format(granularity === 'hour' ? 'MM-DD HH:00' : 'MM-DD') : time),
        labelAutoRotate: false,
      },
      y: { labelFormatter: (value: number) => `${value}` },
    },
    legend: { color: { position: 'top' as const, layout: { justifyContent: 'center' } } },
    tooltip: { title: 'time' },
    style: { lineWidth: 2 },
    scale: { color: { range: [CALLS_COLOR, SUCCESS_COLOR, FAILURE_COLOR] } },
    point: { shapeField: 'circle', sizeField: 3 },
  };

  const subjectColumns: ColumnsType<API.CallMetricsRow> = [
    {
      title: intl.formatMessage({ id: `pages.callMetrics.dim.${groupBy}`, defaultMessage: groupBy }),
      key: 'subject',
      width: 240,
      ellipsis: true,
      render: (_: unknown, row) => (
        <a
          onClick={() => {
            // Two views of the same row: opening the profile closes the records drawer rather than stacking on it.
            setSubject(null);
            setProfileRow(row);
          }}
          style={{ fontWeight: 500, cursor: 'pointer' }}
        >
          {row.subjectName || row.subjectKey}
          {/* The aggregate groups by (kind, subject_id, tool_name): one tool served by two MCP servers is two
              rows with the same name, and the server named beside it is what tells them apart — and scopes the
              drawer to a different server. */}
          {groupBy === 'tool' && row.parentName ? (
            <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
              {row.parentName}
            </Typography.Text>
          ) : null}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.calls', defaultMessage: 'Calls' }),
      dataIndex: 'calls',
      key: 'calls',
      width: 100,
      // The number and the drawer are the same count read at two resolutions, so the drill-down hangs off this
      // column rather than off the subject, which now opens the subject's own profile.
      render: (calls: number, row) => (
        <a
          onClick={() => {
            setProfileRow(null);
            loadDetails(row, 1);
          }}
          style={{ cursor: 'pointer' }}
        >
          {calls}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.successRate', defaultMessage: 'Success rate' }),
      dataIndex: 'successRate',
      key: 'successRate',
      width: 110,
      render: (rate: number) => formatRate(rate),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.avgDuration', defaultMessage: 'Avg duration' }),
      dataIndex: 'avgDurationMs',
      key: 'avgDurationMs',
      width: 110,
      render: (ms: number) => `${ms} ms`,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.p95', defaultMessage: 'P95' }),
      key: 'p95',
      width: 110,
      render: (_: unknown, row) => `${row.p95Operator}${row.p95Ms} ms`,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.lastSeen', defaultMessage: 'Last call' }),
      dataIndex: 'lastSeenAt',
      key: 'lastSeenAt',
      width: 170,
      render: (time?: string) => formatLastSeen(time, groupBy),
    },
  ];

  const detailColumns: ColumnsType<API.CallInvocationRow> = [
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.time', defaultMessage: 'Time' }),
      dataIndex: 'ts',
      key: 'ts',
      width: 170,
      // The service formats every instant column to `yyyy-MM-dd HH:mm:ss` before serialising
      // (ToolMetricsServiceImpl.TIMESTAMP_FORMATTER), so the datetime(3) milliseconds never reach the browser;
      // printing `.SSS` would render a constant `.000` on every row.
      render: (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.outcome', defaultMessage: 'Outcome' }),
      dataIndex: 'outcome',
      key: 'outcome',
      width: 100,
      render: (outcome: string) => (
        <Tag color={OUTCOME_COLORS[outcome] || 'default'}>
          {intl.formatMessage({ id: `pages.callMetrics.outcome.${outcome}`, defaultMessage: outcome })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.duration', defaultMessage: 'Duration' }),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => `${ms} ms`,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.session', defaultMessage: 'Session' }),
      dataIndex: 'sessionId',
      key: 'sessionId',
      ellipsis: true,
      render: (sessionId: string) => sessionId || '-',
    },
  ];

  // The four spans the page offered as a day count are the picker's presets now: each one ends at the current
  // hour and starts the same number of hours back, so a preset answers the numbers the old Select did.
  const rangePresets = RANGE_PRESETS.map((value) => ({
    label: intl.formatMessage({ id: `pages.callMetrics.window.${value}`, defaultMessage: `Last ${value} days` }),
    value: [dayjs().subtract(value - 1, 'day').startOf('hour'), dayjs().startOf('hour')] as [Dayjs, Dayjs],
  }));

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <DashboardOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.callMetrics.title', defaultMessage: 'Call Metrics' })}
          </span>
        ),
      }}
    >
      <Spin spinning={loading}>
        <Tabs
          activeKey={tab}
          onChange={(key) => {
            setTab(key);
            if (key !== 'tool') setToolKind('builtin');
            // Each tab groups by its own subject first, so a dimension that belongs to another tab is dropped
            // rather than left on show asking the new table a question it cannot answer.
            if (!TAB_DIMENSIONS[key].includes(groupBy)) setGroupBy(TAB_DIMENSIONS[key][0]);
          }}
          items={[
            { key: 'tool', label: intl.formatMessage({ id: 'pages.callMetrics.tab.tool', defaultMessage: 'Tools' }) },
            { key: 'mcp', label: intl.formatMessage({ id: 'pages.callMetrics.tab.mcp', defaultMessage: 'MCP' }) },
            { key: 'cli', label: intl.formatMessage({ id: 'pages.callMetrics.tab.cli', defaultMessage: 'CLI' }) },
          ]}
          tabBarExtraContent={{
            right: (
              <RangePicker
                value={range}
                showTime={{ format: 'HH', showMinute: false, showSecond: false }}
                format="YYYY-MM-DD HH:00"
                presets={rangePresets}
                allowClear={false}
                style={{ width: 330 }}
                disabledDate={(current) => current.isAfter(dayjs().endOf('day'))}
                onChange={(dates) => {
                  if (!dates?.[0] || !dates?.[1]) return;
                  // The window is hour grain at both ends, so a typed minute folds back into the hour it
                  // belongs to here rather than reaching the server as a boundary it cannot answer.
                  setRange([dates[0].startOf('hour'), dates[1].startOf('hour')]);
                  // The records an open drawer lists belong to the range they were counted over; paging them
                  // under a new range would silently mix the two.
                  setSubject(null);
                }}
              />
            ),
          }}
        />

        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
          {statCards.map((card) => (
            <Col xs={12} sm={12} md={6} key={card.title}>
              <Card
                style={{
                  borderRadius: 12,
                  border: '1px solid var(--vip-border)',
                  background: 'var(--vip-bg-container)',
                }}
              >
                <Statistic
                  title={
                    card.hint ? (
                      <Tooltip title={card.hint}>
                        <span>
                          {card.title} <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
                        </span>
                      </Tooltip>
                    ) : (
                      card.title
                    )
                  }
                  value={card.value}
                  valueStyle={{ color: card.color, fontWeight: 600 }}
                />
              </Card>
            </Col>
          ))}
        </Row>

        <Card
          title={
            <span style={{ fontSize: '14px', fontWeight: 600 }}>
              {intl.formatMessage({ id: 'pages.callMetrics.trendTitle', defaultMessage: 'Call trend' })}
            </span>
          }
          styles={{ body: { padding: '12px' } }}
          style={{ marginBottom: 16 }}
        >
          {trendData.length > 0 ? (
            <Line {...lineConfig} />
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.callMetrics.empty', defaultMessage: 'No call inside this window' })} style={{ padding: '48px 0' }} />
          )}
        </Card>

        <Card
          title={
            <span style={{ fontSize: '14px', fontWeight: 600 }}>
              {intl.formatMessage({ id: 'pages.callMetrics.detailTitle', defaultMessage: 'Detail' })}
            </span>
          }
          extra={
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              {tab === 'tool' && (
                <Select
                  value={toolKind}
                  onChange={setToolKind}
                  style={{ width: 150 }}
                  options={TOOL_TAB_KINDS.map((value) => ({
                    value,
                    label: intl.formatMessage({ id: `pages.callMetrics.kind.${value}`, defaultMessage: value }),
                  }))}
                />
              )}
              <Select
                value={groupBy}
                onChange={setGroupBy}
                style={{ width: 130 }}
                options={TAB_DIMENSIONS[tab].map((value) => ({
                  value,
                  label: intl.formatMessage(
                    { id: 'pages.callMetrics.groupByPrefix', defaultMessage: 'By {dimension}' },
                    { dimension: intl.formatMessage({ id: `pages.callMetrics.dim.${value}`, defaultMessage: value }) },
                  ),
                }))}
              />
            </div>
          }
          styles={{ body: { padding: '12px' } }}
        >
          {!readsAggregate(groupBy) && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message={intl.formatMessage({ id: 'pages.callMetrics.detailHint', defaultMessage: 'The agent and session views read the detail table, so they only cover calls inside the retention window.' })}
            />
          )}
          <Table<API.CallMetricsRow>
            className="styled-pro-table"
            rowKey={(row) => `${row.kind}-${row.subjectKey}-${row.toolName}-${row.subjectId ?? ''}`}
            columns={subjectColumns}
            dataSource={summary?.rows ?? []}
            loading={loading}
            size="small"
            scroll={{ x: 'max-content' }}
            locale={{
              emptyText: <Empty description={intl.formatMessage({ id: 'pages.callMetrics.empty', defaultMessage: 'No call inside this window' })} />,
            }}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (t) => `${intl.formatMessage({ id: 'pages.common.total', defaultMessage: 'Total' })} ${t} ${intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}`,
            }}
          />
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
            {intl.formatMessage(
              { id: 'pages.callMetrics.windowHint', defaultMessage: 'Counts cover {from} to {to}, both hours inclusive - the cards, the trend line and the table below all read this range. Success rate and P95 are range-wide figures, not per-bucket ones.' },
              // The server answers the range it actually counted, clamps included; the picked range only covers
              // the moment before the first read comes back.
              { from: summary?.from || start, to: summary?.to || end },
            )}
          </Typography.Paragraph>
        </Card>
      </Spin>

      <Drawer
        width={720}
        open={!!subject}
        onClose={() => setSubject(null)}
        title={`${intl.formatMessage({ id: 'pages.callMetrics.detail.title', defaultMessage: 'Call records' })} — ${subject ? subject.subjectName || subject.subjectKey : ''}`}
      >
        <Table<API.CallInvocationRow>
          className="styled-pro-table"
          rowKey="id"
          columns={detailColumns}
          dataSource={details}
          loading={detailLoading}
          size="small"
          pagination={{
            current: detailPage,
            pageSize: 20,
            total: detailTotal,
            showSizeChanger: false,
            onChange: (page) => subject && loadDetails(subject, page),
          }}
          expandable={{
            rowExpandable: (row) => !!(row.errorMessage || row.argsJson || row.resultExcerpt),
            expandedRowRender: (row) => (
              <Descriptions size="small" column={1} bordered>
                {row.errorMessage ? (
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.callMetrics.detail.errorMessage', defaultMessage: 'Failure reason' })}>
                    {row.errorMessage}
                  </Descriptions.Item>
                ) : null}
                {row.argsJson ? (
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.callMetrics.detail.args', defaultMessage: 'Input' })}>
                    <Typography.Paragraph copyable style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                      {row.argsJson}
                    </Typography.Paragraph>
                  </Descriptions.Item>
                ) : null}
                {row.resultExcerpt ? (
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.callMetrics.detail.result', defaultMessage: 'Result excerpt' })}>
                    <Typography.Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                      {row.resultExcerpt}
                    </Typography.Paragraph>
                  </Descriptions.Item>
                ) : null}
              </Descriptions>
            ),
          }}
        />
      </Drawer>

      <SubjectDrawer row={profileRow} groupBy={groupBy} onClose={() => setProfileRow(null)} />
    </PageContainer>
  );
};

export default CallMetrics;
