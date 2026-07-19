// pages/system/user-form/index.ts
import { getUserById, createUser, updateUser } from '../../../services/user';

const ROLES = ['USER', 'ADMIN'];

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    roles: ROLES,
    roleIndex: 0,
    form: {
      username: '',
      password: '',
      nickname: '',
      email: '',
      phone: '',
      roles: ROLES[0],
      status: 1,
    },
  },

  async onLoad(query: Record<string, string>) {
    const id = query.id ? Number(query.id) : 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑用户' : '新建用户' });
    if (id) {
      this.setData({ loading: true });
      try {
        const d = await getUserById(id);
        const roles = (d.roles || 'USER').split(',')[0].trim();
        const roleIndex = Math.max(0, ROLES.indexOf(roles));
        this.setData({
          roleIndex,
          form: {
            username: d.username || '',
            password: '',
            nickname: d.nickname || '',
            email: d.email || '',
            phone: d.phone || '',
            roles,
            status: d.status,
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

  onRoleChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ roleIndex: idx, 'form.roles': ROLES[idx] });
  },

  onStatusChange(e: any) {
    this.setData({ 'form.status': e.detail.value ? 1 : 0 });
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.username.trim()) {
      wx.showToast({ title: '请输入用户名', icon: 'none' });
      return;
    }
    if (!this.data.isEdit && !form.password) {
      wx.showToast({ title: '请输入密码', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    try {
      if (this.data.isEdit) {
        const payload: API.UserUpdateRequest = {
          id: this.data.id,
          nickname: form.nickname || undefined,
          email: form.email || undefined,
          phone: form.phone || undefined,
          roles: form.roles,
          status: form.status,
        };
        await updateUser(this.data.id, payload);
      } else {
        const payload: API.UserCreateRequest = {
          username: form.username.trim(),
          password: form.password,
          nickname: form.nickname || undefined,
          email: form.email || undefined,
          phone: form.phone || undefined,
          roles: form.roles,
          status: form.status,
        };
        await createUser(payload);
      }
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
    } finally {
      this.setData({ submitting: false });
    }
  },
});
