// pages/model/model-form/index.ts
import {
  getModelById,
  createModel,
  updateModel,
  getProviderOptions,
} from '../../../services/model';

const MODEL_TYPES = ['chat', 'embedding', 'rerank', 'image'];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    providers: [] as API.ModelProviderItem[],
    providerNames: [] as string[],
    providerIndex: -1,
    modelTypes: MODEL_TYPES,
    modelTypeIndex: 0,
    form: {
      name: '',
      modelName: '',
      providerId: 0,
      modelType: MODEL_TYPES[0],
      description: '',
      price: 0,
      supportReasoning: 0,
      supportInternet: 0,
      supportTool: 0,
      supportMcp: 0,
      supportVision: 0,
      status: 1,
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑模型' : '新建模型' });
    this.setData({ loading: true });
    await this.loadProviders();
    if (id) {
      await this.loadDetail(id);
    }
    this.setData({ loading: false });
  },

  async loadProviders() {
    try {
      const res = await getProviderOptions();
      const providers = res.records || [];
      this.setData({
        providers,
        providerNames: providers.map((p) => p.name),
      });
    } catch (e) {}
  },

  async loadDetail(id: number) {
    try {
      const d = await getModelById(id);
      const providerIndex = this.data.providers.findIndex((p) => p.id === d.providerId);
      const modelTypeIndex = Math.max(0, MODEL_TYPES.indexOf(d.modelType));
      this.setData({
        providerIndex,
        modelTypeIndex,
        form: {
          name: d.name || '',
          modelName: d.modelName || '',
          providerId: d.providerId || 0,
          modelType: d.modelType || MODEL_TYPES[0],
          description: d.description || '',
          price: d.price || 0,
          supportReasoning: d.supportReasoning || 0,
          supportInternet: d.supportInternet || 0,
          supportTool: d.supportTool || 0,
          supportMcp: d.supportMcp || 0,
          supportVision: d.supportVision || 0,
          status: d.status,
        },
      });
    } catch (e) {}
  },

  onInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value });
  },

  onProviderChange(e: any) {
    const idx = Number(e.detail.value);
    const p = this.data.providers[idx];
    this.setData({ providerIndex: idx, 'form.providerId': p ? p.id : 0 });
  },

  onModelTypeChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ modelTypeIndex: idx, 'form.modelType': MODEL_TYPES[idx] });
  },

  onSwitch(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value ? 1 : 0 });
  },

  onStatusChange(e: any) {
    this.setData({ 'form.status': e.detail.value ? 1 : 0 });
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim()) {
      wx.showToast({ title: '请输入显示名称', icon: 'none' });
      return;
    }
    if (!form.modelName.trim()) {
      wx.showToast({ title: '请输入模型标识', icon: 'none' });
      return;
    }
    if (!form.providerId) {
      wx.showToast({ title: '请选择服务商', icon: 'none' });
      return;
    }
    const payload: API.ModelCreateRequest = {
      name: form.name.trim(),
      modelName: form.modelName.trim(),
      providerId: form.providerId,
      modelType: form.modelType,
      description: form.description || undefined,
      price: Number(form.price) || 0,
      supportReasoning: form.supportReasoning,
      supportInternet: form.supportInternet,
      supportTool: form.supportTool,
      supportMcp: form.supportMcp,
      supportVision: form.supportVision,
      status: form.status,
    };
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateModel(this.data.id, { ...payload, id: this.data.id });
      } else {
        await createModel(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
