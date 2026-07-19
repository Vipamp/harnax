// pages/session/create/index.ts
import { createSession, getAgentOptions } from '../../../services/session';

Page({
  data: {
    loading: false,
    submitting: false,
    agents: [] as API.AgentItem[],
    agentNames: [] as string[],
    agentIndex: -1,
    selectedAgent: null as API.AgentItem | null,
    title: '',
    description: '',
  },

  onLoad() {
    this.loadAgents();
  },

  async loadAgents() {
    this.setData({ loading: true });
    try {
      const res = await getAgentOptions();
      const agents = res.records || [];
      this.setData({
        agents,
        agentNames: agents.map((a) => a.name),
      });
    } catch (e) {
      // ignore
    } finally {
      this.setData({ loading: false });
    }
  },

  onAgentChange(e: any) {
    const idx = Number(e.detail.value);
    const agent = this.data.agents[idx];
    // 未填写标题时自动带出默认标题
    const title = this.data.title || (agent ? `与 ${agent.name} 的对话` : '');
    this.setData({ agentIndex: idx, selectedAgent: agent || null, title });
  },

  onTitleInput(e: any) {
    this.setData({ title: e.detail.value });
  },

  onDescInput(e: any) {
    this.setData({ description: e.detail.value });
  },

  async onSubmit() {
    const { selectedAgent, title, description } = this.data;
    if (!selectedAgent) {
      wx.showToast({ title: '请选择 Agent', icon: 'none' });
      return;
    }
    if (!title.trim()) {
      wx.showToast({ title: '请输入会话标题', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    try {
      await createSession({
        title: title.trim(),
        sessionDescription: description || undefined,
        agentId: selectedAgent.id,
      });
      wx.showToast({ title: '创建成功', icon: 'success' });
      // Backend returns Void, navigate back to session list
      setTimeout(() => wx.navigateBack(), 500);
    } catch (e) {
      // ignore
    } finally {
      this.setData({ submitting: false });
    }
  },
});
