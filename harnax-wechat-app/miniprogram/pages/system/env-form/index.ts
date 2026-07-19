// pages/system/env-form/index.ts
import { getEnvVariableById, createEnvVariable, updateEnvVariable } from '../../../services/envvar';

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    form: {
      envKey: '',
      envValue: '',
      description: '',
      sensitive: 0,
      enabled: 1,
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑环境变量' : '新建环境变量' });
    if (id) {
      this.setData({ loading: true });
      try {
        const d = await getEnvVariableById(id);
        this.setData({
          form: {
            envKey: d.envKey || '',
            envValue: d.sensitive === 1 ? '' : (d.envValue || ''),
            description: d.description || '',
            sensitive: d.sensitive === 1 ? 1 : 0,
            enabled: d.enabled === 0 ? 0 : 1,
          },
        });
      } catch (e) {} finally {
        this.setData({ loading: false });
      }
    }
  },

  onInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value });
  },

  onSensitiveChange(e: any) {
    this.setData({ 'form.sensitive': e.detail.value ? 1 : 0 });
  },

  onEnabledChange(e: any) {
    this.setData({ 'form.enabled': e.detail.value ? 1 : 0 });
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.envKey.trim()) {
      wx.showToast({ title: '请输入变量名', icon: 'none' });
      return;
    }
    // 敏感变量编辑时，值可留空（保持原值）；其余情况必填
    const keepSensitiveValue = this.data.isEdit && form.sensitive === 1;
    if (!keepSensitiveValue && !form.envValue.trim()) {
      wx.showToast({ title: '请输入变量值', icon: 'none' });
      return;
    }
    const payload: API.EnvVariableCreateRequest = {
      envKey: form.envKey.trim(),
      description: form.description || undefined,
      sensitive: form.sensitive,
      enabled: form.enabled,
    };
    // 敏感变量编辑时，仅当用户输入新值才提交 envValue
    if (keepSensitiveValue) {
      if (form.envValue.trim() !== '') payload.envValue = form.envValue;
    } else {
      payload.envValue = form.envValue;
    }
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateEnvVariable(this.data.id, { ...payload, id: this.data.id });
      } else {
        await createEnvVariable(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
