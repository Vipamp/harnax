// pages/session/workspace/index.ts
import {
  listWorkspaceFiles,
  readWorkspaceFile,
  getWorkspaceStatus,
  uploadWorkspaceFile,
  downloadWorkspaceFile,
  WorkspaceFile,
} from '../../../services/workspace';

interface FileRow extends WorkspaceFile {
  isDir: boolean;
  sizeText: string;
  fullPath: string;
}

const ROOT = '/workspace';

/** 人类可读文件大小 */
function formatSize(bytes?: number): string {
  if (bytes === undefined || bytes === null) return '';
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

Page({
  data: {
    sessionId: '',
    currentPath: ROOT,
    files: [] as FileRow[],
    loading: true,
    sandboxActive: false,
    sandboxText: '检测中…',
    canGoUp: false,
    // 文件内容弹层
    fileVisible: false,
    fileName: '',
    fileContent: '',
    currentFilePath: '',
    fileLoading: false,
  },

  onLoad(query: Record<string, string>) {
    const sessionId = query.sessionId || '';
    this.setData({ sessionId });
    wx.setNavigationBarTitle({ title: '工作区' });
    if (sessionId) {
      this.loadStatus();
      this.loadFiles(ROOT);
    } else {
      this.setData({ loading: false });
    }
  },

  /** 加载沙箱状态 */
  async loadStatus() {
    try {
      const map = await getWorkspaceStatus(this.data.sessionId);
      const info = map && map[this.data.sessionId];
      const active = !!(info && info.active);
      this.setData({
        sandboxActive: active,
        sandboxText: active ? '沙箱运行中' : '沙箱未启动',
      });
    } catch (e) {
      this.setData({ sandboxActive: false, sandboxText: '状态未知' });
    }
  },

  /** 加载指定路径文件列表 */
  async loadFiles(path: string) {
    this.setData({ loading: true });
    try {
      const list = (await listWorkspaceFiles(this.data.sessionId, path)) || [];
      const files = this.decorate(list, path);
      this.setData({
        files,
        currentPath: path,
        canGoUp: path !== ROOT && path !== '/',
        loading: false,
      });
    } catch (e: any) {
      this.setData({ files: [], loading: false });
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' });
    }
  },

  /** 目录在前，文件在后，并补充展示字段 */
  decorate(list: WorkspaceFile[], path: string): FileRow[] {
    const base = path.endsWith('/') ? path : path + '/';
    const sorted = [...list].sort((a, b) => {
      const ad = a.type === 'directory';
      const bd = b.type === 'directory';
      if (ad && !bd) return -1;
      if (!ad && bd) return 1;
      return (a.name || '').localeCompare(b.name || '');
    });
    return sorted.map((f) => ({
      ...f,
      isDir: f.type === 'directory',
      sizeText: f.type === 'directory' ? '' : formatSize(f.size),
      fullPath: f.path || base + f.name,
    }));
  },

  /** 点击条目 */
  onTapItem(e: WechatMiniprogram.TouchEvent) {
    const item = e.currentTarget.dataset.item as FileRow;
    if (item.isDir) {
      this.loadFiles(item.fullPath);
    } else {
      this.openFile(item);
    }
  },

  /** 返回上级目录 */
  onGoUp() {
    const p = this.data.currentPath;
    const idx = p.lastIndexOf('/');
    const parent = idx > 0 ? p.slice(0, idx) : ROOT;
    this.loadFiles(parent || ROOT);
  },

  /** 打开文件内容 */
  async openFile(item: FileRow) {
    this.setData({
      fileVisible: true,
      fileName: item.name,
      currentFilePath: item.fullPath,
      fileContent: '',
      fileLoading: true,
    });
    try {
      const r = await readWorkspaceFile(this.data.sessionId, item.fullPath);
      this.setData({ fileContent: (r && r.content) || '（空文件或二进制内容）', fileLoading: false });
    } catch (e: any) {
      this.setData({
        fileContent: '',
        fileLoading: false,
      });
      wx.showToast({ title: '无法预览，请尝试下载', icon: 'none' });
    }
  },

  onCloseFile() {
    this.setData({ fileVisible: false });
  },

  /** 下载并用系统程序打开当前文件 */
  onDownloadFile() {
    const path = this.data.currentFilePath;
    if (!path) return;
    wx.showLoading({ title: '下载中' });
    downloadWorkspaceFile(this.data.sessionId, path)
      .then((tempPath) => {
        wx.hideLoading();
        wx.openDocument({
          filePath: tempPath,
          showMenu: true,
          fail: () => wx.showToast({ title: '暂不支持打开该文件类型', icon: 'none' }),
        });
      })
      .catch((err) => {
        wx.hideLoading();
        wx.showToast({ title: (err && err.message) || '下载失败', icon: 'none' });
      });
  },

  /** 上传文件（从聊天会话选择文件） */
  onUpload() {
    if (!this.data.sandboxActive) {
      wx.showToast({ title: '沙箱未启动，无法上传', icon: 'none' });
      return;
    }
    wx.chooseMessageFile({
      count: 1,
      type: 'all',
      success: (res) => {
        const file = res.tempFiles[0];
        if (!file) return;
        wx.showLoading({ title: '上传中' });
        uploadWorkspaceFile(this.data.sessionId, file.path, this.data.currentPath)
          .then(() => {
            wx.hideLoading();
            wx.showToast({ title: '上传成功', icon: 'success' });
            this.loadFiles(this.data.currentPath);
          })
          .catch((err) => {
            wx.hideLoading();
            wx.showToast({ title: (err && err.message) || '上传失败', icon: 'none' });
          });
      },
    });
  },

  onRefresh() {
    this.loadStatus();
    this.loadFiles(this.data.currentPath);
  },

  onCopyContent() {
    if (!this.data.fileContent) return;
    wx.setClipboardData({
      data: this.data.fileContent,
      success: () => wx.showToast({ title: '已复制', icon: 'none' }),
    });
  },
});
