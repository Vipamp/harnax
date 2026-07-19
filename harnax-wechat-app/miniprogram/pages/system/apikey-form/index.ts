// pages/system/apikey-form/index.ts
import { getApiKeyById, createApiKey, updateApiKey } from '../../../services/apikey';

const SCOPE_OPTIONS = ['ADMIN', 'ROUTER'];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    scopes: SCOPE_OPTIONS,
    scopeOn: [false, false] as boolean[],
    form: {
      name: '',
      rateLimit: 1000,
      expiresAt: '',
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑 API Key' : '新建 API Key' });
    if (id) {
      this.setData({ loading: true });
      try {
        const d = await getApiKeyById(id);
        const scopeOn = SCOPE_OPTIONS.map((s) => (d.scopes || '').split(',').map((t) => t.trim()).includes(s));
        this.setData({
          scopeOn,
          form: {
            name: d.name || '',
            rateLimit: d.rateLimit || 1000,
            expiresAt: (d.expiresAt || '').slice(0, 10),
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

  onRateInput(e: any) {
    const v = Number(e.detail.value) || 0;
    this.setData({ 'form.rateLimit': v });
  },

  onToggleScope(e: WechatMiniprogram.TouchEvent) {
    const idx = Number(e.currentTarget.dataset.idx);
    const next = this.data.scopeOn.slice();
    next[idx] = !next[idx];
    this.setData({ scopeOn: next });
  },

  onDateChange(e: any) {
    this.setData({ 'form.expiresAt': e.detail.value as string });
  },

  buildScopes(): string {
    return SCOPE_OPTIONS.filter((_, i) => this.data.scopeOn[i]).join(',');
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim() && !this.data.isEdit) {
      wx.showToast({ title: '请输入 Key 名称', icon: 'none' });
      return;
    }
    const scopes = this.buildScopes();
    if (!scopes) {
      wx.showToast({ title: '请至少选择一个权限范围', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        const payload: API.ApiKeyUpdateRequest = {
          scopes,
          rateLimit: form.rateLimit || undefined,
          expiresAt: form.expiresAt ? `${form.expiresAt}T23:59:59` : undefined,
        };
        await updateApiKey(this.data.id, payload);
        wx.showToast({ title: '已更新', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 600);
      } else {
        const payload: API.ApiKeyCreateRequest = {
          name: form.name.trim(),
          scopes,
          rateLimit: form.rateLimit || 1000,
          expiresAt: form.expiresAt ? `${form.expiresAt}T23:59:59` : undefined,
        };
        const r = await createApiKey(payload);
        wx.showModal({
          title: 'API Key 已创建',
          content: `请妥善保存：\n${r.rawKey}\n该 Key 仅显示一次。`,
          confirmText: '复制',
          success: (res) => {
            if (res.confirm) wx.setClipboardData({ data: r.rawKey });
            wx.navigateBack();
          },
        });
      }
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
