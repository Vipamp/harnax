// pages/task/form/index.ts
import {
  getAgentTaskById,
  createAgentTask,
  updateAgentTask,
  getAvailableAgents,
} from '../../../services/task';

const CRON_PRESETS = [
  { label: '每分钟', value: '0 * * * * ?' },
  { label: '每小时', value: '0 0 * * * ?' },
  { label: '每天 9:00', value: '0 0 9 * * ?' },
  { label: '每周一 9:00', value: '0 0 9 ? * MON' },
  { label: '每月 1 号 0:00', value: '0 0 0 1 * ?' },
];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    agents: [] as API.AgentOption[],
    agentNames: [] as string[],
    agentIndex: -1,
    cronPresets: CRON_PRESETS,
    form: {
      name: '',
      agentId: 0,
      prompt: '',
      cronExpression: '',
      concurrent: 0,
      timeoutSeconds: 300,
      description: '',
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑任务' : '新建任务' });
    this.setData({ loading: true });
    await this.loadAgents();
    if (id) {
      await this.loadDetail(id);
    }
    this.setData({ loading: false });
  },

  async loadAgents() {
    try {
      const agents = await getAvailableAgents();
      this.setData({
        agents: agents || [],
        agentNames: (agents || []).map((a) => a.name),
      });
    } catch (e) {
      // ignore
    }
  },

  async loadDetail(id: number) {
    try {
      const d = await getAgentTaskById(id);
      const agentIndex = this.data.agents.findIndex((a) => a.id === d.agentId);
      this.setData({
        agentIndex,
        form: {
          name: d.name || '',
          agentId: d.agentId || 0,
          prompt: d.prompt || '',
          cronExpression: d.cronExpression || '',
          concurrent: d.concurrent || 0,
          timeoutSeconds: d.timeoutSeconds || 300,
          description: d.description || '',
        },
      });
    } catch (e) {
      // ignore
    }
  },

  onInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value });
  },

  onNumberInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    const num = parseInt(e.detail.value, 10);
    this.setData({ [`form.${field}`]: isNaN(num) ? 0 : num });
  },

  onAgentChange(e: any) {
    const idx = Number(e.detail.value);
    const agent = this.data.agents[idx];
    this.setData({ agentIndex: idx, 'form.agentId': agent ? agent.id : 0 });
  },

  onConcurrentChange(e: any) {
    this.setData({ 'form.concurrent': e.detail.value ? 1 : 0 });
  },

  onCronPreset(e: WechatMiniprogram.TouchEvent) {
    const value = e.currentTarget.dataset.value as string;
    this.setData({ 'form.cronExpression': value });
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim()) {
      wx.showToast({ title: '请输入任务名称', icon: 'none' });
      return;
    }
    if (!form.agentId) {
      wx.showToast({ title: '请选择 Agent', icon: 'none' });
      return;
    }
    if (!form.prompt.trim()) {
      wx.showToast({ title: '请输入任务提示词', icon: 'none' });
      return;
    }
    if (!form.cronExpression.trim()) {
      wx.showToast({ title: '请输入 Cron 表达式', icon: 'none' });
      return;
    }

    const payload: API.AgentTaskCreateRequest = {
      name: form.name.trim(),
      agentId: form.agentId,
      prompt: form.prompt.trim(),
      cronExpression: form.cronExpression.trim(),
      concurrent: form.concurrent,
      timeoutSeconds: form.timeoutSeconds,
      description: form.description || undefined,
    };

    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateAgentTask(this.data.id, payload);
      } else {
        await createAgentTask(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
      // ignore
    } finally {
      this.setData({ submitting: false });
    }
  },
});
