<template>
  <view class="markdown-body">
    <template v-for="(block, idx) in blocks" :key="idx">
      <!-- Code block -->
      <CodeBlock
        v-if="block.type === 'code'"
        :code="block.content"
        :language="block.language"
      />
      <!-- Heading -->
      <text
        v-else-if="block.type === 'heading'"
        :class="`md-h${block.level}`"
      >{{ block.content }}</text>
      <!-- Blockquote -->
      <view v-else-if="block.type === 'blockquote'" class="md-blockquote">
        <rich-text :nodes="block.html" />
      </view>
      <!-- Unordered list -->
      <view v-else-if="block.type === 'ul'" class="md-list">
        <view v-for="(item, i) in block.items" :key="i" class="md-list-item">
          <text class="md-bullet">•</text>
          <rich-text :nodes="item" class="md-list-text" />
        </view>
      </view>
      <!-- Ordered list -->
      <view v-else-if="block.type === 'ol'" class="md-list">
        <view v-for="(item, i) in block.items" :key="i" class="md-list-item">
          <text class="md-bullet">{{ i + 1 }}.</text>
          <rich-text :nodes="item" class="md-list-text" />
        </view>
      </view>
      <!-- Table -->
      <scroll-view v-else-if="block.type === 'table'" scroll-x class="md-table-wrap">
        <view class="md-table">
          <view v-for="(row, ri) in block.rows" :key="ri" class="md-table-row">
            <text
              v-for="(cell, ci) in row"
              :key="ci"
              :class="['md-table-cell', ri === 0 ? 'md-table-header' : '']"
            >{{ cell }}</text>
          </view>
        </view>
      </scroll-view>
      <!-- Horizontal rule -->
      <view v-else-if="block.type === 'hr'" class="md-hr" />
      <!-- Paragraph -->
      <rich-text v-else :nodes="block.html" />
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import CodeBlock from './CodeBlock.vue'

const props = defineProps<{
  content: string
}>()

interface Block {
  type: 'paragraph' | 'heading' | 'code' | 'blockquote' | 'ul' | 'ol' | 'table' | 'hr'
  content?: string
  html?: string
  language?: string
  level?: number
  items?: string[]
  rows?: string[][]
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function inlineFormat(text: string): string {
  let html = escapeHtml(text)
  // bold
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  // italic
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>')
  // inline code
  html = html.replace(/`([^`]+)`/g, '<code style="background:rgba(127,127,127,0.15);padding:1px 4px;border-radius:3px;font-size:0.9em">$1</code>')
  // links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" style="color:var(--chat-primary)">$1</a>')
  return html
}

function parseMarkdown(source: string): Block[] {
  const lines = source.split('\n')
  const blocks: Block[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    // code block
    const codeMatch = line.match(/^```(\w*)$/)
    if (codeMatch) {
      const lang = codeMatch[1] || ''
      const codeLines: string[] = []
      i++
      while (i < lines.length && !lines[i].match(/^```$/)) {
        codeLines.push(lines[i])
        i++
      }
      i++ // skip closing ```
      blocks.push({ type: 'code', content: codeLines.join('\n'), language: lang })
      continue
    }

    // heading
    const headingMatch = line.match(/^(#{1,6})\s+(.+)$/)
    if (headingMatch) {
      blocks.push({
        type: 'heading',
        level: headingMatch[1].length,
        content: headingMatch[2],
      })
      i++
      continue
    }

    // hr
    if (line.match(/^(-{3,}|_{3,}|\*{3,})$/)) {
      blocks.push({ type: 'hr' })
      i++
      continue
    }

    // blockquote
    if (line.startsWith('> ')) {
      const quoteLines: string[] = []
      while (i < lines.length && lines[i].startsWith('> ')) {
        quoteLines.push(lines[i].slice(2))
        i++
      }
      blocks.push({
        type: 'blockquote',
        html: inlineFormat(quoteLines.join(' ')),
      })
      continue
    }

    // unordered list
    if (line.match(/^[-*+]\s/)) {
      const items: string[] = []
      while (i < lines.length && lines[i].match(/^[-*+]\s/)) {
        items.push(inlineFormat(lines[i].replace(/^[-*+]\s/, '')))
        i++
      }
      blocks.push({ type: 'ul', items })
      continue
    }

    // ordered list
    if (line.match(/^\d+\.\s/)) {
      const items: string[] = []
      while (i < lines.length && lines[i].match(/^\d+\.\s/)) {
        items.push(inlineFormat(lines[i].replace(/^\d+\.\s/, '')))
        i++
      }
      blocks.push({ type: 'ol', items })
      continue
    }

    // table (GFM)
    if (line.includes('|') && i + 1 < lines.length && lines[i + 1].match(/^\|?[\s-:|]+\|/)) {
      const rows: string[][] = []
      // header
      rows.push(line.split('|').map((c) => c.trim()).filter(Boolean))
      i++ // skip separator
      i++
      while (i < lines.length && lines[i].includes('|')) {
        rows.push(lines[i].split('|').map((c) => c.trim()).filter(Boolean))
        i++
      }
      blocks.push({ type: 'table', rows })
      continue
    }

    // empty line
    if (line.trim() === '') {
      i++
      continue
    }

    // paragraph
    const paraLines: string[] = []
    while (i < lines.length && lines[i].trim() !== '' && !lines[i].match(/^(#{1,6}\s|```|[-*+]\s|\d+\.\s|>|---)/)) {
      paraLines.push(lines[i])
      i++
    }
    if (paraLines.length > 0) {
      blocks.push({
        type: 'paragraph',
        html: inlineFormat(paraLines.join(' ')),
      })
    }
  }

  return blocks
}

const blocks = computed(() => parseMarkdown(props.content || ''))
</script>

<style lang="scss" scoped>
.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--chat-text-primary, #1a1a2e);
}

.md-h1 { font-size: 22px; font-weight: 700; margin: 16px 0 8px; }
.md-h2 { font-size: 19px; font-weight: 700; margin: 14px 0 6px; }
.md-h3 { font-size: 16px; font-weight: 600; margin: 12px 0 4px; }
.md-h4 { font-size: 15px; font-weight: 600; margin: 10px 0 4px; }
.md-h5 { font-size: 14px; font-weight: 600; margin: 8px 0 2px; }
.md-h6 { font-size: 13px; font-weight: 600; margin: 8px 0 2px; color: var(--chat-text-secondary, #666); }

.md-blockquote {
  border-left: 3px solid var(--chat-primary, #4f6ef7);
  padding-left: 12px;
  margin: 8px 0;
  color: var(--chat-text-secondary, #666);
}

.md-list {
  margin: 6px 0;
  padding-left: 8px;
}

.md-list-item {
  display: flex;
  align-items: flex-start;
  margin: 3px 0;
}

.md-bullet {
  margin-right: 6px;
  color: var(--chat-text-secondary, #666);
  min-width: 16px;
}

.md-list-text {
  flex: 1;
}

.md-table-wrap {
  margin: 8px 0;
}

.md-table {
  display: flex;
  flex-direction: column;
}

.md-table-row {
  display: flex;
  border-bottom: 1px solid var(--chat-border, #e5e5e5);
}

.md-table-cell {
  flex: 1;
  padding: 6px 10px;
  font-size: 13px;
  min-width: 80px;
}

.md-table-header {
  font-weight: 600;
  background: var(--chat-bg-elevated, rgba(0,0,0,0.03));
}

.md-hr {
  height: 1px;
  background: var(--chat-border, #e5e5e5);
  margin: 16px 0;
}
</style>
