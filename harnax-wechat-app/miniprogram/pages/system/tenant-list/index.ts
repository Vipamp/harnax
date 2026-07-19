// pages/system/tenant-list/index.ts
import {
  getTenantList,
  toggleTenantStatus,
  deleteTenant,
  getTenantUsers,
  removeUserFromTenant,
} from '../../../services/tenant';
import { PAGE_SIZE } from '../../../utils/constants';

Page({
  data: {
    list: [] as API.TenantItem[],
    keyword: '',
    pageNum: 1,
    total: 0,
    loading: false,
    loadingMore: false,
    noMore: false,
    // 成员管理弹层
    showMembers: false,
    members: [] as any[],
    currentTenantId: 0,
    currentTenantName: '',
    membersLoading: false,
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
      const res = await getTenantList({ pageNum: 1, pageSize: PAGE_SIZE, name: this.data.keyword || undefined });
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
      const res = await getTenantList({ pageNum: next, pageSize: PAGE_SIZE, name: this.data.keyword || undefined });
      const merged = this.data.list.concat(res.records || []);
      this.setData({ list: merged, pageNum: next, noMore: merged.length >= (res.total || 0) });
    } catch (e) {} finally {
      this.setData({ loadingMore: false });
    }
  },

  onEdit(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/system/tenant-form/index?id=${id}` });
  },

  async onToggle(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '切换状态',
      content: '确定切换该租户的启用/禁用状态吗？',
      success: async (res) => {
        if (!res.confirm) return;
        try { await toggleTenantStatus(id); this.refresh(); } catch (e) {}
      },
    });
  },

  onDelete(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除租户',
      content: '删除后数据不可恢复，确定删除吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try { await deleteTenant(id); this.refresh(); } catch (e) {}
      },
    });
  },

  async onManage(e: WechatMiniprogram.TouchEvent) {
    const { id, name } = e.currentTarget.dataset as { id: number; name: string };
    this.setData({ showMembers: true, currentTenantId: id, currentTenantName: name, membersLoading: true });
    try {
      const res = await getTenantUsers(id, { pageNum: 1, pageSize: 100 });
      this.setData({ members: res.records || [], membersLoading: false });
    } catch (e) {
      this.setData({ membersLoading: false });
    }
  },

  onCloseMembers() {
    this.setData({ showMembers: false, members: [] });
  },

  onRemoveUser(e: WechatMiniprogram.TouchEvent) {
    const userId = e.currentTarget.dataset.userId;
    const name = e.currentTarget.dataset.name;
    wx.showModal({
      title: '移除用户',
      content: `确定从「${this.data.currentTenantName}」移除用户「${name}」吗？`,
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await removeUserFromTenant(this.data.currentTenantId, userId);
          wx.showToast({ title: '已移除', icon: 'success' });
          this.onManage({ currentTarget: { dataset: { id: this.data.currentTenantId, name: this.data.currentTenantName } } } as any);
        } catch (e) {}
      },
    });
  },

  onCreate() {
    wx.navigateTo({ url: '/pages/system/tenant-form/index' });
  },
});
