// pages/session/detail/index.ts
import { getSessionConfig } from '../../../services/chat';

/** 权限模式文案 */
const PERMISSION_TEXT: Record<string, string> = {
  auto: '自动执行',
  manual: '人工确认',
  ask: '按需确认',
  default: '默认',
};

Page({
  data: {
    sessionId: '',
    loading: true,
    detail: null as any,
    createTime: '',
    permissionText: '',
    capabilities: [] as string[],
  },

  onLoad(query: Record<string, string>) {
    const sessionId = query.sessionId || '';
    this.setData({ sessionId });
    wx.setNavigationBarTitle({ title: '会话详情' });
    if (sessionId) {
      this.loadDetail();
    } else {
      this.setData({ loading: false });
    }
  },

  async loadDetail() {
    this.setData({ loading: true });
    try {
      const detail = await getSessionConfig(this.data.sessionId);
      const caps: string[] = [];
      if (detail.modelSupportReasoning || detail.enableThink) caps.push('深度思考');
      if (detail.modelSupportInternet || detail.enableSearch) caps.push('联网搜索');
      if (detail.modelSupportVision) caps.push('视觉理解');
      if (detail.enablePlan) caps.push('任务规划');
      this.setData({
        detail,
        loading: false,
        capabilities: caps,
        permissionText: PERMISSION_TEXT[detail.permissionMode] || detail.permissionMode || '默认',
        createTime: this.formatTime(detail.createTime),
      });
    } catch (e: any) {
      this.setData({ loading: false });
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' });
    }
  },

  formatTime(t?: string | number): string {
    if (!t) return '-';
    const d = new Date(t);
    if (isNaN(d.getTime())) return String(t);
    const p = (n: number) => (n < 10 ? '0' + n : '' + n);
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  },

  onOpenWorkspace() {
    const { sessionId, detail } = this.data;
    const title = (detail && (detail.title || detail.name)) || '';
    wx.navigateTo({
      url: `/pages/session/workspace/index?sessionId=${sessionId}&title=${encodeURIComponent(title)}`,
    });
  },
});
