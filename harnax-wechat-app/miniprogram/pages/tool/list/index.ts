// pages/tool/list/index.ts
import { getBuiltinTools, BuiltinTool, ToolEnvParam } from '../../../services/tool';

interface ToolRow extends BuiltinTool {
  localName: string;
  showTechName: boolean;
  envCount: number;
  needConfirmFlag: boolean;
  requiredFlag: boolean;
}

Page({
  data: {
    all: [] as ToolRow[],
    list: [] as ToolRow[],
    keyword: '',
    loading: true,
    // 详情弹层
    detailVisible: false,
    current: null as ToolRow | null,
    envParams: [] as ToolEnvParam[],
  },

  onLoad() {
    this.loadTools();
  },

  onPullDownRefresh() {
    this.loadTools().finally(() => wx.stopPullDownRefresh());
  },

  async loadTools() {
    this.setData({ loading: true });
    try {
      const data = (await getBuiltinTools()) || [];
      const rows = data.map((t) => this.decorate(t));
      this.setData({ all: rows, loading: false });
      this.applyFilter();
    } catch (e: any) {
      this.setData({ all: [], list: [], loading: false });
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' });
    }
  },

  decorate(t: BuiltinTool): ToolRow {
    const localName = (t.displayNameZh || '').trim() || (t.displayName || '').trim() || t.name;
    const envCount = (t.envParams && t.envParams.length) || (t.requiredEnvParamKeys && t.requiredEnvParamKeys.length) || 0;
    return {
      ...t,
      localName,
      showTechName: localName !== t.name,
      envCount,
      needConfirmFlag: t.needConfirm === 1,
      requiredFlag: t.isRequired === 1,
    };
  },

  onSearch(e: WechatMiniprogram.CustomEvent<{ value: string }>) {
    this.setData({ keyword: e.detail.value || '' });
    this.applyFilter();
  },

  applyFilter() {
    const kw = this.data.keyword.trim().toLowerCase();
    if (!kw) {
      this.setData({ list: this.data.all });
      return;
    }
    const list = this.data.all.filter((t) => {
      return (
        (t.name && t.name.toLowerCase().includes(kw)) ||
        (t.displayName && t.displayName.toLowerCase().includes(kw)) ||
        (t.displayNameZh && t.displayNameZh.toLowerCase().includes(kw)) ||
        (t.description && t.description.toLowerCase().includes(kw))
      );
    });
    this.setData({ list });
  },

  onTapItem(e: WechatMiniprogram.TouchEvent) {
    const item = e.currentTarget.dataset.item as ToolRow;
    this.setData({
      detailVisible: true,
      current: item,
      envParams: item.envParams || [],
    });
  },

  onCloseDetail() {
    this.setData({ detailVisible: false });
  },
});
