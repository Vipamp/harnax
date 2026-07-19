// pages/session/list/index.ts
import { getSessionPage, deleteSession } from '../../../services/session';
import { PAGE_SIZE } from '../../../utils/constants';
import { formatRelativeTime } from '../../../utils/format';

interface SessionRow extends API.SessionItem {
  _time: string;
}

Page({
  data: {
    list: [] as SessionRow[],
    keyword: '',
    pageNum: 1,
    total: 0,
    loading: false,
    loadingMore: false,
    noMore: false,
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2 });
    }
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

  decorate(records: API.SessionItem[]): SessionRow[] {
    return (records || []).map((r) => ({ ...r, _time: formatRelativeTime(r.updateTime || r.createTime) }));
  },

  async refresh() {
    this.setData({ pageNum: 1, noMore: false, loading: true });
    try {
      const res = await getSessionPage({
        pageNum: 1,
        pageSize: PAGE_SIZE,
        keyword: this.data.keyword || undefined,
      });
      const list = this.decorate(res.records);
      this.setData({
        list,
        total: res.total || 0,
        noMore: list.length >= (res.total || 0),
      });
    } catch (e) {
      // ignore
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadMore() {
    if (this.data.loadingMore || this.data.noMore || this.data.loading) return;
    const next = this.data.pageNum + 1;
    this.setData({ loadingMore: true });
    try {
      const res = await getSessionPage({
        pageNum: next,
        pageSize: PAGE_SIZE,
        keyword: this.data.keyword || undefined,
      });
      const merged = this.data.list.concat(this.decorate(res.records));
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
    const item = e.currentTarget.dataset.item as API.SessionItem;
    const sid = item.sessionId || item.id;
    wx.navigateTo({
      url: `/pages/session/chat/index?sessionId=${sid}&title=${encodeURIComponent(item.title)}`,
    });
  },

  onDelete(e: WechatMiniprogram.TouchEvent) {
    const { id, title } = e.currentTarget.dataset;
    wx.showModal({
      title: '删除确认',
      content: `确定删除会话「${title}」吗？`,
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteSession(Number(id));
          wx.showToast({ title: '已删除', icon: 'success' });
          this.refresh();
        } catch (err) {
          // ignore
        }
      },
    });
  },

  onCreate() {
    wx.navigateTo({ url: '/pages/session/create/index' });
  },
});