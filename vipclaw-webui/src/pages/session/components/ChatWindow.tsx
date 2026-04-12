import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Input, Button, message } from 'antd';
import {
  SendOutlined,
  BulbOutlined,
  SearchOutlined,
  DownOutlined,
  RightOutlined,
  ToolOutlined,
  CheckCircleOutlined,
  RobotOutlined,
  UserOutlined,
  CopyOutlined,
  CheckOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import styles from './ChatWindow.less';

const { TextArea } = Input;

/* ─── 类型 ─── */
interface MessageSegment {
  type: 'text' | 'thinking' | 'tool_call' | 'tool_result';
  content: string;
  toolName?: string;
}

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  segments: MessageSegment[];
  timestamp: number;
}

interface ChatWindowProps {
  sessionId: string;
}

/* ─── 快捷建议 ─── */
const SUGGESTIONS = [
  '帮我写一段 Java 排序算法',
  '解释一下 Spring Boot 的自动装配原理',
  '用 Python 实现一个简单的 Web 服务器',
  '如何优化 SQL 慢查询？',
];

/* ─── 代码块（带复制 + 语言标签） ─── */
const CodeBlock: React.FC<{ className?: string; children?: React.ReactNode }> = ({
  className,
  children,
}) => {
  const [copied, setCopied] = useState(false);
  const match = /language-(\w+)/.exec(className || '');
  const code = String(children).replace(/\n$/, '');

  if (!match) {
    return <code className={className}>{children}</code>;
  }

  const handleCopy = async () => {
    await navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  };

  return (
    <div className={styles.codeBlockWrapper}>
      <div className={styles.codeBlockHeader}>
        <span className={styles.codeBlockLang}>{match[1]}</span>
        <button
          className={`${styles.codeBlockCopy} ${copied ? styles.codeBlockCopied : ''}`}
          onClick={handleCopy}
        >
          {copied ? <CheckOutlined /> : <CopyOutlined />}
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <SyntaxHighlighter
        style={oneLight}
        language={match[1]}
        PreTag="div"
        customStyle={{ margin: 0, padding: '14px 16px', fontSize: 13, background: '#fafafa' }}
      >
        {code}
      </SyntaxHighlighter>
    </div>
  );
};

/* ─── 思考折叠 ─── */
const ThinkingBlock: React.FC<{ content: string }> = ({ content }) => {
  const [expanded, setExpanded] = useState(true);
  return (
    <div className={styles.thinkingBlock}>
      <div className={styles.thinkingToggle} onClick={() => setExpanded(!expanded)}>
        <BulbOutlined />
        <span>思考过程</span>
        {expanded ? (
          <DownOutlined style={{ fontSize: 10 }} />
        ) : (
          <RightOutlined style={{ fontSize: 10 }} />
        )}
      </div>
      {expanded && <div className={styles.thinkingContent}>{content}</div>}
    </div>
  );
};

/* ─── 工具卡片 ─── */
const ToolCallCard: React.FC<{ toolName: string; content: string }> = ({ toolName, content }) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className={styles.toolCard}>
      <div className={styles.toolCardHeader} onClick={() => setExpanded(!expanded)}>
        <ToolOutlined /> 调用工具: {toolName}
        {expanded ? <DownOutlined style={{ fontSize: 10, marginLeft: 4 }} /> : <RightOutlined style={{ fontSize: 10, marginLeft: 4 }} />}
      </div>
      {expanded && content && <div className={styles.toolCardBody}>{content}</div>}
    </div>
  );
};

const ToolResultCard: React.FC<{ content: string }> = ({ content }) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className={styles.toolResultCard}>
      <div className={styles.toolResultHeader} onClick={() => setExpanded(!expanded)}>
        <CheckCircleOutlined /> 工具返回
        {expanded ? <DownOutlined style={{ fontSize: 10, marginLeft: 4 }} /> : <RightOutlined style={{ fontSize: 10, marginLeft: 4 }} />}
      </div>
      {expanded && <div className={styles.toolResultBody}>{content}</div>}
    </div>
  );
};

