// pages/mcp/detail/index.ts
import { getMcpById, toggleMcp, deleteMcp, testMcp, getMcpTools } from '../../../services/mcp';
import { formatDateTime } from '../../../utils/format';

Page({
  data: {
    id: 0,
    detail: null as API.McpItem | null,
    createTime: '-',
    loading: false,
    testing: false,
    testResult: '' as string,
    testOk: false,
    loadingTools: false,
    tools: [] as any[],
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
      const detail = await getMcpById(this.data.id);
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

  async onTest() {
    if (this.data.testing) return;
    this.setData({ testing: true, testResult: '' });
    try {
      const res = await testMcp(this.data.id);
      const ok = res === true || (res && (res.success || res.connected || res.status === 'ok'));
      this.setData({
        testOk: !!ok,
        testResult: ok ? '连接成功' : (typeof res === 'string' ? res : (res && res.message) || '连接失败'),
      });
    } catch (e: any) {
      this.setData({ testOk: false, testResult: e?.message || '连接失败' });
    } finally {
      this.setData({ testing: false });
    }
  },

  async onListTools() {
    if (this.data.loadingTools) return;
    this.setData({ loadingTools: true });
    try {
      const res = await getMcpTools(this.data.id);
      const tools = Array.isArray(res) ? res : (res && res.tools) || [];
      this.setData({ tools });
      if (!tools.length) wx.showToast({ title: '暂无可用工具', icon: 'none' });
    } catch (e) {
    } finally {
      this.setData({ loadingTools: false });
    }
  },

  async onToggle() {
    const { detail } = this.data;
    if (!detail) return;
    const next = detail.status === 1 ? 0 : 1;
    try {
      await toggleMcp(detail.id, next);
      wx.showToast({ title: next === 1 ? '已启用' : '已禁用', icon: 'success' });
      this.loadDetail();
    } catch (e) {}
  },

  onEdit() {
    wx.navigateTo({ url: `/pages/mcp/form/index?id=${this.data.id}` });
  },

  onDelete() {
    const { detail } = this.data;
    if (!detail) return;
    wx.showModal({
      title: '删除确认',
      content: `确定删除 MCP「${detail.name}」吗？`,
      confirmColor: '#ff4d4f',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await deleteMcp(detail.id);
          wx.showToast({ title: '已删除', icon: 'success' });
          setTimeout(() => wx.navigateBack(), 500);
        } catch (e) {}
      },
    });
  },
});
