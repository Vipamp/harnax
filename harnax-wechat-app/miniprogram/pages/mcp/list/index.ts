// pages/mcp/list/index.ts
import { getMcpPage, toggleMcp, deleteMcp } from '../../../services/mcp';
import { PAGE_SIZE } from '../../../utils/constants';

Page({
  data: {
    list: [] as API.McpItem[],
    keyword: '',
    pageNum: 1,
    total: 0,
    loading: false,
    loadingMore: false,
    noMore: false,
  },

  onShow() {
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
      const res = await getMcpPage({
        pageNum: 1,
        pageSize: PAGE_SIZE,
        keyword: this.data.keyword || undefined,
      });
      this.setData({
        list: res.records || [],
        total: res.total || 0,
        noMore: (res.records || []).length >= (res.total || 0),
      });
    } catch (e) {
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadMore() {
    if (this.data.loadingMore || this.data.noMore || this.data.loading) return;
    const next = this.data.pageNum + 1;
    this.setData({ loadingMore: true });
    try {
      const res = await getMcpPage({
        pageNum: next,
        pageSize: PAGE_SIZE,
        keyword: this.data.keyword || undefined,
      });
      const merged = this.data.list.concat(res.records || []);
      this.setData({
        list: merged,
        pageNum: next,
        noMore: merged.length >= (res.total || 0),
      });
    } catch (e) {
    } finally {
      this.setData({ loadingMore: false });
    }
  },

  onTapItem(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/mcp/detail/index?id=${id}` });
  },

  onEdit(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/mcp/form/index?id=${id}` });
  },

  async onToggle(e: WechatMiniprogram.TouchEvent) {
    const { id, status } = e.currentTarget.dataset as { id: number; status: number };
    try {
      await toggleMcp(id, status === 1 ? 0 : 1);
      this.refresh();
    } catch (e) {}
  },

  onDelete(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除 MCP',
      content: '确定删除该 MCP 服务吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteMcp(id);
          this.refresh();
        } catch (e) {}
      },
    });
  },

  onCreate() {
    wx.navigateTo({ url: '/pages/mcp/form/index' });
  },
});
