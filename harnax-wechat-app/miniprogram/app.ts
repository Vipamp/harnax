// app.ts
App<IAppOption>({
  globalData: {
    version: '1.0.0',
    userInfo: undefined,
  },
  onLaunch() {
    // 检查登录态：未登录跳转登录页
    const token = wx.getStorageSync('accessToken');
    if (!token) {
      wx.reLaunch({ url: '/pages/login/index' });
    }
  },
});
