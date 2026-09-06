// pages/skill/repo-form/index.ts
import { getRepoById, createRepo, updateRepo } from '../../../services/skill';
import { describeSkillInstall, showSkillInstallToast } from '../../../utils/skillInstall';

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
        const sourceTypeIndex = SOURCE_TYPES.indexOf(sourceType);
        if (sourceTypeIndex < 0) {
          // ZIP 来源的内容在上传时就固定了，内置仓库是平台只读的，两者在这个表单里都改不了。
          // 按 GIT 展示只会得到一个填不满的地址栏，所以直接说明原因并退回
          wx.showModal({
            title: '无法编辑',
            content: `${sourceType} 类型的仓库不支持在小程序里编辑`,
            showCancel: false,
            complete: () => wx.navigateBack(),
          });
          return;
        }
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
    // 两个接口共用的字段；sourceType 只在创建时发送，更新接口换不了来源类型
    const payload = {
      name: form.name.trim(),
      sourceConfig,
      version: form.version || undefined,
      description: form.description || undefined,
      status: form.status,
    };
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        await updateRepo(this.data.id, payload);
        // PUT 只写配置、不重新拉取。先返回列表再提示，toast 才不会随页面一起销毁；
        // 报「已保存」会让用户以为改完地址就已经生效了
        wx.navigateBack({
          complete: () =>
            wx.showToast({
              title: '配置已保存，地址或包名变更后要在列表里重新同步才会生效',
              icon: 'none',
              duration: 6000,
            }),
        });
        return;
      }
      // 创建即安装，返回 { source, install }：HTTP 成功不等于技能全部落库
      const result = await createRepo({ ...payload, sourceType: form.sourceType });
      wx.navigateBack({
        complete: () => showSkillInstallToast(describeSkillInstall(result?.install, '已导入')),
      });
    } catch (e) {
      // request 已经弹过后端返回的具体原因
    } finally {
      this.setData({ submitting: false });
    }
  },
});
