import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Input, Button, message, Modal, Table, Tag, Space, Upload, Popconfirm, Collapse, Spin } from 'antd';
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
  ExclamationCircleOutlined,
  PictureOutlined,
  CloseOutlined,
  DeleteOutlined,
  UnorderedListOutlined,
  ClockCircleOutlined,
  CheckSquareOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { getSessionMessages } from '@/services/ant-design-pro/chat';
import styles from './ChatWindow.less';

const { TextArea } = Input;

/* ─── 类型 ─── */
interface PendingCallTool {
  toolId: string;
  toolName: string;
  arguments: Record<string, any>;
  isDangerous: boolean;
}

interface MessageSegment {
  type: 'text' | 'thinking' | 'tool_call' | 'tool_result' | 'tool_confirm';
  content: string;
  toolName?: string;
  toolId?: string;
  confirmStatus?: 'pending' | 'confirmed' | 'rejected';
  pendingCallTools?: PendingCallTool[];
}

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  segments: MessageSegment[];
  timestamp: number;
  imageUrls?: string[];
}

interface ChatWindowProps {
  sessionId: string;
}

interface PlanSubTask {
  name: string;
  description: string;
  expectedOutcome: string;
  outcome: string;
  state: 'TODO' | 'IN_PROGRESS' | 'DONE' | 'ABANDONED';
  createdAt: string;
  finishedAt: string | null;
  costTimeSeconds: number;
}

interface PlanNote {
  sessionId: string;
  planId: string;
  name: string;
  description: string | null;
  expectedOutcome: string | null;
  subtasks: PlanSubTask[] | null;
  createdAt: string;
  finishedAt: string | null;
  costTimeseconds: number;
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
const ToolCallCard: React.FC<{
  toolName: string;
  content: string;
  confirmStatus?: 'pending' | 'confirmed' | 'rejected';
}> = ({ toolName, content, confirmStatus }) => {
  const [expanded, setExpanded] = useState(false);
  
  // 根据确认状态显示不同的文本
  const statusText = confirmStatus === 'confirmed' 
    ? '（已允许）' 
    : confirmStatus === 'rejected' 
    ? '（已拒绝）' 
    : confirmStatus === 'pending' 
    ? '（待确认）' 
    : '';
  
  return (
    <div style={{ display: 'block', width: '100%' }}>
      <div className={styles.toolCard}>
        <div className={styles.toolCardHeader} onClick={() => setExpanded(!expanded)}>
          <ToolOutlined /> 调用工具: {toolName}{statusText}
          {expanded ? <DownOutlined style={{ fontSize: 10, marginLeft: 4 }} /> : <RightOutlined style={{ fontSize: 10, marginLeft: 4 }} />}
        </div>
        {expanded && content && <div className={styles.toolCardBody}>{content}</div>}
      </div>
    </div>
  );
};

const ToolResultCard: React.FC<{ content: string }> = ({ content }) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <div style={{ display: 'block', width: '100%' }}>
      <div className={styles.toolResultCard}>
        <div className={styles.toolResultHeader} onClick={() => setExpanded(!expanded)}>
          <CheckCircleOutlined /> 工具返回
          {expanded ? <DownOutlined style={{ fontSize: 10, marginLeft: 4 }} /> : <RightOutlined style={{ fontSize: 10, marginLeft: 4 }} />}
        </div>
        {expanded && <div className={styles.toolResultBody}>{content}</div>}
      </div>
    </div>
  );
};

