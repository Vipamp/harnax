// pages/index/index.ts
import { getAgentPage } from '../../services/agent';
import { getSessionPage } from '../../services/session';
import { getAgentTaskPage } from '../../services/task';
import { getModelPage } from '../../services/model';
import { getUserInfo } from '../../utils/auth';

interface StatItem {
  key: string;
  label: string;
  icon: string;
  bg: string;
  value: number | string;
  url: string;
  tab: boolean;
}

Page({
  data: {
    username: '管理员',
    loading: false,
    stats: [
      { key: 'agent', label: 'Agent 数量', icon: 'robot', bg: '#4F46E5', value: '-', url: '/pages/agent/list/index', tab: true },
      { key: 'session', label: '会话数量', icon: 'chat', bg: '#00A870', value: '-', url: '/pages/session/list/index', tab: true },
      { key: 'task', label: '任务数量', icon: 'task', bg: '#ED7B2F', value: '-', url: '/pages/task/list/index', tab: false },
      { key: 'model', label: '模型数量', icon: 'layers', bg: '#E34D59', value: '-', url: '/pages/model/list/index', tab: false },
    ] as StatItem[],
    quickEntries: [
      { label: 'Agent', icon: 'robot', bg: '#4F46E5', url: '/pages/agent/list/index', tab: true },
      { label: '会话', icon: 'chat', bg: '#00A870', url: '/pages/session/list/index', tab: true },
      { label: '任务', icon: 'task', bg: '#ED7B2F', url: '/pages/task/list/index', tab: false },
      { label: '模型', icon: 'layers', bg: '#E34D59', url: '/pages/model/list/index', tab: false },
      { label: 'MCP', icon: 'link', bg: '#0EA5E9', url: '/pages/mcp/list/index', tab: false },
      { label: '技能', icon: 'star', bg: '#7C3AED', url: '/pages/skill/list/index', tab: false },
      { label: '工具', icon: 'tools', bg: '#14B8A6', url: '/pages/tool/list/index', tab: false },
      { label: '渠道', icon: 'internet', bg: '#2563EB', url: '/pages/channel/list/index', tab: false },
      { label: '系统', icon: 'setting', bg: '#86909C', url: '/pages/system/user-list/index', tab: false },
    ],
  },

  onLoad() {
    const info = getUserInfo();
    if (info) {
      this.setData({ username: info.nickname || info.username || '管理员' });
    }
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 });
    }
    this.loadStats();
  },

  onPullDownRefresh() {
    this.loadStats().finally(() => wx.stopPullDownRefresh());
  },

  async loadStats() {
    this.setData({ loading: true });
    const query = { pageNum: 1, pageSize: 1 };
    const results = await Promise.allSettled([
      getAgentPage(query),
      getSessionPage(query),
      getAgentTaskPage(query),
      getModelPage(query),
    ]);
    const stats = this.data.stats.map((s, i) => {
      const r = results[i];
      const value = r.status === 'fulfilled' && r.value ? (r.value.total ?? 0) : '-';
      return { ...s, value };
    });
    this.setData({ stats, loading: false });
  },

  onStatTap(e: WechatMiniprogram.TouchEvent) {
    const { url, tab } = e.currentTarget.dataset;
    this.goto(url, tab);
  },

  onQuickTap(e: WechatMiniprogram.TouchEvent) {
    const { url, tab } = e.currentTarget.dataset;
    this.goto(url, tab);
  },

  goto(url: string, isTab?: boolean) {
    if (!url) return;
    if (isTab) {
      wx.switchTab({ url, fail: () => wx.navigateTo({ url }) });
    } else {
      wx.navigateTo({ url });
    }
  },
});
