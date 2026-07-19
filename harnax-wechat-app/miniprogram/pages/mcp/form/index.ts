// pages/mcp/form/index.ts
import { getMcpById, createMcp, updateMcp } from '../../../services/mcp';

const TRANSPORTS = ['stdio', 'sse', 'streamablehttp'];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    transports: TRANSPORTS,
    transportIndex: 0,
    form: {
      name: '',
      description: '',
      type: TRANSPORTS[0],
      command: '',
      url: '',
      status: 1,
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑 MCP' : '新建 MCP' });
    if (id) {
      this.setData({ loading: true });
      try {
        const d = await getMcpById(id);
        const type = d.type || 'stdio';
        const transportIndex = Math.max(0, TRANSPORTS.indexOf(type));
        this.setData({
          transportIndex,
          form: {
            name: d.name || '',
            description: d.description || '',
            type,
            command: d.command || '',
            url: d.url || '',
            status: d.status,
          },
        });
      } catch (e) {
      } finally {
        this.setData({ loading: false });
      }
    }
  },

  onInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value });
  },

  onTransportChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ transportIndex: idx, 'form.type': TRANSPORTS[idx] });
  },

  onStatusChange(e: any) {
    this.setData({ 'form.status': e.detail.value ? 1 : 0 });
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim()) {
      wx.showToast({ title: '请输入名称', icon: 'none' });
      return;
    }
    if (form.type === 'stdio' && !form.command.trim()) {
      wx.showToast({ title: '请输入启动命令', icon: 'none' });
      return;
    }
    if (form.type !== 'stdio' && !form.url.trim()) {
      wx.showToast({ title: '请输入服务 URL', icon: 'none' });
      return;
    }
    const payload: API.McpCreateRequest = {
      name: form.name.trim(),
      description: form.description || undefined,
      type: form.type,
      command: form.type === 'stdio' ? form.command : undefined,
      url: form.type !== 'stdio' ? form.url : undefined,
      status: form.status,
    };
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateMcp(this.data.id, { ...payload, id: this.data.id });
      } else {
        await createMcp(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