/* ─── 工具确认卡片（用于聊天内容中显示） ─── */
const ToolConfirmChatCard: React.FC<{
  pendingCallTools: PendingCallTool[];
  status: 'pending' | 'confirmed' | 'rejected';
}> = ({ pendingCallTools, status }) => {
  const [expanded, setExpanded] = useState(false);
  
  const statusText = status === 'confirmed' ? '已允许' : status === 'rejected' ? '已拒绝' : '等待确认';
  const statusColor = status === 'confirmed' ? 'green' : status === 'rejected' ? 'red' : 'orange';
  
  return (
    <div className={styles.toolConfirmCard}>
      <div className={styles.toolConfirmCardHeader} onClick={() => setExpanded(!expanded)}>
        <ExclamationCircleOutlined style={{ color: '#faad14' }} />
        <span>工具执行确认</span>
        <Tag color={statusColor}>{statusText}</Tag>
        {expanded ? <DownOutlined style={{ fontSize: 10, marginLeft: 4 }} /> : <RightOutlined style={{ fontSize: 10, marginLeft: 4 }} />}
      </div>
      {expanded && (
        <div className={styles.toolConfirmCardBody}>
          <div style={{ marginBottom: 8, fontSize: 13, color: '#666' }}>
            AI 想要调用以下工具：
          </div>
          {pendingCallTools.map((tool) => (
            <div key={tool.toolId} style={{ marginBottom: 8, padding: 8, background: '#fafafa', borderRadius: 4 }}>
              <div style={{ fontWeight: 500, marginBottom: 4 }}>
                <ToolOutlined /> {tool.toolName}
                {tool.isDangerous && <Tag color="red" style={{ marginLeft: 8 }}>高风险</Tag>}
              </div>
              <pre style={{ margin: 0, fontSize: 12, maxHeight: 100, overflow: 'auto' }}>
                {JSON.stringify(tool.arguments, null, 2)}
              </pre>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

/* ─── 工具确认弹窗 ─── */
const ToolConfirmCard: React.FC<{
  pendingCallTools: PendingCallTool[];
  onConfirm: (confirmed: boolean) => void;
}> = ({ pendingCallTools, onConfirm }) => {
  const [modalVisible, setModalVisible] = useState(true);

  const handleConfirm = () => {
    setModalVisible(false);
    onConfirm(true);
  };

  const handleReject = () => {
    setModalVisible(false);
    onConfirm(false);
  };

  const columns = [
    {
      title: '工具名称',
      dataIndex: 'toolName',
      key: 'toolName',
      width: 150,
    },
    {
      title: '参数',
      dataIndex: 'arguments',
      key: 'arguments',
      render: (args: Record<string, any>) => (
        <pre style={{ margin: 0, fontSize: 12, maxHeight: 100, overflow: 'auto' }}>
          {JSON.stringify(args, null, 2)}
        </pre>
      ),
    },
    {
      title: '风险级别',
      dataIndex: 'isDangerous',
      key: 'isDangerous',
      width: 100,
      render: (isDangerous: boolean) => (
        <Tag color={isDangerous ? 'red' : 'green'}>
          {isDangerous ? '高风险' : '低风险'}
        </Tag>
      ),
    },
  ];

  return (
    <Modal
      title={
        <span>
          <ExclamationCircleOutlined style={{ color: '#faad14', marginRight: 8 }} />
          工具执行确认
        </span>
      }
      open={modalVisible}
      onCancel={handleReject}
      footer={
        <Space>
          <Button danger onClick={handleReject}>
            拒绝
          </Button>
          <Button type="primary" onClick={handleConfirm}>
            允许执行
          </Button>
        </Space>
      }
      width={700}
    >
      <div style={{ marginBottom: 16 }}>
        <p>AI 想要调用以下工具，请确认是否允许执行：</p>
      </div>
      <Table
        columns={columns}
        dataSource={pendingCallTools}
        rowKey="toolId"
        pagination={false}
        size="small"
      />
    </Modal>
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
  const [enablePlan, setEnablePlan] = useState(false);
  const [imageUrls, setImageUrls] = useState<string[]>([]);
  const [pendingConfirm, setPendingConfirm] = useState<{
    pendingCallTools: PendingCallTool[];
    resolve: (confirmed: boolean) => void;
  } | null>(null);
  const [showPlanPanel, setShowPlanPanel] = useState(false);
  const [plans, setPlans] = useState<PlanNote[]>([]);
  const [loadingPlans, setLoadingPlans] = useState(false);
  const [currentPlan, setCurrentPlan] = useState<any>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const uploadRef = useRef<HTMLInputElement>(null);
  const planRefreshTimerRef = useRef<NodeJS.Timeout | null>(null); // 当前计划刷新定时器（2秒）
  const plansListTimerRef = useRef<NodeJS.Timeout | null>(null); // 历史计划刷新定时器（5秒）

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  // 组件卸载时中断请求和定时器
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
      if (planRefreshTimerRef.current) {
        clearInterval(planRefreshTimerRef.current);
      }
      if (plansListTimerRef.current) {
        clearInterval(plansListTimerRef.current);
      }
    };
  }, []);

  // 当 sessionId 变化时，加载历史消息
  useEffect(() => {
    if (!sessionId) {
      setMessages([]);
      setPlans([]);
      setShowPlanPanel(false);
      return;
    }

    const loadHistoryMessages = async () => {
      try {
        setLoading(true);
        const response = await getSessionMessages(sessionId);
        
        if (response.code === 200 && response.data) {
          const logs: any[] = response.data;
          const historyMessages: ChatMessage[] = [];
          
          // 遍历所有日志，将同一个 AI 回复的所有 segment 合并到一个消息中
          let currentAssistantMsg: ChatMessage | null = null;
          
          for (let i = 0; i < logs.length; i++) {
            const log = logs[i];
            const msgId = `${log.role.toLowerCase()}-${log.timestamp || Date.now()}-${i}`;
            
            if (log.role === 'USER') {
              // 用户消息：结束当前的 assistant 消息（如果有）
              currentAssistantMsg = null;
              historyMessages.push({
                id: msgId,
                role: 'user' as const,
                segments: [{ type: 'text', content: log.message || '' }],
                timestamp: log.timestamp || Date.now(),
              });
            } else if (log.role === 'ASSISTANT') {
              // 助手消息：创建或继续当前的 assistant 消息
              if (!currentAssistantMsg) {
                // 创建新的 assistant 消息
                currentAssistantMsg = {
                  id: msgId,
                  role: 'assistant' as const,
                  segments: [],
                  timestamp: log.timestamp || Date.now(),
                };
                historyMessages.push(currentAssistantMsg);
              }
              
              const segments = currentAssistantMsg.segments;
              
              // 添加思考过程
              if (log.thinking) {
                segments.push({ type: 'thinking', content: log.thinking });
              }
              
              // 添加工具调用日志
              if (log.toolUseLog && log.toolUseLog.length > 0) {
                // 遍历每个工具调用
                for (const tool of log.toolUseLog) {
                  // 添加工具调用
                  segments.push({
                    type: 'tool_call',
                    content: JSON.stringify(tool.input || {}, null, 2),
                    toolName: tool.name || '未知工具',
                  });
                  
                  // 检查下一条消息是否是对应的工具结果
                  // 工具结果通常紧跟在助手消息之后
                  if (i + 1 < logs.length && logs[i + 1].role === 'TOOL') {
                    const toolResultLog = logs[i + 1];
                    // 匹配工具名称（如果有的话）
                    if (!toolResultLog.name || toolResultLog.name === tool.name) {
                      segments.push({
                        type: 'tool_result',
                        content: toolResultLog.result || '',
                      });
                      i++; // 跳过下一条，因为已经处理了
                    }
                  }
                }
              }
              
              // 添加文本回复
              if (log.text) {
                segments.push({ type: 'text', content: log.text });
              }
            } else if (log.role === 'TOOL') {
              // 如果工具结果没有被前面的 ASSISTANT 消息吸收，添加到当前的 assistant 消息
              if (currentAssistantMsg) {
                currentAssistantMsg.segments.push({
                  type: 'tool_result',
                  content: log.result || '',
                });
              } else {
                // 如果没有当前的 assistant 消息，创建一个新的
                currentAssistantMsg = {
                  id: msgId,
                  role: 'assistant' as const,
                  segments: [{ type: 'tool_result', content: log.result || '' }],
                  timestamp: log.timestamp || Date.now(),
                };
                historyMessages.push(currentAssistantMsg);
              }
            }
          }
          
          setMessages(historyMessages);
        }
      } catch (error) {
        console.error('加载历史消息失败:', error);
        message.error('加载历史消息失败');
      } finally {
        setLoading(false);
      }
    };

    loadHistoryMessages();
  }, [sessionId]);

  /* ─── 发送消息（fetch + ReadableStream，走代理） ─── */
  const doSend = async (text: string) => {
    if (!text.trim() || loading) return;

    const userMessage: ChatMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      segments: [{ type: 'text', content: text.trim() }],
      timestamp: Date.now(),
      imageUrls: imageUrls.length > 0 ? [...imageUrls] : undefined,
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
    setImageUrls([]); // 清空图片
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
      const chatBody = {
        sessionId,
        message: text.trim(),
        imageUrl: imageUrls,
        enableThink,
        enableSearch,
        enablePlan,
      };

      const response = await fetch('/ai/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify(chatBody),
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
            } else if (data.eventType === 'ToolConfirmEvent') {
              // 收到工具确认事件，暂停并等待用户确认
              accText = '';
              const pendingTools = data.pendingCallTools || [];
              
              // 为每个待确认的工具添加工具调用卡片（与正常工具调用一致）
              for (const tool of pendingTools) {
                currentSegs.push({
                  type: 'tool_call',
                  content: JSON.stringify(tool.arguments || {}, null, 2),
                  toolName: tool.toolName,
                  // 添加工具ID用于后续更新状态
                  toolId: tool.toolId,
                  // 标记为待确认状态
                  confirmStatus: 'pending' as const,
                });
              }
              changed = true;
              flushUI();
              
              // 创建一个 Promise 等待用户确认
              const confirmResult = await new Promise<boolean>((resolve) => {
                setPendingConfirm({
                  pendingCallTools: pendingTools,
                  resolve,
                });
              });
              
              // 用户确认后，清除待确认状态
              setPendingConfirm(null);
              
              // 更新聊天内容中的工具调用状态
              for (let i = 0; i < currentSegs.length; i++) {
                const seg = currentSegs[i];
                if (seg.type === 'tool_call' && seg.confirmStatus === 'pending') {
                  // 更新状态为已允许或已拒绝
                  seg.confirmStatus = confirmResult ? 'confirmed' : 'rejected';
                }
              }
              changed = true;
              flushUI();
              
              // 调用 confirm 接口 (POST)
              const confirmBody = {
                sessionId,
                isConfirmed: confirmResult,
                toolInfoList: pendingTools.map((tool: PendingCallTool) => ({
                  toolId: tool.toolId,
                  toolName: tool.toolName,
                })),
                enableThink,
                enableSearch,
              };
              
              const confirmResponse = await fetch('/ai/confirm', {
                method: 'POST',
                headers: {
                  'Content-Type': 'application/json',
                  'Accept': 'text/event-stream',
                },
                body: JSON.stringify(confirmBody),
                signal: abortController.signal,
              });
              
              if (!confirmResponse.ok) throw new Error(`HTTP ${confirmResponse.status}`);
              
              const confirmReader = confirmResponse.body?.getReader();
              if (!confirmReader) throw new Error('无法读取响应流');
              
              let confirmBuffer = '';
              
              while (true) {
                const { done, value } = await confirmReader.read();
                if (done) break;
                
                confirmBuffer += decoder.decode(value, { stream: true });
                const confirmLines = confirmBuffer.split('\n');
                confirmBuffer = confirmLines.pop() || '';
                
                let confirmChanged = false;
                for (const line of confirmLines) {
                  if (!line.startsWith('data:')) continue;
                  const confirmJsonStr = line.substring(5).trim();
                  if (!confirmJsonStr) continue;
                  try {
                    const confirmData = JSON.parse(confirmJsonStr);
                    
                    if (confirmData.eventType === 'TextEvent') {
                      if (confirmData.last === true) {
                        accText = '';
                        continue;
                      }
                      accText += confirmData.message || '';
                      const lastSeg = currentSegs[currentSegs.length - 1];
                      if (lastSeg && lastSeg.type === 'text') {
                        lastSeg.content = accText;
                      } else {
                        currentSegs.push({ type: 'text', content: accText });
                      }
                      confirmChanged = true;
                    } else if (confirmData.eventType === 'ThinkingEvent') {
                      if (confirmData.last === true) {
                        accThinking = '';
                        activeThinkIdx = -1;
                        continue;
                      }
                      accThinking += confirmData.message || '';
                      if (activeThinkIdx >= 0 && activeThinkIdx < currentSegs.length) {
                        currentSegs[activeThinkIdx].content = accThinking;
                      } else {
                        currentSegs.push({ type: 'thinking', content: accThinking });
                        activeThinkIdx = currentSegs.length - 1;
                      }
                      confirmChanged = true;
                    } else if (confirmData.eventType === 'CallToolEvent') {
                      accText = '';
                      currentSegs.push({
                        type: 'tool_call',
                        content: JSON.stringify(confirmData.arguments || {}, null, 2),
                        toolName: confirmData.toolName || '未知工具',
                      });
                      confirmChanged = true;
                    } else if (confirmData.eventType === 'ToolResultEvent') {
                      accText = '';
                      currentSegs.push({ type: 'tool_result', content: confirmData.message || '' });
                      confirmChanged = true;
                    } else if (confirmData.eventType === 'ToolConfirmEvent') {
                      // 递归处理嵌套的工具确认
                      accText = '';
                      const nestedPendingTools = confirmData.pendingCallTools || [];
                      
                      // 为每个待确认的工具添加工具调用卡片（与正常工具调用一致）
                      for (const tool of nestedPendingTools) {
                        currentSegs.push({
                          type: 'tool_call',
                          content: JSON.stringify(tool.arguments || {}, null, 2),
                          toolName: tool.toolName,
                          toolId: tool.toolId,
                          confirmStatus: 'pending' as const,
                        });
                      }
                      confirmChanged = true;
                      flushUI();
                      
                      const nestedConfirmResult = await new Promise<boolean>((resolve) => {
                        setPendingConfirm({
                          pendingCallTools: nestedPendingTools,
                          resolve,
                        });
                      });
                      
                      setPendingConfirm(null);
                      
                      // 更新聊天内容中的工具调用状态
                      for (let i = 0; i < currentSegs.length; i++) {
                        const seg = currentSegs[i];
                        if (seg.type === 'tool_call' && seg.confirmStatus === 'pending') {
                          seg.confirmStatus = nestedConfirmResult ? 'confirmed' : 'rejected';
                        }
                      }
                      confirmChanged = true;
                      flushUI();
                      
                      const nestedConfirmBody = {
                        sessionId,
                        isConfirmed: nestedConfirmResult,
                        toolInfoList: nestedPendingTools.map((tool: PendingCallTool) => ({
                          toolId: tool.toolId,
                          toolName: tool.toolName,
                        })),
                        enableThink,
                        enableSearch,
                      };
                      
                      const nestedConfirmResponse = await fetch('/ai/confirm', {
                        method: 'POST',
                        headers: {
                          'Content-Type': 'application/json',
                          'Accept': 'text/event-stream',
                        },
                        body: JSON.stringify(nestedConfirmBody),
                        signal: abortController.signal,
                      });
                      
                      if (!nestedConfirmResponse.ok) throw new Error(`HTTP ${nestedConfirmResponse.status}`);
                      
                      const nestedConfirmReader = nestedConfirmResponse.body?.getReader();
                      if (!nestedConfirmReader) throw new Error('无法读取响应流');
                      
                      let nestedConfirmBuffer = '';
                      
                      while (true) {
                        const { done: nestedDone, value: nestedValue } = await nestedConfirmReader.read();
                        if (nestedDone) break;
                        
                        nestedConfirmBuffer += decoder.decode(nestedValue, { stream: true });
                        const nestedConfirmLines = nestedConfirmBuffer.split('\n');
                        nestedConfirmBuffer = nestedConfirmLines.pop() || '';
                        
                        for (const nestedLine of nestedConfirmLines) {
                          if (!nestedLine.startsWith('data:')) continue;
                          const nestedJsonStr = nestedLine.substring(5).trim();
                          if (!nestedJsonStr) continue;
                          try {
                            const nestedData = JSON.parse(nestedJsonStr);
                            
                            if (nestedData.eventType === 'TextEvent') {
                              if (nestedData.last === true) {
                                accText = '';
                                continue;
                              }
                              accText += nestedData.message || '';
                              const lastSeg = currentSegs[currentSegs.length - 1];
                              if (lastSeg && lastSeg.type === 'text') {
                                lastSeg.content = accText;
                              } else {
                                currentSegs.push({ type: 'text', content: accText });
                              }
                              confirmChanged = true;
                            } else if (nestedData.eventType === 'ThinkingEvent') {
                              if (nestedData.last === true) {
                                accThinking = '';
                                activeThinkIdx = -1;
                                continue;
                              }
                              accThinking += nestedData.message || '';
                              if (activeThinkIdx >= 0 && activeThinkIdx < currentSegs.length) {
                                currentSegs[activeThinkIdx].content = accThinking;
                              } else {
                                currentSegs.push({ type: 'thinking', content: accThinking });
                                activeThinkIdx = currentSegs.length - 1;
                              }
                              confirmChanged = true;
                            } else if (nestedData.eventType === 'CallToolEvent') {
                              accText = '';
                              currentSegs.push({
                                type: 'tool_call',
                                content: JSON.stringify(nestedData.arguments || {}, null, 2),
                                toolName: nestedData.toolName || '未知工具',
                              });
                              confirmChanged = true;
                            } else if (nestedData.eventType === 'ToolResultEvent') {
                              accText = '';
                              currentSegs.push({ type: 'tool_result', content: nestedData.message || '' });
                              confirmChanged = true;
                            }
                          } catch (err) {
                            console.error('解析嵌套SSE消息失败:', err, nestedLine);
                          }
                        }
                        
                        if (confirmChanged) {
                          flushUI();
                        }
                      }
                    }
                  } catch (err) {
                    console.error('解析confirm SSE消息失败:', err, line);
                  }
                }
                
                if (confirmChanged) {
                  flushUI();
                }
              }
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
      
      // 如果启用了计划功能，发送消息后自动加载当前计划
      if (enablePlan) {
        loadCurrentPlan();
      }
    }
  };

  const handleSend = () => doSend(inputValue);

  const handleKeyPress = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  /* ─── 图片处理 ─── */
  const handleImageUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    // 将图片转换为 Base64
    const base64Promises = Array.from(files).map((file) => {
      return new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result as string);
        reader.onerror = reject;
        reader.readAsDataURL(file);
      });
    });

    try {
      const base64Urls = await Promise.all(base64Promises);
      setImageUrls((prev) => [...prev, ...base64Urls]);
    } catch (error) {
      console.error('图片转换失败:', error);
      message.error('图片转换失败');
    }

    // 清空 input 以允许重复上传同一文件
    if (uploadRef.current) {
      uploadRef.current.value = '';
    }
  };

  const handleRemoveImage = (index: number) => {
    setImageUrls((prev) => {
      const newUrls = [...prev];
      newUrls.splice(index, 1);
      return newUrls;
    });
  };

  /* ─── 加载当前计划 ─── */
  const loadCurrentPlan = async () => {
    if (!sessionId) return;
    
    try {
      const response = await fetch(`/ai/session/${sessionId}/current-plan`);
      
      if (!response.ok) {
        return; // 静默失败，不显示错误
      }
      
      const result = await response.json();
      console.log('Current plan API response:', result);
      
      if (result.code === 200 && result.data) {
        // 后端返回的数据结构: { currentPlan: {...} }
        // 将 currentPlan 包装成 activePlan 格式以适配前端渲染逻辑
        const planData = result.data.currentPlan || result.data;
        console.log('Plan data extracted:', planData);
        
        if (planData) {
          setCurrentPlan({
            activePlan: planData
          });
          console.log('Current plan set successfully');
        }
      }
    } catch (error) {
      // 静默失败，不影响用户体验
      console.error('加载当前计划失败:', error);
    }
  };

  /* ─── 加载计划列表 ─── */
  const loadPlans = async () => {
    if (!sessionId) return;
    
    try {
      setLoadingPlans(true);
      const response = await fetch(`/ai/session/${sessionId}/plans`);
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const result = await response.json();
      console.log('Plans API response:', result);
      
      if (result.code === 200 && result.data) {
        setPlans(result.data);
        console.log('Plans loaded:', result.data.length, 'plans');
      }
    } catch (error) {
      console.error('加载计划列表失败:', error);
      // 不显示错误消息，静默失败
    } finally {
      setLoadingPlans(false);
    }
  };

  /* ─── 切换计划面板 ─── */
  const handleTogglePlanPanel = () => {
    const newShowState = !showPlanPanel;
    setShowPlanPanel(newShowState);
    
    if (newShowState) {
      // 打开面板时加载历史计划
      loadPlans();
      
      // 启动定时刷新当前计划（每2秒）
      loadCurrentPlan(); // 立即加载一次
      planRefreshTimerRef.current = setInterval(() => {
        loadCurrentPlan();
      }, 2000);
      
      // 启动定时刷新历史计划（每5秒）
      plansListTimerRef.current = setInterval(() => {
        loadPlans();
      }, 5000);
    } else {
      // 关闭面板时清除所有定时器
      if (planRefreshTimerRef.current) {
        clearInterval(planRefreshTimerRef.current);
        planRefreshTimerRef.current = null;
      }
      if (plansListTimerRef.current) {
        clearInterval(plansListTimerRef.current);
        plansListTimerRef.current = null;
      }
    }
  };

  /* ─── 清空聊天记录 ─── */
  const handleClearChat = async () => {
    try {
      const response = await fetch(`/ai/session/${sessionId}`, {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      // 清空前端聊天记录
      setMessages([]);
      setPlans([]);
      setCurrentPlan(null);
      setInputValue('');
      setImageUrls([]);
      setShowPlanPanel(false);
      
      // 清除所有定时器
      if (planRefreshTimerRef.current) {
        clearInterval(planRefreshTimerRef.current);
        planRefreshTimerRef.current = null;
      }
      if (plansListTimerRef.current) {
        clearInterval(plansListTimerRef.current);
        plansListTimerRef.current = null;
      }
      
      message.success('聊天记录已清空');
    } catch (error) {
      console.error('清空聊天记录失败:', error);
      message.error('清空聊天记录失败');
    }
  };

  /* ─── 渲染段落 ─── */
  const renderSegment = (seg: MessageSegment, idx: number) => {
    switch (seg.type) {
      case 'thinking':
        return <ThinkingBlock key={idx} content={seg.content} />;
      case 'tool_call':
        return (
          <ToolCallCard
            key={idx}
            toolName={seg.toolName || ''}
            content={seg.content}
            confirmStatus={seg.confirmStatus}
          />
        );
      case 'tool_result':
        return <ToolResultCard key={idx} content={seg.content} />;
      case 'tool_confirm':
        const status = (seg.content as 'pending' | 'confirmed' | 'rejected') || 'pending';
        return (
          <ToolConfirmChatCard
            key={idx}
            pendingCallTools={seg.pendingCallTools || []}
            status={status}
          />
        );
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

  /* ─── 渲染计划状态标签 ─── */
  const renderPlanState = (state: string) => {
    const stateMap: Record<string, { color: string; text: string }> = {
      TODO: { color: 'default', text: '待处理' },
      IN_PROGRESS: { color: 'processing', text: '进行中' },
      DONE: { color: 'success', text: '已完成' },
      ABANDONED: { color: 'error', text: '已放弃' },
    };
    const config = stateMap[state] || { color: 'default', text: state };
    return <Tag color={config.color}>{config.text}</Tag>;
  };

  /* ─── 渲染计划面板 ─── */
  const renderPlanPanel = () => {
    if (!showPlanPanel) return null;

    return (
      <div className={styles.planPanel}>
        <div className={styles.planPanelHeader}>
          <h3>执行计划</h3>
          <Button
            type="text"
            icon={<CloseOutlined />}
            onClick={() => setShowPlanPanel(false)}
            size="small"
          />
        </div>
        <div className={styles.planPanelContent}>
          {/* 当前计划状态 */}
          {currentPlan && (
            <div className={styles.currentPlanSection}>
              <div className={styles.currentPlanHeader}>
                <h4>当前计划</h4>
                <div className={styles.refreshIndicator}>
                  <ClockCircleOutlined spin />
                  <span>实时更新</span>
                </div>
              </div>
              {currentPlan.activePlan && (
                <div className={styles.activePlanCard}>
                  <div className={styles.planTitle}>{currentPlan.activePlan.name}</div>
                  {currentPlan.activePlan.description && (
                    <p className={styles.planDesc}>{currentPlan.activePlan.description}</p>
                  )}
                  {currentPlan.activePlan.subtasks && currentPlan.activePlan.subtasks.length > 0 && (
                    <div className={styles.currentSubtasks}>
                      <div className={styles.subtaskProgress}>
                        <span>进度：</span>
                        <span>
                          {currentPlan.activePlan.subtasks.filter((t: any) => t.state === 'DONE').length} / {currentPlan.activePlan.subtasks.length}
                        </span>
                      </div>
                      {currentPlan.activePlan.subtasks.map((subtask: any, idx: number) => (
                        <div key={idx} className={styles.subtaskItem}>
                          <div className={styles.subtaskIcon}>
                            {subtask.state === 'DONE' && <CheckCircleOutlined style={{ color: '#52c41a' }} />}
                            {subtask.state === 'IN_PROGRESS' && <ClockCircleOutlined spin style={{ color: '#1890ff' }} />}
                            {subtask.state === 'TODO' && <div className={styles.todoIcon} />}
                            {subtask.state === 'ABANDONED' && <CloseOutlined style={{ color: '#ff4d4f' }} />}
                          </div>
                          <div className={styles.subtaskInfo}>
                            <div className={styles.subtaskName}>{subtask.name}</div>
                            {subtask.state === 'IN_PROGRESS' && (
                              <div className={styles.subtaskStatus}>执行中...</div>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
              {!currentPlan.activePlan && (
                <div className={styles.noActivePlan}>
                  <p>当前没有活动计划</p>
                </div>
              )}
            </div>
          )}

          {/* 历史计划 */}
          {loadingPlans && (
            <div className={styles.loadingPlans}>
              <p>加载计划中...</p>
            </div>
          )}
          
          {!loadingPlans && plans.length > 0 && (
            <div className={styles.historyPlansSection}>
              <h4>历史计划 ({plans.length})</h4>
              <Collapse accordion defaultActiveKey={[plans[0]?.planId]}>
                {plans.map((plan) => (
                  <Collapse.Panel
                    key={plan.planId}
                    header={
                      <div className={styles.planHeader}>
                        <span className={styles.planName}>{plan.name}</span>
                        {plan.finishedAt && (
                          <Tag color="success" style={{ marginLeft: 8 }}>
                            已完成
                          </Tag>
                        )}
                      </div>
                    }
                  >
                    {plan.description && (
                      <p className={styles.planDescription}>{plan.description}</p>
                    )}
                    {plan.expectedOutcome && (
                      <div className={styles.planOutcome}>
                        <strong>预期结果：</strong>
                        <span>{plan.expectedOutcome}</span>
                      </div>
                    )}
                    {plan.subtasks && plan.subtasks.length > 0 && (
                      <div className={styles.planSubtasks}>
                        <h4>子任务 ({plan.subtasks.length})</h4>
                        <Table
                          dataSource={plan.subtasks}
                          rowKey={(record) => record.name}
                          size="small"
                          pagination={false}
                          columns={[
                            {
                              title: '状态',
                              dataIndex: 'state',
                              key: 'state',
                              width: 90,
                              render: (state: string) => renderPlanState(state),
                            },
                            {
                              title: '任务名称',
                              dataIndex: 'name',
                              key: 'name',
                              ellipsis: true,
                            },
                            {
                              title: '耗时',
                              dataIndex: 'costTimeSeconds',
                              key: 'costTimeSeconds',
                              width: 70,
                              render: (seconds: number) => `${seconds}s`,
                            },
                          ]}
                          expandable={{
                            expandedRowRender: (record) => (
                              <div className={styles.subtaskDetail}>
                                {record.description && (
                                  <p>
                                    <strong>描述：</strong>
                                    {record.description}
                                  </p>
                                )}
                                {record.expectedOutcome && (
                                  <p>
                                    <strong>预期结果：</strong>
                                    {record.expectedOutcome}
                                  </p>
                                )}
                                {record.outcome && (
                                  <p>
                                    <strong>实际结果：</strong>
                                    {record.outcome}
                                  </p>
                                )}
                              </div>
                            ),
                          }}
                        />
                      </div>
                    )}
                    <div className={styles.planFooter}>
                      <span>创建时间：{plan.createdAt}</span>
                      {plan.costTimeseconds > 0 && (
                        <span>总耗时：{plan.costTimeseconds}s</span>
                      )}
                    </div>
                  </Collapse.Panel>
                ))}
              </Collapse>
            </div>
          )}

          {!loadingPlans && plans.length === 0 && (
            <div className={styles.planEmpty}>
              <ClockCircleOutlined style={{ fontSize: 32, color: '#d9d9d9' }} />
              <p>暂无历史计划</p>
              <span>开启"开启计划"选项后，AI 会自动创建计划</span>
            </div>
          )}
        </div>
      </div>
    );
  };

  return (
    <div className={styles.chatWindow}>
      {/* ─── 计划面板 ─── */}
      {renderPlanPanel()}
      {/* ─── 工具确认弹窗 ─── */}
      {pendingConfirm && (
        <ToolConfirmCard
          pendingCallTools={pendingConfirm.pendingCallTools}
          onConfirm={(confirmed) => {
            pendingConfirm.resolve(confirmed);
          }}
        />
      )}
      
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
                    <div>
                      {msg.imageUrls && msg.imageUrls.length > 0 && (
                        <div className={styles.imagePreview}>
                          {msg.imageUrls.map((url, idx) => (
                            <img key={idx} src={url} alt={`图片${idx + 1}`} className={styles.previewImage} />
                          ))}
                        </div>
                      )}
                      <span>{msg.segments[0]?.content}</span>
                    </div>
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
          {/* 图片预览 */}
          {imageUrls.length > 0 && (
            <div className={styles.imageUploadPreview}>
              {imageUrls.map((url, index) => (
                <div key={index} className={styles.uploadedImageItem}>
                  <img src={url} alt={`上传图片${index + 1}`} className={styles.uploadedImage} />
                  <div
                    className={styles.removeImageBtn}
                    onClick={() => handleRemoveImage(index)}
                  >
                    <CloseOutlined />
                  </div>
                </div>
              ))}
            </div>
          )}
          
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
                className={`${styles.optionItem} ${imageUrls.length > 0 ? styles.optionActive : ''}`}
                onClick={() => uploadRef.current?.click()}
              >
                <PictureOutlined />
                <span>图片 ({imageUrls.length})</span>
              </div>
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
              <div
                className={`${styles.optionItem} ${enablePlan ? styles.optionActive : ''}`}
                onClick={() => setEnablePlan(!enablePlan)}
              >
                <UnorderedListOutlined />
                <span>开启计划</span>
              </div>
              <div
                className={`${styles.optionItem} ${showPlanPanel ? styles.optionActive : ''}`}
                onClick={handleTogglePlanPanel}
              >
                <CheckSquareOutlined />
                <span>查看计划</span>
              </div>
              <Popconfirm
                title="清空聊天记录"
                description="确定要清空当前会话的所有聊天记录吗?"
                onConfirm={handleClearChat}
                okText="确定"
                cancelText="取消"
                placement="topLeft"
              >
                <div className={`${styles.optionItem} ${styles.clearOption}`}>
                  <DeleteOutlined />
                  <span>清空记录</span>
                </div>
              </Popconfirm>
            </div>
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={handleSend}
              loading={loading}
              disabled={!inputValue.trim() && imageUrls.length === 0}
              className={styles.sendButton}
            />
          </div>
        </div>
      </div>
      
      {/* 隐藏的文件输入 */}
      <input
        ref={uploadRef}
        type="file"
        accept="image/*"
        multiple
        style={{ display: 'none' }}
        onChange={handleImageUpload}
      />
    </div>
  );
};

export default ChatWindow;
