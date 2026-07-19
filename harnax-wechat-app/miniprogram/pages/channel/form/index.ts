// pages/channel/form/index.ts
import {
  getChannelById,
  createChannel,
  updateChannel,
  getAgentOptions,
} from '../../../services/channel';

const CHANNEL_TYPES = [
  { value: 'wecom', label: '企业微信' },
  { value: 'feishu', label: '飞书' },
  { value: 'dingtalk', label: '钉钉' },
  { value: 'http', label: 'HTTP' },
];
const COMM_MODES = [
  { value: 'webhook', label: 'Webhook' },
  { value: 'websocket', label: 'WebSocket' },
  { value: 'long_polling', label: '长轮询' },
];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    channelTypes: CHANNEL_TYPES,
    channelTypeLabels: CHANNEL_TYPES.map((t) => t.label),
    typeIndex: 0,
    commModes: COMM_MODES,
    commModeLabels: COMM_MODES.map((m) => m.label),
    commModeIndex: 0,
    agents: [] as API.AgentItem[],
    agentNames: [] as string[],
    agentIndex: -1,
    form: {
      name: '',
      type: 'wecom',
      agentId: 0,
      communicationMode: 'webhook',
      enabled: 1,
      description: '',
      // type-specific
      token: '',
      encodingAesKey: '',
      appId: '',
      appSecret: '',
      webhookUrl: '',
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑渠道' : '新建渠道' });
    await this.loadAgents();
    if (id) await this.loadDetail();
  },

  async loadAgents() {
    try {
      const res = await getAgentOptions();
      const agents = res.records || [];
      this.setData({ agents, agentNames: agents.map((a) => a.name) });
    } catch (e) {}
  },

  async loadDetail() {
    this.setData({ loading: true });
    try {
      const d = await getChannelById(this.data.id);
      const typeIndex = Math.max(0, CHANNEL_TYPES.findIndex((t) => t.value === d.type));
      const commModeIndex = Math.max(0, COMM_MODES.findIndex((m) => m.value === (d.communicationMode || 'webhook')));
      const agentIndex = this.data.agents.findIndex((a) => a.id === d.agentId);
      let config: Record<string, string> = {};
      if (d.configJson) {
        try { config = JSON.parse(d.configJson); } catch (e) {}
      }
      this.setData({
        typeIndex,
        commModeIndex,
        agentIndex,
        form: {
          name: d.name || '',
          type: d.type || 'wecom',
          agentId: d.agentId || 0,
          communicationMode: d.communicationMode || 'webhook',
          enabled: d.enabled === 0 ? 0 : 1,
          description: d.description || '',
          token: config.token || '',
          encodingAesKey: config.encodingAesKey || '',
          appId: config.appId || '',
          appSecret: config.appSecret || '',
          webhookUrl: config.webhookUrl || '',
        },
      });
    } catch (e) {
    } finally {
      this.setData({ loading: false });
    }
  },

  onInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value });
  },

  onTypeChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ typeIndex: idx, 'form.type': CHANNEL_TYPES[idx].value });
  },

  onCommModeChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ commModeIndex: idx, 'form.communicationMode': COMM_MODES[idx].value });
  },

  onAgentChange(e: any) {
    const idx = Number(e.detail.value);
    const agent = this.data.agents[idx];
    this.setData({ agentIndex: idx, 'form.agentId': agent ? agent.id : 0 });
  },

  onEnabledChange(e: any) {
    this.setData({ 'form.enabled': e.detail.value ? 1 : 0 });
  },

  buildConfig(): string | undefined {
    const { form } = this.data;
    const config: Record<string, string> = {};
    if (form.type === 'wecom') {
      if (form.token) config.token = form.token;
      if (form.encodingAesKey) config.encodingAesKey = form.encodingAesKey;
    } else if (form.type === 'feishu' || form.type === 'dingtalk') {
      if (form.appId) config.appId = form.appId;
      if (form.appSecret) config.appSecret = form.appSecret;
    } else if (form.type === 'http') {
      if (form.webhookUrl) config.webhookUrl = form.webhookUrl;
    }
    return Object.keys(config).length > 0 ? JSON.stringify(config) : undefined;
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim()) {
      wx.showToast({ title: '请输入渠道名称', icon: 'none' });
      return;
    }
    if (!form.agentId) {
      wx.showToast({ title: '请选择关联 Agent', icon: 'none' });
      return;
    }
    if (form.type === 'wecom' && (!form.token || !form.encodingAesKey)) {
      wx.showToast({ title: '请完善企业微信配置', icon: 'none' });
      return;
    }
    if ((form.type === 'feishu' || form.type === 'dingtalk') && (!form.appId || !form.appSecret)) {
      wx.showToast({ title: '请完善应用配置', icon: 'none' });
      return;
    }
    const payload: API.ChannelCreateRequest = {
      name: form.name.trim(),
      type: form.type,
      agentId: form.agentId,
      communicationMode: form.communicationMode,
      enabled: form.enabled,
      configJson: this.buildConfig(),
      description: form.description || undefined,
    };
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateChannel(this.data.id, { ...payload, id: this.data.id });
      } else {
        await createChannel(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
