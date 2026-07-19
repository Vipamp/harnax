// pages/system/token-monitor/index.ts
import { getTokenAggregation, getTokenTimeSeries } from '../../../services/token-stats';

const GRANULARITY = [
  { value: 'hour', label: '小时' },
  { value: 'day', label: '天' },
  { value: 'month', label: '月' },
];

Page({
  data: {
    loading: false,
    aggregation: null as any,
    trends: [] as any[],
    granularities: GRANULARITY.map((g) => g.label),
    granularityIndex: 1, // 默认天
    // 格式化后
    totalTokensText: '-',
    promptTokensText: '-',
    completionTokensText: '-',
    totalCostText: '-',
    todayTokensText: '-',
    todayCostText: '-',
    // 图表数据
    promptPct: 0,
    completionPct: 0,
    modelBars: [] as any[],
    trendBars: [] as any[],
  },

  onShow() {
    this.loadData();
  },

  onPullDownRefresh() {
    this.loadData().finally(() => wx.stopPullDownRefresh());
  },

  onGranularityChange(e: any) {
    this.setData({ granularityIndex: Number(e.detail.value) });
    this.loadTrends();
  },

  async loadData() {
    this.setData({ loading: true });
    try {
      await Promise.all([this.loadAggregation(), this.loadTrends()]);
    } catch (e) {} finally {
      this.setData({ loading: false });
    }
  },

  async loadAggregation() {
    try {
      const r = await getTokenAggregation({});
      const o = r?.overall || {};
      this.setData({
        aggregation: r as any,
        totalTokensText: this.fmtNum(o.grandTotalToken),
        promptTokensText: this.fmtNum(o.totalInputToken),
        completionTokensText: this.fmtNum(o.totalOutputToken),
        totalCostText: this.fmtCost(o.totalFee),
        // Backend 无 todayTokens/todayCost，用 agentCount/sessionCount 替代展示
        todayTokensText: this.fmtNum(o.sessionCount),
        todayCostText: this.fmtNum(o.agentCount),
      });
      this.buildRatio(o);
      this.buildModelBars((r && (r as any).modelStats) || []);
    } catch (e) {}
  },

  /** Prompt / Completion 占比 */
  buildRatio(o: any) {
    const input = Number(o.totalInputToken || 0);
    const output = Number(o.totalOutputToken || 0);
    const sum = input + output;
    if (sum <= 0) {
      this.setData({ promptPct: 0, completionPct: 0 });
      return;
    }
    const promptPct = Math.round((input / sum) * 100);
    this.setData({ promptPct, completionPct: 100 - promptPct });
  },

  /** 模型分布条形图（Top 5） */
  buildModelBars(stats: any[]) {
    const rows = (stats || [])
      .map((s) => ({
        name: s.modelName || s.model || s.name || '未知模型',
        tokens: Number(s.grandTotalToken || s.totalToken || 0),
        fee: Number(s.totalFee || 0),
      }))
      .sort((a, b) => b.tokens - a.tokens)
      .slice(0, 5);
    const max = rows.reduce((m, r) => Math.max(m, r.tokens), 0) || 1;
    const modelBars = rows.map((r) => ({
      name: r.name,
      tokensText: this.fmtNum(r.tokens),
      widthPct: Math.max(4, Math.round((r.tokens / max) * 100)),
    }));
    this.setData({ modelBars });
  },

  async loadTrends() {
    try {
      const g = GRANULARITIES[this.data.granularityIndex].value as 'hour' | 'day' | 'month';
      const r = await getTokenTimeSeries({ granularity: g });
      const list = r?.timeSeriesData || [];
      const trends = list.map((item: any) => ({
        date: item.timePoint || '',
        tokens: item.grandTotalToken || 0,
        cost: item.totalFee || 0,
        tokensText: this.fmtNum(item.grandTotalToken),
        costText: this.fmtCost(item.totalFee),
      }));
      this.setData({ trends });
      this.buildTrendBars(trends);
    } catch (e) {}
  },

  /** 趋势柱状图（取最近 12 个时间点） */
  buildTrendBars(trends: any[]) {
    const recent = trends.slice(-12);
    const max = recent.reduce((m, t) => Math.max(m, Number(t.tokens || 0)), 0) || 1;
    const trendBars = recent.map((t) => {
      const label = String(t.date || '');
      return {
        label,
        shortLabel: label.length > 5 ? label.slice(-5) : label,
        tokensText: t.tokensText,
        heightPct: Math.max(3, Math.round((Number(t.tokens || 0) / max) * 100)),
      };
    });
    this.setData({ trendBars });
  },

  fmtNum(n?: number): string {
    if (n === undefined || n === null) return '-';
    return n.toLocaleString ? n.toLocaleString() : String(n);
  },

  fmtCost(n?: number): string {
    if (n === undefined || n === null) return '-';
    return `¥${Number(n).toFixed(4)}`;
  },
});

const GRANULARITIES = GRANULARITY;
