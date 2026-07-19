// pages/task/log/index.ts
import { getAgentTaskLogs } from '../../../services/task';
import { PAGE_SIZE } from '../../../utils/constants';
import { formatDateTime, formatDuration } from '../../../utils/format';

const STATUS_MAP: Record<number, { text: string; type: string }> = {
  0: { text: '执行中', type: 'processing' },
  1: { text: '成功', type: 'success' },
  2: { text: '失败', type: 'error' },
};

Page({
  data: {
    taskId: 0,
    list: [] as any[],
    pageNum: 1,
    total: 0,
    loading: false,
    loadingMore: false,
    noMore: false,
  },

  onLoad(query: Record<string, string>) {
    const taskId = query.id ? Number(query.id) : 0;
    this.setData({ taskId });
    this.refresh();
  },

  onPullDownRefresh() {
    this.refresh().finally(() => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    this.loadMore();
  },

  decorate(records: API.AgentTaskLogItem[]) {
    return records.map((r) => {
      const st = STATUS_MAP[r.status] || { text: '未知', type: 'default' };
      return {
        ...r,
        statusText: st.text,
        statusType: st.type,
        startTimeText: formatDateTime(r.startTime),
        durationText: formatDuration(r.durationMs),
      };
    });
  },

  async refresh() {
    this.setData({ pageNum: 1, noMore: false, loading: true });
    try {
      const res = await getAgentTaskLogs(this.data.taskId, {
        pageNum: 1,
        pageSize: PAGE_SIZE,
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
      const res = await getAgentTaskLogs(this.data.taskId, {
        pageNum: next,
        pageSize: PAGE_SIZE,
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

  onToggleDetail(e: WechatMiniprogram.TouchEvent) {
    const idx = Number(e.currentTarget.dataset.idx);
    const key = `list[${idx}].expanded`;
    this.setData({ [key]: !this.data.list[idx].expanded });
  },
});
