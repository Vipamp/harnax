// pages/system/env-list/index.ts
import { getEnvVariablePage, deleteEnvVariable, toggleEnvVariable } from '../../../services/envvar';
import { PAGE_SIZE } from '../../../utils/constants';

Page({
  data: {
    list: [] as API.EnvVariableItem[],
    keyword: '',
    pageNum: 1,
    total: 0,
    loading: false,
    loadingMore: false,
    noMore: false,
  },

  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  onReachBottom() { this.loadMore(); },
  onSearch(e: WechatMiniprogram.CustomEvent<{ value: string }>) {
    this.setData({ keyword: e.detail.value });
    this.refresh();
  },

  async refresh() {
    this.setData({ pageNum: 1, noMore: false, loading: true });
    try {
      const res = await getEnvVariablePage({ pageNum: 1, pageSize: PAGE_SIZE, keyword: this.data.keyword || undefined });
      this.setData({
        list: res.records || [],
        total: res.total || 0,
        noMore: (res.records || []).length >= (res.total || 0),
      });
    } catch (e) {} finally {
      this.setData({ loading: false });
    }
  },

  async loadMore() {
    if (this.data.loadingMore || this.data.noMore || this.data.loading) return;
    const next = this.data.pageNum + 1;
    this.setData({ loadingMore: true });
    try {
      const res = await getEnvVariablePage({ pageNum: next, pageSize: PAGE_SIZE, keyword: this.data.keyword || undefined });
      const merged = this.data.list.concat(res.records || []);
      this.setData({ list: merged, pageNum: next, noMore: merged.length >= (res.total || 0) });
    } catch (e) {} finally {
      this.setData({ loadingMore: false });
    }
  },

  onEdit(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/system/env-form/index?id=${id}` });
  },

  async onToggle(e: WechatMiniprogram.TouchEvent) {
    const { id, enabled } = e.currentTarget.dataset as { id: number; enabled: number };
    const next = enabled === 1 ? 0 : 1;
    try {
      await toggleEnvVariable(id, next);
      wx.showToast({ title: next === 1 ? '已启用' : '已禁用', icon: 'none' });
      this.refresh();
    } catch (e) {}
  },

  onDelete(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除变量',
      content: '确定删除该环境变量吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try { await deleteEnvVariable(id); this.refresh(); } catch (e) {}
      },
    });
  },

  onCreate() {
    wx.navigateTo({ url: '/pages/system/env-form/index' });
  },
});
