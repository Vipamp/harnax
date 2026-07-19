// components/md/index.ts
// 轻量 Markdown 渲染组件：普通文本转 rich-text（内联样式），代码块单独渲染并支持复制。

interface MdBlock {
  type: 'rich' | 'code';
  html?: string;
  code?: string;
  lang?: string;
}

/** HTML 转义 */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/** 内联语法转换（入参需已 HTML 转义） */
function inline(text: string): string {
  let t = text;
  // 行内代码 `code`
  t = t.replace(
    /`([^`]+)`/g,
    (_m, c) =>
      `<code style="background:#F2F3F5;color:#E34D59;padding:2rpx 8rpx;border-radius:6rpx;font-family:monospace;font-size:26rpx;">${c}</code>`,
  );
  // 加粗 **text**
  t = t.replace(/\*\*([^*]+)\*\*/g, '<strong style="font-weight:600;">$1</strong>');
  // 斜体 *text*
  t = t.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em style="font-style:italic;">$2</em>');
  // 链接 [text](url)
  t = t.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a style="color:#4F46E5;">$1</a>');
  return t;
}

/** 结构行判断 */
function isStructural(t: string): boolean {
  return (
    /^(#{1,6})\s+/.test(t) ||
    /^[-*]\s+/.test(t) ||
    /^\d+\.\s+/.test(t) ||
    /^>\s?/.test(t) ||
    /^(-{3,}|\*{3,})$/.test(t)
  );
}

/** Markdown 文本段 -> HTML 字符串 */
function mdToHtml(md: string): string {
  const lines = md.split('\n');
  const out: string[] = [];
  let i = 0;
  let listType: 'ul' | 'ol' | null = null;
  const closeList = () => {
    if (listType) {
      out.push(`</${listType}>`);
      listType = null;
    }
  };

  while (i < lines.length) {
    const raw = lines[i];
    const trimmed = raw.trim();
    if (!trimmed) {
      closeList();
      i++;
      continue;
    }
    // 标题
    const h = /^(#{1,6})\s+(.*)$/.exec(trimmed);
    if (h) {
      closeList();
      const lvl = h[1].length;
      const sizes = [38, 34, 32, 30, 28, 28];
      out.push(
        `<div style="font-weight:600;font-size:${sizes[lvl - 1]}rpx;color:#1D2129;margin:18rpx 0 8rpx;">${inline(escapeHtml(h[2]))}</div>`,
      );
      i++;
      continue;
    }
    // 分割线
    if (/^(-{3,}|\*{3,})$/.test(trimmed)) {
      closeList();
      out.push('<div style="border-top:1rpx solid #E5E6EB;margin:18rpx 0;"></div>');
      i++;
      continue;
    }
    // 引用
    if (/^>\s?/.test(trimmed)) {
      closeList();
      const q = inline(escapeHtml(trimmed.replace(/^>\s?/, '')));
      out.push(
        `<div style="border-left:6rpx solid #C9CDD4;padding:4rpx 0 4rpx 18rpx;color:#86909C;margin:10rpx 0;">${q}</div>`,
      );
      i++;
      continue;
    }
    // 无序列表
    const ul = /^[-*]\s+(.*)$/.exec(trimmed);
    if (ul) {
      if (listType !== 'ul') {
        closeList();
        out.push('<ul style="margin:8rpx 0;padding-left:40rpx;">');
        listType = 'ul';
      }
      out.push(`<li style="margin:6rpx 0;line-height:1.7;">${inline(escapeHtml(ul[1]))}</li>`);
      i++;
      continue;
    }
    // 有序列表
    const ol = /^\d+\.\s+(.*)$/.exec(trimmed);
    if (ol) {
      if (listType !== 'ol') {
        closeList();
        out.push('<ol style="margin:8rpx 0;padding-left:40rpx;">');
        listType = 'ol';
      }
      out.push(`<li style="margin:6rpx 0;line-height:1.7;">${inline(escapeHtml(ol[1]))}</li>`);
      i++;
      continue;
    }
    // 段落：合并后续普通行
    closeList();
    const paras: string[] = [raw];
    i++;
    while (i < lines.length) {
      const nt = lines[i].trim();
      if (!nt || isStructural(nt)) break;
      paras.push(lines[i]);
      i++;
    }
    const joined = paras.map((p) => inline(escapeHtml(p))).join('<br/>');
    out.push(`<p style="margin:8rpx 0;line-height:1.7;">${joined}</p>`);
  }
  closeList();
  return out.join('');
}

/** 完整内容 -> 区块数组（分离围栏代码块） */
function parseMarkdown(content: string): MdBlock[] {
  const blocks: MdBlock[] = [];
  const re = /```([^\n`]*)\n?([\s\S]*?)```/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(content)) !== null) {
    if (m.index > last) {
      const text = content.slice(last, m.index);
      if (text.trim()) blocks.push({ type: 'rich', html: mdToHtml(text) });
    }
    blocks.push({ type: 'code', lang: (m[1] || '').trim(), code: m[2].replace(/\n$/, '') });
    last = re.lastIndex;
  }
  if (last < content.length) {
    const text = content.slice(last);
    if (text.trim()) blocks.push({ type: 'rich', html: mdToHtml(text) });
  }
  if (blocks.length === 0) blocks.push({ type: 'rich', html: mdToHtml(content) });
  return blocks;
}

Component({
  properties: {
    content: {
      type: String,
      value: '',
      observer(this: any, v: string) {
        this.setData({ blocks: parseMarkdown(v || '') });
      },
    },
  },
  data: {
    blocks: [] as MdBlock[],
  },
  methods: {
    onCopy(this: any, e: WechatMiniprogram.TouchEvent) {
      const code = e.currentTarget.dataset.code as string;
      wx.setClipboardData({
        data: code || '',
        success: () => wx.showToast({ title: '已复制', icon: 'none' }),
      });
    },
  },
});
