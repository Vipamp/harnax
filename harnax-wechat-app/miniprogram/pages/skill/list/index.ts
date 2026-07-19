// pages/skill/list/index.ts
import {
  getRepoPage,
  toggleRepo,
  deleteRepo,
  fetchRemoteSkills,
  batchSaveSkills,
  getSkillPage,
  toggleSkill,
  deleteSkill,
} from '../../../services/skill';
import { PAGE_SIZE } from '../../../utils/constants';

Page({
  data: {
    tab: 'repo' as 'repo' | 'skill',
    repos: [] as API.SkillRepositoryItem[],
    skills: [] as API.SkillItem[],
    loading: false,
  },

  onShow() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().finally(() => wx.stopPullDownRefresh());
  },

  onTab(e: WechatMiniprogram.TouchEvent) {
    const tab = e.currentTarget.dataset.tab as 'repo' | 'skill';
    if (tab === this.data.tab) return;
    this.setData({ tab });
    this.reload();
  },

  async reload() {
    this.setData({ loading: true });
    try {
      if (this.data.tab === 'repo') {
        const res = await getRepoPage({ pageNum: 1, pageSize: PAGE_SIZE });
        this.setData({ repos: res.records || [] });
      } else {
        const res = await getSkillPage({ pageNum: 1, pageSize: PAGE_SIZE });
        this.setData({ skills: res.records || [] });
      }
    } catch (e) {
    } finally {
      this.setData({ loading: false });
    }
  },

  // ---- 仓库 ----
  onEditRepo(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/skill/repo-form/index?id=${id}` });
  },

  async onSyncRepo(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id as number;
    wx.showLoading({ title: '拉取中' });
    try {
      const names = await fetchRemoteSkills(id);
      wx.hideLoading();
      if (!names || !names.length) {
        wx.showToast({ title: '未发现可导入技能', icon: 'none' });
        return;
      }
      wx.showModal({
        title: '同步技能',
        content: `发现 ${names.length} 个技能，是否全部导入？`,
        success: async (res) => {
          if (!res.confirm) return;
          wx.showLoading({ title: '导入中' });
          try {
            await batchSaveSkills(id, names);
            wx.hideLoading();
            wx.showToast({ title: '已导入', icon: 'success' });
            this.setData({ tab: 'skill' });
            this.reload();
          } catch (e) {
            wx.hideLoading();
          }
        },
      });
    } catch (e) {
      wx.hideLoading();
    }
  },

  async onToggleRepo(e: WechatMiniprogram.TouchEvent) {
    const { id, status } = e.currentTarget.dataset as { id: number; status: number };
    try {
      await toggleRepo(id, status === 1 ? 0 : 1);
      this.reload();
    } catch (e) {}
  },

  onDeleteRepo(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除仓库',
      content: '确定删除该技能仓库吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteRepo(id);
          this.reload();
        } catch (e) {}
      },
    });
  },

  // ---- 技能 ----
  onTapSkill(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/skill/detail/index?id=${id}` });
  },

  async onToggleSkill(e: WechatMiniprogram.TouchEvent) {
    const { id, status } = e.currentTarget.dataset as { id: number; status: number };
    try {
      await toggleSkill(id, status === 1 ? 0 : 1);
      this.reload();
    } catch (e) {}
  },

  onDeleteSkill(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除技能',
      content: '确定删除该技能吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteSkill(id);
          this.reload();
        } catch (e) {}
      },
    });
  },

  onCreate() {
    // 仅仓库可新建，技能通过仓库同步导入
    wx.navigateTo({ url: '/pages/skill/repo-form/index' });
  },
});
