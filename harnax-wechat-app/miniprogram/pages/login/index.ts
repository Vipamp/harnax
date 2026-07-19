// pages/login/index.ts
import { login } from '../../services/auth';
import { saveAuth } from '../../utils/auth';
import { sha256 } from '../../utils/crypto';
import { getStorage, setStorage } from '../../utils/storage';
import { STORAGE_KEYS } from '../../utils/constants';

Page({
  data: {
    baseUrl: '',
    routerUrl: '',
    username: '',
    password: '',
    autoLogin: true,
    showPwd: false,
    showAdvanced: false,
    loading: false,
  },

  onLoad() {
    // 回填上次输入的服务器地址与用户名
    const baseUrl = getStorage<string>(STORAGE_KEYS.BASE_URL, '') || '';
    const routerUrl = getStorage<string>('lastRouterUrl', '') || '';
    const username = getStorage<string>('lastUsername', '') || '';
    this.setData({ baseUrl, routerUrl, username });
  },

  onBaseUrlInput(e: any) {
    this.setData({ baseUrl: (e.detail.value || '').trim() });
  },

  onUsernameInput(e: any) {
    this.setData({ username: (e.detail.value || '').trim() });
  },

  onPasswordInput(e: any) {
    this.setData({ password: e.detail.value || '' });
  },

  togglePwd() {
    this.setData({ showPwd: !this.data.showPwd });
  },

  onRememberChange(e: any) {
    this.setData({ autoLogin: (e.detail.value || []).length > 0 });
  },

  toggleAdvanced() {
    this.setData({ showAdvanced: !this.data.showAdvanced });
  },

  onRouterUrlInput(e: any) {
    this.setData({ routerUrl: (e.detail.value || '').trim() });
  },

  async onSubmit() {
    if (this.data.loading) return;
    const { baseUrl, username, password, autoLogin } = this.data;

    if (!baseUrl) {
      wx.showToast({ title: '请输入服务器地址', icon: 'none' });
      return;
    }
    if (!/^https?:\/\//.test(baseUrl)) {
      wx.showToast({ title: '地址需以 http:// 或 https:// 开头', icon: 'none' });
      return;
    }
    if (!username) {
      wx.showToast({ title: '请输入用户名', icon: 'none' });
      return;
    }
    if (!password) {
      wx.showToast({ title: '请输入密码', icon: 'none' });
      return;
    }

    // 先保存服务器地址，供 request 使用
    const normalizedUrl = baseUrl.replace(/\/+$/, '');
    setStorage(STORAGE_KEYS.BASE_URL, normalizedUrl);

    this.setData({ loading: true });
    try {
      const res = await login({
        username,
        password: sha256(password),
        autoLogin,
        type: 'account',
      });

      if (res.code === 200 && res.data) {
        const data: any = res.data;
        saveAuth({
          accessToken: data.accessToken,
          tokenType: data.tokenType,
          expiresIn: data.expiresIn,
          routerApiKey: data.routerApiKey,
          // Use user-entered routerUrl if provided, otherwise use backend's
          routerUrl: this.data.routerUrl.replace(/\/+$/, '') || data.routerUrl,
          userInfo: data.userInfo,
        });
        setStorage('lastUsername', username);
        setStorage('lastRouterUrl', this.data.routerUrl);
        const app = getApp<IAppOption>();
        if (app) {
          app.globalData.userInfo = data.userInfo;
        }
        wx.showToast({ title: '登录成功', icon: 'success' });
        setTimeout(() => {
          wx.reLaunch({ url: '/pages/index/index' });
        }, 500);
      } else {
        wx.showToast({ title: res.message || '登录失败', icon: 'none' });
      }
    } catch (err: any) {
      wx.showToast({ title: err?.message || '登录失败，请检查网络', icon: 'none' });
    } finally {
      this.setData({ loading: false });
    }
  },
});
