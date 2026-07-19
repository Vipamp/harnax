// pages/skill/detail/index.ts
import { getSkillById, toggleSkill, deleteSkill } from '../../../services/skill';
import { formatDateTime } from '../../../utils/format';

Page({
  data: {
    id: 0,
    detail: null as API.SkillItem | null,
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
      const detail = await getSkillById(this.data.id);
      this.setData({
        detail,
        createTime: formatDateTime(detail.createTime),
      });
      wx.setNavigationBarTitle({ title: detail.name });
    } catch (e) {
    } finally {
      this.setData({ loading: false });
    }
  },

  async onToggle() {
    const { detail } = this.data;
    if (!detail) return;
    const next = detail.status === 1 ? 0 : 1;
    try {
      await toggleSkill(detail.id, next);
      wx.showToast({ title: next === 1 ? '已启用' : '已禁用', icon: 'success' });
      this.loadDetail();
    } catch (e) {}
  },

  onDelete() {
    const { detail } = this.data;
    if (!detail) return;
    wx.showModal({
      title: '删除确认',
      content: `确定删除技能「${detail.name}」吗？`,
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteSkill(detail.id);
          wx.showToast({ title: '已删除', icon: 'success' });
          setTimeout(() => wx.navigateBack(), 500);
        } catch (e) {}
      },
    });
  },
});
