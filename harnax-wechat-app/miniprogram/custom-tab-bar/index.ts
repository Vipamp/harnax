Component({
  data: {
    selected: 0,
    list: [
      { text: '仪表盘', pagePath: '/pages/index/index', icon: 'dashboard', iconActive: 'dashboard-filled' },
      { text: 'Agent',  pagePath: '/pages/agent/list/index', icon: 'robot', iconActive: 'robot-filled' },
      { text: '会话',   pagePath: '/pages/session/list/index', icon: 'chat', iconActive: 'chat-filled' },
      { text: '我的',   pagePath: '/pages/profile/index', icon: 'user', iconActive: 'user-filled' }
    ]
  },

  methods: {
    onChange(e: any) {
      const index = e.detail.value;
      const url = this.data.list[index].pagePath;
      wx.switchTab({ url });
    }
  }
});
