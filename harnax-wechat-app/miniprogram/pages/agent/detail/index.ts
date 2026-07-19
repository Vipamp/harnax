// pages/agent/detail/index.ts
import { getAgentById, toggleAgentStatus, deleteAgent } from '../../../services/agent';
import { formatDateTime } from '../../../utils/format';

Page({
  data: {
    id: 0,
    detail: null as API.AgentItem | null,
    createTime: '-',
    loading: false,
  },

  onLoad(query: Record<string, string>) {
    this.setData({ id: Number(query.id) });
  },

  onShow() {
    if (this.data.id) this.loadDetail();
  },

  async loadDetail() {
    this.setData({ loading: true });
    try {
      const detail = await getAgentById(this.data.id);
      // 兜底数组，便于 wxml 直接取 length
      detail.mcpList = detail.mcpList || [];
      detail.toolList = detail.toolList || [];
      detail.skillList = detail.skillList || [];
      this.setData({
        detail,
        createTime: formatDateTime(detail.createTime),
      });
      wx.setNavigationBarTitle({ title: detail.name });
    } catch (e) {
      // ignore
    } finally {
      this.setData({ loading: false });
    }
  },

  async onToggle() {
    const { detail } = this.data;
    if (!detail) return;
    const next = detail.status === 1 ? 0 : 1;
    try {
      await toggleAgentStatus(detail.id, next);
      wx.showToast({ title: next === 1 ? '已启用' : '已禁用', icon: 'success' });
      this.loadDetail();
    } catch (e) {
      // ignore
    }
  },

  onEdit() {
    wx.navigateTo({ url: `/pages/agent/form/index?id=${this.data.id}` });
  },

  onDelete() {
    const { detail } = this.data;
    if (!detail) return;
    wx.showModal({
      title: '删除确认',
      content: `确定删除 Agent「${detail.name}」吗？`,
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteAgent(detail.id);
          wx.showToast({ title: '已删除', icon: 'success' });
          setTimeout(() => wx.navigateBack(), 500);
        } catch (e) {
          // ignore
        }
      },
    });
  },
});
