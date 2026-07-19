// pages/profile/index.ts
import { getUserInfo, getBaseUrl } from '../../utils/auth';
import { logoutApi } from '../../services/auth';
import { logout as clearAuth } from '../../utils/auth';

Page({
  data: {
    userInfo: undefined as IUserInfo | undefined,
    baseUrl: '',
    menus: [
      { title: '用户管理', icon: 'usergroup', url: '/pages/system/user-list/index' },
      { title: '租户管理', icon: 'city', url: '/pages/system/tenant-list/index' },
      { title: 'API Key', icon: 'lock-on', url: '/pages/system/apikey-list/index' },
      { title: '环境变量', icon: 'setting', url: '/pages/system/env-list/index' },
      { title: 'Token 监控', icon: 'chart-bar', url: '/pages/system/token-monitor/index' },
    ],
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 3 });
    }
    const userInfo = getUserInfo();
    const baseUrl = getBaseUrl();
    this.setData({ userInfo, baseUrl });
  },

  onGo(e: WechatMiniprogram.TouchEvent) {
    const url = e.currentTarget.dataset.url as string;
    wx.navigateTo({ url });
  },

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定退出当前账号吗？',
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await logoutApi();
        } catch (e) {
          // 即使后端接口失败，也清理本地态
        }
        clearAuth(false);
        wx.reLaunch({ url: '/pages/login/index' });
      },
    });
  },

  onCopyUrl() {
    wx.setClipboardData({
      data: this.data.baseUrl,
      success: () => wx.showToast({ title: '已复制', icon: 'success' }),
    });
  },
});