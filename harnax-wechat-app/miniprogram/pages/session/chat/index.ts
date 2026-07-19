// pages/session/chat/index.ts
import {
  getSessionMessages,
  getSessionConfig,
  sendSessionCommand,
  streamChat,
  confirmTools,
  SSEEvent,
  PendingCallTool,
} from '../../../services/chat';

interface Segment {
  type: 'text' | 'thinking' | 'tool_call';
  content: string;
  toolName?: string;
  toolId?: string;
  toolResult?: string;
  confirmStatus?: 'pending' | 'confirmed' | 'rejected';
  collapsed?: boolean;
}

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  segments: Segment[];
  images?: string[];
  timestamp: number;
  timeText?: string;
}

/** 时间戳 -> HH:mm */
function fmtTime(ts?: number): string {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return '';
  const pad = (n: number) => (n < 10 ? '0' + n : '' + n);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// 流式过程中的可变状态（不放入 data，避免频繁 diff）
interface StreamState {
  currentSegs: Segment[];
  accText: string;
  accThinking: string;
  activeTextIdx: number;
  activeThinkIdx: number;
  currentEventType: 'text' | 'thinking' | null;
  toolCallMap: Map<string, number>;
  assistantId: string;
}

/** 权限模式定义（对齐 webui） */
const PERMISSION_MODES = [
  { key: 'DEFAULT', label: '默认', desc: '危险工具需确认' },
  { key: 'BYPASS', label: '自动执行', desc: '免确认' },
  { key: 'ACCEPT_EDITS', label: '接受编辑', desc: '自动批准文件编辑' },
  { key: 'EXPLORE', label: '只读', desc: '只读模式' },
  { key: 'DONT_ASK', label: '不询问', desc: '自动拒绝危险工具' },
];
function permissionLabel(mode: string): string {
  const m = PERMISSION_MODES.find((x) => x.key === mode);
  return m ? m.label : '默认';
}

Page({
  data: {
    sessionId: '',
    title: '对话',
    loading: true, // 首屏加载历史
    loadError: false, // 加载失败标记
    loadErrorMsg: '', // 加载失败详情
    streaming: false, // 是否正在接收流
    messages: [] as ChatMessage[],
    inputValue: '',
    scrollToId: '',
    // 待发送图片（base64 data url）
    pendingImages: [] as string[],
    // 待确认工具
    pendingConfirm: null as { tools: PendingCallTool[] } | null,
    // 会话参数开关（对齐 webui）
    enableThink: false,
    enableSearch: false,
    enablePlan: false,
    permissionMode: 'DEFAULT',
    permissionText: '默认',
    modelSupportReasoning: true,
    modelSupportInternet: true,
    modelSupportVision: true,
  },

  _stream: null as StreamState | null,
  _task: null as any,
  _flushTimer: null as any,

  onLoad(query: Record<string, string>) {
    const sessionId = query.sessionId || '';
    const title = query.title ? decodeURIComponent(query.title) : '对话';
    this.setData({ sessionId, title });
    wx.setNavigationBarTitle({ title });
    if (sessionId) {
      this.loadHistory();
      this.loadConfig();
    } else {
      this.setData({ loading: false });
    }
  },

  onUnload() {
    this.abortStream();
  },

  /** 加载历史消息并转换为 segment 消息模型 */
  async loadHistory() {
    this.setData({ loading: true, loadError: false });
    try {
      const logs = (await getSessionMessages(this.data.sessionId)) as any[];
      const messages = this.convertHistory(logs || []);
      this.setData({ loading: false, messages });
      this.scrollToBottom();
    } catch (e: any) {
      let msg = (e && e.message) || '加载消息失败';
      // "Full authentication is required" 说明请求打到了 Admin 而非 Router
      if (msg.indexOf('Full authentication') >= 0) {
        msg = 'Router 服务未可达，请检查服务器地址（nginx 端口）或在高级设置中填写 Router URL';
      }
      console.error('[chat] loadHistory failed:', msg);
      this.setData({ loading: false, loadError: true, loadErrorMsg: msg });
      wx.showToast({ title: msg, icon: 'none', duration: 3000 });
    }
  },

  /** 重试加载历史 */
  onRetryLoad() {
    if (this.data.sessionId) {
      this.loadHistory();
    }
  },

  /** 加载会话参数配置（深度思考/联网/计划/权限模式 + 模型能力） */
  async loadConfig() {
    try {
      const config = (await getSessionConfig(this.data.sessionId)) as any;
      if (!config) return;
      const permissionMode = config.permissionMode || 'DEFAULT';
      this.setData({
        enableThink: !!config.enableThink,
        enableSearch: !!config.enableSearch,
        enablePlan: !!config.enablePlan,
        permissionMode,
        permissionText: permissionLabel(permissionMode),
        modelSupportReasoning: config.modelSupportReasoning !== 0,
        modelSupportInternet: config.modelSupportInternet !== 0,
        modelSupportVision: config.modelSupportVision !== 0,
      });
    } catch (e) {
      // 配置加载失败不阻塞对话，静默处理
      console.warn('[chat] loadConfig failed', e);
    }
  },

  /** 切换深度思考 */
  onToggleThink() {
    if (!this.data.modelSupportReasoning) {
      wx.showToast({ title: '当前模型不支持深度思考', icon: 'none' });
      return;
    }
    const next = !this.data.enableThink;
    this.setData({ enableThink: next });
    this.sendSwitchCommand(next ? 'ENABLE' : 'DISABLE', 'thinking', () => {
      this.setData({ enableThink: !next });
    });
  },

  /** 切换联网搜索 */
  onToggleSearch() {
    if (!this.data.modelSupportInternet) {
      wx.showToast({ title: '当前模型不支持联网搜索', icon: 'none' });
      return;
    }
    const next = !this.data.enableSearch;
    this.setData({ enableSearch: next });
    this.sendSwitchCommand(next ? 'ENABLE' : 'DISABLE', 'search', () => {
      this.setData({ enableSearch: !next });
    });
  },

  /** 切换计划模式 */
  onTogglePlan() {
    const next = !this.data.enablePlan;
    this.setData({ enablePlan: next });
    this.sendSwitchCommand(next ? 'ENABLE' : 'DISABLE', 'plan', () => {
      this.setData({ enablePlan: !next });
    });
  },

  /** 选择权限模式 */
  onChoosePermission() {
    const prev = this.data.permissionMode;
    wx.showActionSheet({
      itemList: PERMISSION_MODES.map((m) => `${m.label}（${m.desc}）`),
      success: (res) => {
        const target = PERMISSION_MODES[res.tapIndex];
        if (!target || target.key === prev) return;
        this.setData({ permissionMode: target.key, permissionText: target.label });
        this.sendSwitchCommand('PERMISSION', target.key, () => {
          this.setData({ permissionMode: prev, permissionText: permissionLabel(prev) });
        });
      },
    });
  },

  /** 发送配置切换命令，失败时回滚 */
  async sendSwitchCommand(command: string, args: string, revert: () => void) {
    if (!this.data.sessionId) return;
    try {
      const res = await sendSessionCommand(this.data.sessionId, command, args);
      const data: any = res && res.data;
      if (data && data.success === false) {
        if (data.message) wx.showToast({ title: data.message, icon: 'none' });
        revert();
      }
    } catch (e) {
      revert();
      wx.showToast({ title: '设置失败', icon: 'none' });
    }
  },

  /** 历史日志 -> 消息模型（对齐 webui 合并逻辑） */
  convertHistory(logs: any[]): ChatMessage[] {
    const result: ChatMessage[] = [];
    let assistant: ChatMessage | null = null;
    let lastIdx = -1;

    for (let i = 0; i < logs.length; i++) {
      if (i <= lastIdx) continue;
      const log = logs[i];
      const id = `${(log.role || '').toLowerCase()}-${log.timestamp || Date.now()}-${i}`;

      if (log.role === 'USER') {
        assistant = null;
        result.push({
          id,
          role: 'user',
          segments: [{ type: 'text', content: log.message || '' }],
          timestamp: log.timestamp || Date.now(),
          timeText: fmtTime(log.timestamp || Date.now()),
        });
        lastIdx = i;
      } else if (log.role === 'ASSISTANT') {
        if (!assistant) {
          assistant = {
            id,
            role: 'assistant',
            segments: [],
            timestamp: log.timestamp || Date.now(),
            timeText: fmtTime(log.timestamp || Date.now()),
          };
          result.push(assistant);
        }
        const segs = assistant.segments;
        if (log.thinking) {
          const last = segs[segs.length - 1];
          if (last && last.type === 'thinking') {
            last.content = (last.content || '') + '\n\n' + log.thinking;
          } else {
            segs.push({ type: 'thinking', content: log.thinking, collapsed: true });
          }
        }
        if (log.toolUseLog && log.toolUseLog.length) {
          for (const tool of log.toolUseLog) {
            if (!tool.name) continue;
            const seg: Segment = {
              type: 'tool_call',
              content: JSON.stringify(tool.input || {}, null, 2),
              toolName: tool.name,
              collapsed: true,
            };
            if (i + 1 < logs.length && logs[i + 1].role === 'TOOL') {
              const tr = logs[i + 1];
              if (!tr.name || tr.name === tool.name) {
                seg.toolResult = tr.result || '';
                lastIdx = i + 1;
              }
            }
            segs.push(seg);
          }
        }
        if (log.text) {
          segs.push({ type: 'text', content: log.text });
        }
        lastIdx = i;
      } else if (log.role === 'TOOL') {
        lastIdx = i;
      }
    }
    return result;
  },

  onInput(e: any) {
    this.setData({ inputValue: e.detail.value });
  },

  /** 发送消息 */
  onSend() {
    const text = this.data.inputValue.trim();
    const images = this.data.pendingImages.slice();
    if (!text && images.length === 0) return;
    if (this.data.streaming) return;
    if (!this.data.sessionId) {
      wx.showToast({ title: '会话不存在', icon: 'none' });
      return;
    }

    const now = Date.now();
    const userMsg: ChatMessage = {
      id: `user-${now}`,
      role: 'user',
      segments: [{ type: 'text', content: text }],
      images,
      timestamp: now,
      timeText: fmtTime(now),
    };
    const assistantId = `assistant-${now}`;
    const assistantMsg: ChatMessage = {
      id: assistantId,
      role: 'assistant',
      segments: [],
      timestamp: now,
      timeText: fmtTime(now),
    };

    const messages = [...this.data.messages, userMsg, assistantMsg];
    this.setData({ messages, inputValue: '', pendingImages: [], streaming: true });
    this.scrollToBottom();

    this.resetStreamState(assistantId);
    this._task = streamChat(
      this.data.sessionId,
      text,
      {
        onEvent: (ev) => this.handleEvent(ev),
        onDone: () => this.onStreamDone(),
        onError: (err) => this.onStreamError(err),
      },
      images,
    );
  },

  /** 选择图片（转 base64） */
  onChooseImage() {
    if (this.data.streaming) return;
    const remaining = 9 - this.data.pendingImages.length;
    if (remaining <= 0) {
      wx.showToast({ title: '最多选择 9 张', icon: 'none' });
      return;
    }
    wx.chooseMedia({
      count: remaining,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const fsm = wx.getFileSystemManager();
        const tasks = res.tempFiles.map(
          (f) =>
            new Promise<string>((resolve) => {
              fsm.readFile({
                filePath: f.tempFilePath,
                encoding: 'base64',
                success: (r) => {
                  const ext = (f.tempFilePath.split('.').pop() || 'jpg').toLowerCase();
                  const mime =
                    ext === 'png' ? 'image/png' : ext === 'webp' ? 'image/webp' : 'image/jpeg';
                  resolve(`data:${mime};base64,${r.data}`);
                },
                fail: () => resolve(''),
              });
            }),
        );
        Promise.all(tasks).then((urls) => {
          const valid = urls.filter(Boolean);
          this.setData({ pendingImages: this.data.pendingImages.concat(valid) });
        });
      },
    });
  },

  /** 移除待发送图片 */
  onRemoveImage(e: WechatMiniprogram.TouchEvent) {
    const index = Number(e.currentTarget.dataset.index);
    const pendingImages = this.data.pendingImages.slice();
    pendingImages.splice(index, 1);
    this.setData({ pendingImages });
  },

  /** 预览图片 */
  onPreviewImage(e: WechatMiniprogram.TouchEvent) {
    const src = e.currentTarget.dataset.src as string;
    if (!src) return;
    wx.previewImage({ current: src, urls: [src] });
  },

  /** 打开工作区文件 */
  onOpenWorkspace() {
    wx.navigateTo({
      url: `/pages/session/workspace/index?sessionId=${this.data.sessionId}&title=${encodeURIComponent(
        this.data.title,
      )}`,
    });
  },

  /** 打开会话详情 */
  onOpenDetail() {
    wx.navigateTo({
      url: `/pages/session/detail/index?sessionId=${this.data.sessionId}`,
    });
  },

  /** 初始化流式可变状态 */
  resetStreamState(assistantId: string) {
    this._stream = {
      currentSegs: [],
      accText: '',
      accThinking: '',
      activeTextIdx: -1,
      activeThinkIdx: -1,
      currentEventType: null,
      toolCallMap: new Map<string, number>(),
      assistantId,
    };
  },

  /** 处理单个 SSE 事件 */
  handleEvent(ev: SSEEvent) {
    const s = this._stream;
    if (!s) return;
    const type = ev.eventType;

    if (type === 'TextEvent') {
      if (ev.isLast === true) {
        s.accText = '';
        s.activeTextIdx = -1;
        s.currentEventType = null;
        return;
      }
      if (s.currentEventType !== 'text') {
        const last = s.currentSegs[s.currentSegs.length - 1];
        if (last && last.type === 'text') {
          s.activeTextIdx = s.currentSegs.length - 1;
          s.accText = last.content || '';
        } else {
          s.accText = '';
          s.activeTextIdx = -1;
        }
      }
      s.currentEventType = 'text';
      s.accText += ev.message || '';
      if (s.activeTextIdx < 0 || s.activeTextIdx >= s.currentSegs.length) {
        s.currentSegs.push({ type: 'text', content: s.accText });
        s.activeTextIdx = s.currentSegs.length - 1;
      } else {
        s.currentSegs[s.activeTextIdx].content = s.accText;
      }
      this.scheduleFlush();
    } else if (type === 'ThinkingEvent') {
      if (ev.isLast === true) {
        s.accThinking = '';
        s.activeThinkIdx = -1;
        s.currentEventType = null;
        return;
      }
      if (s.currentEventType !== 'thinking') {
        const last = s.currentSegs[s.currentSegs.length - 1];
        if (last && last.type === 'thinking') {
          s.activeThinkIdx = s.currentSegs.length - 1;
          s.accThinking = last.content || '';
        } else {
          s.accThinking = '';
          s.activeThinkIdx = -1;
        }
      }
      s.currentEventType = 'thinking';
      s.accThinking += ev.message || '';
      if (s.activeThinkIdx < 0 || s.activeThinkIdx >= s.currentSegs.length) {
        s.currentSegs.push({ type: 'thinking', content: s.accThinking, collapsed: false });
        s.activeThinkIdx = s.currentSegs.length - 1;
      } else {
        s.currentSegs[s.activeThinkIdx].content = s.accThinking;
      }
      this.scheduleFlush();
    } else if (type === 'CallToolEvent') {
      const toolName = ev.toolName;
      if (!toolName) return;
      const toolId = ev.toolId || `tool-${Date.now()}`;
      this.breakContinuity(s);
      // 去重：优先 toolId 精确匹配，其次按工具名/空闲卡片回退匹配，
      // 原地更新已有卡片（可能由 ToolConfirmEvent 先行创建），避免重复新建 tag
      const existIdx = this.findToolSegmentIdx(s, toolId, toolName);
      if (existIdx >= 0) {
        const existSeg = s.currentSegs[existIdx];
        if (existSeg && existSeg.type === 'tool_call') {
          existSeg.content = JSON.stringify(ev.arguments || {}, null, 2);
          existSeg.toolName = toolName;
          existSeg.toolId = toolId;
          // 从 pending/confirmed 进入实际调用态，清除确认标记
          if (existSeg.confirmStatus === 'pending' || existSeg.confirmStatus === 'confirmed') {
            existSeg.confirmStatus = undefined;
          }
          s.toolCallMap.set(toolId, existIdx);
        }
      } else {
        s.currentSegs.push({
          type: 'tool_call',
          content: JSON.stringify(ev.arguments || {}, null, 2),
          toolName,
          toolId,
          collapsed: true,
        });
        s.toolCallMap.set(toolId, s.currentSegs.length - 1);
      }
      this.scheduleFlush();
    } else if (type === 'ToolResultEvent') {
      const toolId = ev.toolId || '';
      const toolName = ev.toolName || '';
      const resultContent = ev.message || '';
      this.breakContinuity(s);
      // 跨结构匹配：结果事件的 toolId 可能与调用/确认事件不一致，回退按工具名匹配
      const idx = this.findToolSegmentIdx(s, toolId, toolName);
      if (idx >= 0) {
        const seg = s.currentSegs[idx];
        if (seg && seg.type === 'tool_call') {
          seg.toolResult = resultContent;
          if (ev.success === false) seg.confirmStatus = 'rejected';
        }
      }
      this.scheduleFlush();
    } else if (type === 'EndEvent') {
      this.breakContinuity(s);
      this.scheduleFlush();
    } else if (type === 'ToolConfirmEvent') {
      this.breakContinuity(s);
      const pending = (ev.pendingCallTools || []).filter((t) => t.toolName);
      for (const tool of pending) {
        // 去重：复用 CallToolEvent 可能已创建的卡片，仅更新为待确认态，避免重复新建 tag
        const existIdx = this.findToolSegmentIdx(s, tool.toolId, tool.toolName);
        if (existIdx >= 0) {
          const seg = s.currentSegs[existIdx];
          if (seg && seg.type === 'tool_call') {
            seg.confirmStatus = 'pending';
            seg.content = JSON.stringify(tool.arguments || {}, null, 2);
            seg.toolName = tool.toolName;
            seg.toolId = tool.toolId;
            s.toolCallMap.set(tool.toolId, existIdx);
          }
        } else {
          s.currentSegs.push({
            type: 'tool_call',
            content: JSON.stringify(tool.arguments || {}, null, 2),
            toolName: tool.toolName,
            toolId: tool.toolId,
            confirmStatus: 'pending',
            collapsed: true,
          });
          s.toolCallMap.set(tool.toolId, s.currentSegs.length - 1);
        }
      }
      this.flushNow();
      // 展示确认条，等待用户操作（原始流将结束）
      this.setData({ pendingConfirm: { tools: pending } });
    }
  },

  /**
   * 匹配已有的 tool_call 段索引（对齐 webui 逻辑）：
   * 1) 主匹配：toolId 在 toolCallMap 中精确命中
   * 2) 回退 1：同名且尚无结果的最后一个 tool_call（并回填 toolId 映射）
   * 3) 回退 2：任意未确认且无结果的 tool_call（应对调用/确认事件工具名格式不一致）
   * 返回 -1 表示未找到（应新建）。
   */
  findToolSegmentIdx(s: StreamState, toolId: string, toolName: string): number {
    if (toolId && s.toolCallMap.has(toolId)) {
      return s.toolCallMap.get(toolId)!;
    }
    for (let i = s.currentSegs.length - 1; i >= 0; i--) {
      const seg = s.currentSegs[i];
      if (seg.type === 'tool_call' && seg.toolName === toolName && seg.toolResult === undefined) {
        if (toolId) s.toolCallMap.set(toolId, i);
        return i;
      }
    }
    for (let i = s.currentSegs.length - 1; i >= 0; i--) {
      const seg = s.currentSegs[i];
      if (seg.type === 'tool_call' && !seg.confirmStatus && seg.toolResult === undefined) {
        if (toolId) s.toolCallMap.set(toolId, i);
        return i;
      }
    }
    return -1;
  },

  /** 中断 text/thinking 连续性 */
  breakContinuity(s: StreamState) {
    s.currentEventType = null;
    s.accText = '';
    s.accThinking = '';
    s.activeTextIdx = -1;
    s.activeThinkIdx = -1;
  },

  /** 节流刷新 UI */
  scheduleFlush() {
    if (this._flushTimer) return;
    this._flushTimer = setTimeout(() => {
      this._flushTimer = null;
      this.flushNow();
    }, 80);
  },

  /** 立即把当前流式 segments 写入对应 assistant 消息 */
  flushNow() {
    const s = this._stream;
    if (!s) return;
    const messages = this.data.messages.slice();
    const idx = messages.findIndex((m) => m.id === s.assistantId);
    if (idx >= 0) {
      messages[idx] = { ...messages[idx], segments: s.currentSegs.slice() };
      this.setData({ messages });
      this.scrollToBottom();
    }
  },

  onStreamDone() {
    if (this._flushTimer) {
      clearTimeout(this._flushTimer);
      this._flushTimer = null;
    }
    this.flushNow();
    this.setData({ streaming: false });
    this._task = null;
    // If waiting for tool confirmation, preserve _stream so onConfirmTools can access segments
    if (!this.data.pendingConfirm) {
      this._stream = null;
    }
  },

  onStreamError(err: any) {
    if (this._flushTimer) {
      clearTimeout(this._flushTimer);
      this._flushTimer = null;
    }
    this._task = null;
    // 移除流失败时残留的空 assistant 消息
    const s = this._stream;
    if (s) {
      const messages = this.data.messages.filter((m) => m.id !== s.assistantId || m.segments.length > 0);
      this.setData({ messages });
    }
    this._stream = null;
    this.setData({ streaming: false });
    if (err && err.errMsg && err.errMsg.indexOf('abort') >= 0) return;
    wx.showToast({ title: '连接失败', icon: 'none' });
  },

  /** 用户确认 / 拒绝工具 */
  onConfirmTools(e: WechatMiniprogram.TouchEvent) {
    const pending = this.data.pendingConfirm;
    if (!pending) return;
    const isConfirmed = e.currentTarget.dataset.ok === true || e.currentTarget.dataset.ok === 'true';

    // Update pending segments status
    const s = this._stream;
    if (s) {
      for (const seg of s.currentSegs) {
        if (seg.type === 'tool_call' && seg.confirmStatus === 'pending') {
          seg.confirmStatus = isConfirmed ? 'confirmed' : 'rejected';
        }
      }
    }
    const toolInfoList = pending.tools.map((t) => ({
      toolId: t.toolId,
      toolName: t.toolName,
    }));

    this.setData({ pendingConfirm: null, streaming: true });

    // Re-init stream state for the continued response (same assistant message)
    const assistantId = s?.assistantId || this.data.messages.filter(m => m.role === 'assistant').pop()?.id || '';
    this.resetStreamState(assistantId);
    if (s) {
      // Carry over existing segments so new events append correctly
      this._stream!.currentSegs = s.currentSegs.slice();
      this._stream!.toolCallMap = new Map(s.toolCallMap);
    }
    this.flushNow();

    this._task = confirmTools(this.data.sessionId, isConfirmed, toolInfoList, {
      onEvent: (ev) => this.handleEvent(ev),
      onDone: () => this.onStreamDone(),
      onError: (err) => this.onStreamError(err),
    });
  },

  /** 切换 thinking / tool_call 折叠 */
  onToggleSeg(e: WechatMiniprogram.TouchEvent) {
    const { mid, sid } = e.currentTarget.dataset as { mid: string; sid: number };
    const messages = this.data.messages.slice();
    const mi = messages.findIndex((m) => m.id === mid);
    if (mi < 0) return;
    const segs = messages[mi].segments.slice();
    segs[sid] = { ...segs[sid], collapsed: !segs[sid].collapsed };
    messages[mi] = { ...messages[mi], segments: segs };
    this.setData({ messages });
  },

  /** 中断流 */
  onStop() {
    this.abortStream();
    // 移除中断时的空 assistant 消息
    const s = this._stream;
    if (s) {
      const messages = this.data.messages.filter((m) => m.id !== s.assistantId || m.segments.length > 0);
      this.setData({ messages });
    }
    this._stream = null;
    this.setData({ streaming: false, pendingConfirm: null });
  },

  abortStream() {
    if (this._task) {
      try {
        this._task.abort();
      } catch (e) {}
      this._task = null;
    }
    if (this._flushTimer) {
      clearTimeout(this._flushTimer);
      this._flushTimer = null;
    }
  },

  scrollToBottom() {
    const messages = this.data.messages;
    if (messages.length) {
      this.setData({ scrollToId: `msg-${messages[messages.length - 1].id}` });
    }
  },
});
