// pages/agent/list/index.ts
import { getAgentPage, toggleAgentStatus, deleteAgent } from '../../../services/agent';
import { PAGE_SIZE } from '../../../utils/constants';

/** 后端分页返回 mcpList/toolList/skillList 数组（无 count 字段），这里用长度计算统计数 */
function withCounts(r: API.AgentItem): API.AgentItem {
  return {
    ...r,
    mcpCount: (r.mcpList || []).length,
    toolCount: (r.toolList || []).length,
    skillCount: (r.skillList || []).length,
  };
}

Page({
  data: {
    list: [] as API.AgentItem[],
    keyword: '',
    pageNum: 1,
    total: 0,
    loading: false,
    loadingMore: false,
    noMore: false,
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 1 });
    }
    // 从表单返回后刷新
    this.refresh();
  },

  onPullDownRefresh() {
    this.refresh().finally(() => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    this.loadMore();
  },

  onSearch(e: WechatMiniprogram.CustomEvent<{ value: string }>) {
    this.setData({ keyword: e.detail.value });
    this.refresh();
  },

  async refresh() {
    this.setData({ pageNum: 1, noMore: false, loading: true });
    try {
      const res = await getAgentPage({
        pageNum: 1,
        pageSize: PAGE_SIZE,
        name: this.data.keyword || undefined,
      });
      this.setData({
        list: (res.records || []).map(withCounts),
        total: res.total || 0,
        noMore: (res.records || []).length >= (res.total || 0),
      });
    } catch (e) {
      // 错误已由 request 层提示
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadMore() {
    if (this.data.loadingMore || this.data.noMore || this.data.loading) return;
    const next = this.data.pageNum + 1;
    this.setData({ loadingMore: true });
    try {
      const res = await getAgentPage({
        pageNum: next,
        pageSize: PAGE_SIZE,
        name: this.data.keyword || undefined,
      });
      const merged = this.data.list.concat((res.records || []).map(withCounts));
      this.setData({
        list: merged,
        pageNum: next,
        noMore: merged.length >= (res.total || 0),
      });
    } catch (e) {
      // ignore
    } finally {
      this.setData({ loadingMore: false });
    }
  },

  onTapItem(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/agent/detail/index?id=${id}` });
  },

  async onToggle(e: WechatMiniprogram.TouchEvent) {
    const { id, status } = e.currentTarget.dataset as { id: number; status: number };
    const next = status === 1 ? 0 : 1;
    try {
      await toggleAgentStatus(id, next);
      wx.showToast({ title: next === 1 ? '已启用' : '已禁用', icon: 'none' });
      this.refresh();
    } catch (e) {}
  },

  onEdit(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/agent/form/index?id=${id}` });
  },

  onDelete(e: WechatMiniprogram.TouchEvent) {
    const { id, name } = e.currentTarget.dataset as { id: number; name: string };
    wx.showModal({
      title: '删除确认',
      content: `确定删除 Agent「${name}」吗？`,
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteAgent(id);
          wx.showToast({ title: '已删除', icon: 'success' });
          this.refresh();
        } catch (e) {}
      },
    });
  },

  onCreate() {
    wx.navigateTo({ url: '/pages/agent/form/index' });
  },
});