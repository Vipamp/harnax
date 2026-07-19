// pages/system/tenant-form/index.ts
import { getTenantById, createTenant } from '../../../services/tenant';
import { getUserPage } from '../../../services/user';

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    users: [] as API.UserItem[],
    userLabels: [] as string[],
    userIndex: -1,
    form: {
      name: '',
      code: '',
      description: '',
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '租户详情' : '新建租户' });
    await this.loadUsers();
    if (id) await this.loadDetail();
  },

  async loadUsers() {
    try {
      const res = await getUserPage({ pageNum: 1, pageSize: 100, status: 1 });
      const users = res.records || [];
      this.setData({ users, userLabels: users.map((u) => u.username) });
    } catch (e) {}
  },

  async loadDetail() {
    this.setData({ loading: true });
    try {
      const d = await getTenantById(this.data.id);
      this.setData({
        form: {
          name: d.name || '',
          code: d.code || '',
          description: d.description || '',
        },
      });
    } catch (e) {} finally {
      this.setData({ loading: false });
    }
  },

  onInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value });
  },

  onUserChange(e: any) {
    this.setData({ userIndex: Number(e.detail.value) });
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim()) {
      wx.showToast({ title: '请输入租户名称', icon: 'none' });
      return;
    }
    if (this.data.isEdit) {
      // 暂无更新租户接口
      wx.showToast({ title: '租户暂不支持编辑', icon: 'none' });
      return;
    }
    if (this.data.userIndex < 0) {
      wx.showToast({ title: '请选择管理员', icon: 'none' });
      return;
    }
    const admin = this.data.users[this.data.userIndex];
    this.setData({ submitting: true });
    try {
      await createTenant({
        name: form.name.trim(),
        code: form.code.trim() || undefined,
        description: form.description || undefined,
        adminUserId: admin.id,
      });
      wx.showToast({ title: '已创建', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
