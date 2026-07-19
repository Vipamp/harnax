// pages/model/list/index.ts
import {
  getProviderPage,
  toggleProvider,
  deleteProvider,
  testProvider,
  getModelPage,
  toggleModel,
  deleteModel,
} from '../../../services/model';
import { PAGE_SIZE } from '../../../utils/constants';

Page({
  data: {
    tab: 'provider' as 'provider' | 'model',
    providers: [] as API.ModelProviderItem[],
    models: [] as API.ModelItem[],
    loading: false,
  },

  onShow() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().finally(() => wx.stopPullDownRefresh());
  },

  onTab(e: WechatMiniprogram.TouchEvent) {
    const tab = e.currentTarget.dataset.tab as 'provider' | 'model';
    if (tab === this.data.tab) return;
    this.setData({ tab });
    this.reload();
  },

  async reload() {
    this.setData({ loading: true });
    try {
      if (this.data.tab === 'provider') {
        const res = await getProviderPage({ pageNum: 1, pageSize: PAGE_SIZE });
        this.setData({ providers: res.records || [] });
      } else {
        const res = await getModelPage({ pageNum: 1, pageSize: PAGE_SIZE });
        this.setData({ models: res.records || [] });
      }
    } catch (e) {
      // ignore
    } finally {
      this.setData({ loading: false });
    }
  },

  // ---- 服务商 ----
  onEditProvider(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/model/provider-form/index?id=${id}` });
  },

  async onToggleProvider(e: WechatMiniprogram.TouchEvent) {
    const { id, status } = e.currentTarget.dataset as { id: number; status: number };
    try {
      await toggleProvider(id, status === 1 ? 0 : 1);
      this.reload();
    } catch (e) {}
  },

  async onTestProvider(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showLoading({ title: '测试中' });
    try {
      const ok = await testProvider(id);
      wx.hideLoading();
      wx.showToast({ title: ok ? '连接正常' : '连接失败', icon: ok ? 'success' : 'none' });
    } catch (e) {
      wx.hideLoading();
    }
  },

  onDeleteProvider(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除服务商',
      content: '确定删除该服务商吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteProvider(id);
          this.reload();
        } catch (e) {}
      },
    });
  },

  // ---- 模型 ----
  onEditModel(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/model/model-form/index?id=${id}` });
  },

  async onToggleModel(e: WechatMiniprogram.TouchEvent) {
    const { id, status } = e.currentTarget.dataset as { id: number; status: number };
    try {
      await toggleModel(id, status === 1 ? 0 : 1);
      this.reload();
    } catch (e) {}
  },

  onDeleteModel(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除模型',
      content: '确定删除该模型吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteModel(id);
          this.reload();
        } catch (e) {}
      },
    });
  },

  onCreate() {
    if (this.data.tab === 'provider') {
      wx.navigateTo({ url: '/pages/model/provider-form/index' });
    } else {
      wx.navigateTo({ url: '/pages/model/model-form/index' });
    }
  },
});