/* ─── 加载点 ─── */
const LoadingDots: React.FC = () => (
  <div className={styles.loadingDots}>
    <span className={styles.dot} />
    <span className={styles.dot} />
    <span className={styles.dot} />
  </div>
);

/* ═══════════════ 主组件 ═══════════════ */
const ChatWindow: React.FC<ChatWindowProps> = ({ sessionId }) => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [enableThink, setEnableThink] = useState(false);
  const [enableSearch, setEnableSearch] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  // 组件卸载时中断请求
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  /* ─── 发送消息（fetch + ReadableStream，走代理） ─── */
  const doSend = async (text: string) => {
    if (!text.trim() || loading) return;

    const userMessage: ChatMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      segments: [{ type: 'text', content: text.trim() }],
      timestamp: Date.now(),
    };
    const assistantMessageId = `assistant-${Date.now()}`;
    const assistantMessage: ChatMessage = {
      id: assistantMessageId,
      role: 'assistant',
      segments: [],
      timestamp: Date.now(),
    };

    // 可变状态
    let currentSegs: MessageSegment[] = [];
    let accText = '';
    let accThinking = '';
    let activeThinkIdx = -1; // 当前活跃的 thinking segment 索引
    let currentMsgs = [...messages, userMessage, assistantMessage];

    setMessages(currentMsgs);
    setInputValue('');
    setLoading(true);

    const flushUI = () => {
      const idx = currentMsgs.findIndex((m) => m.id === assistantMessageId);
      if (idx >= 0) {
        currentMsgs = [...currentMsgs];
        currentMsgs[idx] = { ...currentMsgs[idx], segments: [...currentSegs] };
        setMessages(currentMsgs);
      }
    };

    const abortController = new AbortController();
    abortRef.current = abortController;

    try {
      const params = new URLSearchParams({
        sessionId,
        message: text.trim(),
        enableThink: String(enableThink),
        enableSearch: String(enableSearch),
      });

      const response = await fetch(`/ai/chat?${params.toString()}`, {
        method: 'GET',
        headers: { Accept: 'text/event-stream' },
        signal: abortController.signal,
      });

      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const reader = response.body?.getReader();
      if (!reader) throw new Error('无法读取响应流');

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        let changed = false;
        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          const jsonStr = line.substring(5).trim();
          if (!jsonStr) continue;
          try {
            const data = JSON.parse(jsonStr);

            if (data.eventType === 'TextEvent') {
              if (data.last === true) {
                // 当前文本段结束，重置累积器，后续文本作为新 segment
                accText = '';
                continue;
              }
              accText += data.message || '';
              const lastSeg = currentSegs[currentSegs.length - 1];
              if (lastSeg && lastSeg.type === 'text') {
                lastSeg.content = accText;
              } else {
                currentSegs.push({ type: 'text', content: accText });
              }
              changed = true;
            } else if (data.eventType === 'ThinkingEvent') {
              if (data.last === true) {
                // 思考过程结束，重置累积器和索引，后续思考创建新块
                accThinking = '';
                activeThinkIdx = -1;
                continue;
              }
              accThinking += data.message || '';
              if (activeThinkIdx >= 0 && activeThinkIdx < currentSegs.length) {
                currentSegs[activeThinkIdx].content = accThinking;
              } else {
                // 新建一个 thinking segment
                currentSegs.push({ type: 'thinking', content: accThinking });
                activeThinkIdx = currentSegs.length - 1;
              }
              changed = true;
            } else if (data.eventType === 'CallToolEvent') {
              accText = '';
              currentSegs.push({
                type: 'tool_call',
                content: JSON.stringify(data.arguments || {}, null, 2),
                toolName: data.toolName || '未知工具',
              });
              changed = true;
            } else if (data.eventType === 'ToolResultEvent') {
              accText = '';
              currentSegs.push({ type: 'tool_result', content: data.message || '' });
              changed = true;
            }
          } catch (err) {
            console.error('解析SSE消息失败:', err, line);
          }
        }

        // 每个 chunk 处理完后统一刷新 UI
        if (changed) {
          flushUI();
        }
      }
    } catch (error: any) {
      if (error?.name === 'AbortError') return;
      console.error('发送消息失败:', error);
      message.error('发送消息失败');
      if (currentSegs.length === 0) {
        currentSegs.push({ type: 'text', content: '消息发送失败，请重试' });
        flushUI();
      }
    } finally {
      setLoading(false);
      abortRef.current = null;
    }
  };

  const handleSend = () => doSend(inputValue);

  const handleKeyPress = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  /* ─── 渲染段落 ─── */
  const renderSegment = (seg: MessageSegment, idx: number) => {
    switch (seg.type) {
      case 'thinking':
        return <ThinkingBlock key={idx} content={seg.content} />;
      case 'tool_call':
        return <ToolCallCard key={idx} toolName={seg.toolName || ''} content={seg.content} />;
      case 'tool_result':
        return <ToolResultCard key={idx} content={seg.content} />;
      default:
        return (
          <div key={idx} className={styles.markdownBody}>
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ code: CodeBlock as any }}>
              {seg.content}
            </ReactMarkdown>
          </div>
        );
    }
  };

  const isEmpty = (msg: ChatMessage) =>
    msg.segments.length === 0 || msg.segments.every((s) => !s.content);

  return (
    <div className={styles.chatWindow}>
      {/* ─── 消息列表 ─── */}
      <div className={styles.messageList}>
        {messages.length === 0 ? (
          <div className={styles.emptyState}>
            <div className={styles.emptyIcon}>
              <RobotOutlined style={{ color: '#fff' }} />
            </div>
            <div className={styles.emptyTitle}>有什么可以帮你的？</div>
            <div className={styles.emptySubtitle}>选择一个话题，或直接输入你的问题</div>
            <div className={styles.suggestions}>
              {SUGGESTIONS.map((s) => (
                <div key={s} className={styles.suggestionChip} onClick={() => doSend(s)}>
                  {s}
                </div>
              ))}
            </div>
          </div>
        ) : (
          messages.map((msg) => (
            <div
              key={msg.id}
              className={`${styles.messageRow} ${msg.role === 'user' ? styles.messageRowUser : ''}`}
            >
              <div
                className={`${styles.avatar} ${
                  msg.role === 'user' ? styles.avatarUser : styles.avatarAssistant
                }`}
              >
                {msg.role === 'user' ? <UserOutlined /> : <RobotOutlined />}
              </div>

              <div className={styles.messageBubble}>
                <div
                  className={`${styles.bubbleInner} ${
                    msg.role === 'user' ? styles.bubbleUser : styles.bubbleAssistant
                  }`}
                >
                  {msg.role === 'user' ? (
                    <span>{msg.segments[0]?.content}</span>
                  ) : isEmpty(msg) ? (
                    <LoadingDots />
                  ) : (
                    msg.segments.map((seg, idx) => renderSegment(seg, idx))
                  )}
                </div>
                <div
                  className={`${styles.timestamp} ${msg.role === 'user' ? styles.timestampUser : ''}`}
                >
                  {new Date(msg.timestamp).toLocaleTimeString()}
                </div>
              </div>
            </div>
          ))
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* ─── 输入区域 ─── */}
      <div className={styles.inputArea}>
        <div className={styles.inputCard}>
          <div className={styles.inputCardBody}>
            <TextArea
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder="输入消息... (Enter 发送，Shift + Enter 换行)"
              autoSize={{ minRows: 2, maxRows: 6 }}
              disabled={loading}
            />
          </div>
          <div className={styles.inputCardFooter}>
            <div className={styles.options}>
              <div
                className={`${styles.optionItem} ${enableThink ? styles.optionActive : ''}`}
                onClick={() => setEnableThink(!enableThink)}
              >
                <BulbOutlined />
                <span>深度思考</span>
              </div>
              <div
                className={`${styles.optionItem} ${enableSearch ? styles.optionActive : ''}`}
                onClick={() => setEnableSearch(!enableSearch)}
              >
                <SearchOutlined />
                <span>联网搜索</span>
              </div>
            </div>
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={handleSend}
              loading={loading}
              disabled={!inputValue.trim()}
              className={styles.sendButton}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

export default ChatWindow;
