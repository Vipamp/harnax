// pages/model/provider-form/index.ts
import {
  getProviderById,
  createProvider,
  updateProvider,
} from '../../../services/model';

const PROVIDER_TYPES = [
  'openai',
  'azure',
  'deepseek',
  'zhipu',
  'dashscope',
  'moonshot',
  'ollama',
  'anthropic',
  'other',
];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    types: PROVIDER_TYPES,
    typeIndex: 0,
    form: {
      type: PROVIDER_TYPES[0],
      name: '',
      baseUrl: '',
      apiKey: '',
      description: '',
      isPublic: 0,
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑服务商' : '新建服务商' });
    if (id) {
      this.setData({ loading: true });
      try {
        const d = await getProviderById(id);
        const typeIndex = Math.max(0, PROVIDER_TYPES.indexOf(d.type));
        this.setData({
          typeIndex,
          form: {
            type: d.type || PROVIDER_TYPES[0],
            name: d.name || '',
            baseUrl: d.baseUrl || '',
            apiKey: d.apiKey || '',
            description: d.description || '',
            isPublic: 0,
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

  onTypeChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ typeIndex: idx, 'form.type': PROVIDER_TYPES[idx] });
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim()) {
      wx.showToast({ title: '请输入名称', icon: 'none' });
      return;
    }
    const payload: API.ModelProviderCreateRequest = {
      type: form.type,
      name: form.name.trim(),
      baseUrl: form.baseUrl || undefined,
      apiKey: form.apiKey || undefined,
      description: form.description || undefined,
    };
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateProvider(this.data.id, { ...payload, id: this.data.id });
      } else {
        await createProvider(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
