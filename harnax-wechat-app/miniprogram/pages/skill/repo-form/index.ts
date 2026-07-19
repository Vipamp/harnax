// pages/skill/repo-form/index.ts
import { getRepoById, createRepo, updateRepo } from '../../../services/skill';

const SOURCE_TYPES = ['GIT', 'NPM'];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    sourceTypes: SOURCE_TYPES,
    sourceTypeIndex: 0,
    form: {
      name: '',
      sourceType: 'GIT',
      url: '',
      branch: 'main',
      packageName: '',
      registry: '',
      version: '',
      description: '',
      status: 1,
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑仓库' : '新建仓库' });
    if (id) {
      this.setData({ loading: true });
      try {
        const d = await getRepoById(id);
        const sc = d.sourceConfig || {};
        const sourceType = d.sourceType || 'GIT';
        const sourceTypeIndex = Math.max(0, SOURCE_TYPES.indexOf(sourceType));
        this.setData({
          sourceTypeIndex,
          form: {
            name: d.name || '',
            sourceType,
            url: sc.url || d.url || '',
            branch: sc.branch || d.branch || 'main',
            packageName: sc.packageName || '',
            registry: sc.registry || '',
            version: d.version || '',
            description: d.description || '',
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

  onSourceTypeChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ sourceTypeIndex: idx, 'form.sourceType': SOURCE_TYPES[idx] });
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
    let sourceConfig: Record<string, any> = {};
    if (form.sourceType === 'GIT') {
      if (!form.url.trim()) {
        wx.showToast({ title: '请输入仓库地址', icon: 'none' });
        return;
      }
      sourceConfig = { url: form.url.trim(), branch: form.branch || 'main' };
    } else if (form.sourceType === 'NPM') {
      if (!form.packageName.trim()) {
        wx.showToast({ title: '请输入包名', icon: 'none' });
        return;
      }
      sourceConfig = { packageName: form.packageName.trim(), registry: form.registry || '' };
    }
    const payload: API.SkillRepositoryCreateRequest = {
      name: form.name.trim(),
      sourceType: form.sourceType,
      sourceConfig,
      version: form.version || undefined,
      description: form.description || undefined,
      status: form.status,
    };
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateRepo(this.data.id, { ...payload, id: this.data.id });
      } else {
        await createRepo(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
