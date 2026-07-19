// pages/task/list/index.ts
import {
  getAgentTaskPage,
  toggleAgentTaskStatus,
  triggerAgentTask,
  deleteAgentTask,
} from '../../../services/task';
import { PAGE_SIZE } from '../../../utils/constants';
import { formatDateTime } from '../../../utils/format';

Page({
  data: {
    list: [] as API.AgentTaskItem[],
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

  noop() { /* prevent event bubbling */ },

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

  decorate(records: API.AgentTaskItem[]) {
    return records.map((r) => ({
      ...r,
      lastRunTimeText: r.lastRunTime ? formatDateTime(r.lastRunTime) : '未运行',
    }));
  },

  async refresh() {
    this.setData({ pageNum: 1, noMore: false, loading: true });
    try {
      const res = await getAgentTaskPage({
        pageNum: 1,
        pageSize: PAGE_SIZE,
        name: this.data.keyword || undefined,
      });
      const records = this.decorate(res.records || []);
      this.setData({
        list: records,
        total: res.total || 0,
        noMore: records.length >= (res.total || 0),
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
      const res = await getAgentTaskPage({
        pageNum: next,
        pageSize: PAGE_SIZE,
        name: this.data.keyword || undefined,
      });
      const merged = this.data.list.concat(this.decorate(res.records || []));
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
    wx.navigateTo({ url: `/pages/task/log/index?id=${id}` });
  },

  onEdit(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/task/form/index?id=${id}` });
  },

  async onToggle(e: WechatMiniprogram.TouchEvent) {
    const { id, status } = e.currentTarget.dataset as { id: number; status: number };
    const next = status === 1 ? 0 : 1;
    try {
      await toggleAgentTaskStatus(id, next);
      wx.showToast({ title: next === 1 ? '已启用' : '已停用', icon: 'success' });
      this.refresh();
    } catch (e) {
      // ignore
    }
  },

  async onTrigger(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    try {
      await triggerAgentTask(id);
      wx.showToast({ title: '已触发', icon: 'success' });
    } catch (e) {
      // ignore
    }
  },

  onDelete(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除任务',
      content: '确定删除该任务吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteAgentTask(id);
          wx.showToast({ title: '已删除', icon: 'success' });
          this.refresh();
        } catch (e) {
          // ignore
        }
      },
    });
  },

  onCreate() {
    wx.navigateTo({ url: '/pages/task/form/index' });
  },
});
