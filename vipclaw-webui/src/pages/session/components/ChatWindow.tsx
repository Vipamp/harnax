import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Input, Button, message, Modal, Table, Tag, Space, Upload, Popconfirm, Collapse, Spin, Dropdown } from 'antd';
import {
  SendOutlined,
  BulbOutlined,
  SearchOutlined,
  DownOutlined,
  UpOutlined,
  RightOutlined,
  LeftOutlined,
  ReloadOutlined,
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
  EyeOutlined,
  EyeInvisibleOutlined,
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
  type: 'text' | 'thinking' | 'tool_call' | 'tool_result' | 'tool_confirm' | 'plan_card';
  content: string;
  toolName?: string;
  toolId?: string;
  confirmStatus?: 'pending' | 'confirmed' | 'rejected';
  pendingCallTools?: PendingCallTool[];
  planData?: any; // 计划数据
  toolResult?: string; // 工具返回结果（合并显示时使用）
  toolResultExpanded?: boolean; // 工具结果是否展开（合并显示时使用）
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
  status: string; // TODO, IN_PROGRESS, DONE, ABANDONED
}

/* ─── 快捷建议 ─── */
const SUGGESTIONS = [
  '帮我写一段 Java 排序算法',
  '解释一下 Spring Boot 的自动装配原理',
  '用 Python 实现一个简单的 Web 服务器',
  '如何优化 SQL 慢查询？',
];

/* ─── 辅助函数：获取 JWT Token（与 requestInterceptors 逻辑一致） ─── */
const getAuthHeaders = (): Record<string, string> => {
  try {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    console.log('[ChatWindow] tokenInfoStr:', tokenInfoStr);
    if (tokenInfoStr) {
      const tokenInfo = JSON.parse(tokenInfoStr);
      console.log('[ChatWindow] tokenInfo:', tokenInfo);
      if (tokenInfo.accessToken) {
        const headers = { 'Authorization': `Bearer ${tokenInfo.accessToken}` };
        console.log('[ChatWindow] Authorization header:', headers);
        return headers;
      }
    }
  } catch (e) {
    console.error('[ChatWindow] 获取 token 失败:', e);
  }
  return {};
};

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
  const [expanded, setExpanded] = useState(true); // 默认展开
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

/* ─── 合并的工具卡片（工具调用 + 工具返回） ─── */
const MergedToolCard: React.FC<{
  toolName: string;
  arguments: string;
  result?: string;
  confirmStatus?: 'pending' | 'confirmed' | 'rejected';
  onToggleResult?: () => void;
}> = ({ toolName, arguments: args, result, confirmStatus, onToggleResult }) => {
  const [expanded, setExpanded] = useState(false); // 默认折叠
  const hasResult = !!result;
  
  // 根据确认状态显示不同的文本和颜色
  const statusConfig = {
    pending: { text: '待确认', color: '#faad14', icon: <ExclamationCircleOutlined /> },
    confirmed: { text: '已允许', color: '#52c41a', icon: <CheckCircleOutlined /> },
    rejected: { text: '已拒绝', color: '#ff4d4f', icon: <CloseOutlined /> },
    calling: { text: '调用中', color: '#1890ff', icon: <ClockCircleOutlined spin /> },
    completed: { text: '已完成', color: '#52c41a', icon: <CheckCircleOutlined /> },
  };
  
  let status = 'calling';
  if (confirmStatus === 'pending') status = 'pending';
  else if (confirmStatus === 'rejected') status = 'rejected';
  else if (confirmStatus === 'confirmed' && !hasResult) status = 'confirmed';
  else if (hasResult) status = 'completed';
  
  const currentStatus = statusConfig[status as keyof typeof statusConfig];
  
  return (
    <div className={styles.mergedToolCard}>
      {/* 工具调用头部 - 始终显示 */}
      <div 
        className={styles.mergedToolHeader}
        onClick={() => setExpanded(!expanded)}
        style={{ cursor: 'pointer' }}
      >
        <div className={styles.mergedToolTitle}>
          <ToolOutlined style={{ color: currentStatus.color }} />
          <span className={styles.mergedToolName}>{toolName}</span>
          <span className={styles.mergedToolStatus} style={{ color: currentStatus.color }}>
            {currentStatus.icon}
            <span style={{ marginLeft: 4 }}>{currentStatus.text}</span>
          </span>
        </div>
        <div className={styles.mergedToolArrow}>
          {expanded ? <UpOutlined /> : <DownOutlined />}
        </div>
      </div>
      
      {/* 展开后显示参数和结果 */}
      {expanded && (
        <>
          {/* 工具参数 */}
          {args && (
            <div className={styles.mergedToolArgs}>
              <div className={styles.mergedToolSectionLabel}>参数</div>
              <pre className={styles.mergedToolCode}>{args}</pre>
            </div>
          )}
          
          {/* 工具返回结果 */}
          {hasResult && (
            <div className={styles.mergedToolResult}>
              <div className={styles.mergedToolSectionLabel}>返回结果</div>
              <pre className={styles.mergedToolCode}>{result}</pre>
            </div>
          )}
        </>
      )}
    </div>
  );
};

/* ─── 工具卡片（旧版，保留兼容） ─── */
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
  const [showThinking, setShowThinking] = useState(true); // 是否显示思考过程
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
  const [currentPlanExpanded, setCurrentPlanExpanded] = useState(true); // 当前计划默认展开
  const previousHasPlanRef = useRef<boolean>(false); // 记录上一次是否有计划
  const currentPlanMessageIdRef = useRef<string | null>(null); // 当前计划消息的ID
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

  // 当开启计划功能时，立即加载当前计划
  useEffect(() => {
    if (enablePlan && sessionId) {
      loadCurrentPlan();
    }
  }, [enablePlan, sessionId]);

  // 根据当前计划的折叠状态控制定时器
  useEffect(() => {
    if (enablePlan && currentPlanExpanded && currentPlan) {
      // 展开状态：启动定时器，每2秒刷新一次
      planRefreshTimerRef.current = setInterval(() => {
        loadCurrentPlan();
      }, 2000);
    } else {
      // 折叠状态或无计划：清除定时器
      if (planRefreshTimerRef.current) {
        clearInterval(planRefreshTimerRef.current);
        planRefreshTimerRef.current = null;
      }
    }

    // 组件卸载时清理定时器
    return () => {
      if (planRefreshTimerRef.current) {
        clearInterval(planRefreshTimerRef.current);
      }
    };
  }, [currentPlanExpanded, currentPlan, enablePlan]);

  // 组件卸载时中断请求和定时器
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
      // planRefreshTimerRef 已经在上面的 useEffect 中清理
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
      setCurrentPlan(null);
      currentPlanMessageIdRef.current = null; // 重置计划消息ID
      previousHasPlanRef.current = false; // 重置计划状态
      return;
    }

    // 切换会话时重置计划卡片ID
    currentPlanMessageIdRef.current = null;
    previousHasPlanRef.current = false;

    const loadHistoryMessages = async () => {
      try {
        setLoading(true);
        const response = await getSessionMessages(sessionId);
        
        if (response.code === 200 && response.data) {
          const logs: any[] = response.data;
          const historyMessages: ChatMessage[] = [];
          
          // 遍历所有日志，将同一个 AI 回复的所有 segment 合并到一个消息中
          let currentAssistantMsg: ChatMessage | null = null;
          let lastProcessedIndex = -1; // 记录最后处理的消息索引
          
          for (let i = 0; i < logs.length; i++) {
            // 如果当前索引已经被处理过，跳过
            if (i <= lastProcessedIndex) continue;
            
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
              lastProcessedIndex = i;
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
              
              // 添加思考过程（需要合并连续的思考）
              if (log.thinking) {
                // 检查上一个 segment 是否是 thinking 类型
                const lastSeg = segments.length > 0 ? segments[segments.length - 1] : null;
                if (lastSeg && lastSeg.type === 'thinking') {
                  // 如果最后一个是 thinking，合并到它（追加内容）
                  lastSeg.content = (lastSeg.content || '') + '\n\n' + log.thinking;
                } else {
                  // 否则创建新的 thinking segment
                  segments.push({ type: 'thinking', content: log.thinking });
                }
              }
              
              // 添加工具调用日志
              if (log.toolUseLog && log.toolUseLog.length > 0) {
                // 遍历每个工具调用
                for (const tool of log.toolUseLog) {
                  const toolName = tool.name;
                  
                  // 如果没有工具名称，跳过
                  if (!toolName) {
                    continue;
                  }
                  
                  // 过滤掉计划相关的工具
                  if (isPlanRelatedTool(toolName)) {
                    continue;
                  }
                  
                  // 创建工具调用 segment
                  const toolSeg: MessageSegment = {
                    type: 'tool_call',
                    content: JSON.stringify(tool.input || {}, null, 2),
                    toolName: toolName,
                  };
                  
                  // 检查下一条消息是否是对应的工具结果
                  // 工具结果通常紧跟在助手消息之后
                  if (i + 1 < logs.length && logs[i + 1].role === 'TOOL') {
                    const toolResultLog = logs[i + 1];
                    // 匹配工具名称（如果有的话）
                    if (!toolResultLog.name || toolResultLog.name === tool.name) {
                      // 过滤掉计划相关的工具结果
                      if (!isPlanRelatedTool(toolName)) {
                        // 将工具结果合并到工具调用中
                        toolSeg.toolResult = toolResultLog.result || '';
                      }
                      lastProcessedIndex = i + 1; // 标记下一条已处理
                    }
                  }
                  
                  segments.push(toolSeg);
                }
              }
              
              // 添加文本回复
              if (log.text) {
                segments.push({ type: 'text', content: log.text });
              }
              
              lastProcessedIndex = i;
            } else if (log.role === 'TOOL') {
              // 过滤掉计划相关的工具结果
              const toolName = log.name || '';
              if (isPlanRelatedTool(toolName)) {
                lastProcessedIndex = i;
                continue;
              }
              
              // 如果工具结果没有名称，跳过
              if (!toolName) {
                lastProcessedIndex = i;
                continue;
              }
              
              // 如果工具结果没有被前面的 ASSISTANT 消息吸收，不显示（已合并到工具调用中）
              // 不再创建独立的 tool_result segment
              
              lastProcessedIndex = i;
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
    let activeTextIdx = -1; // 当前活跃的 text segment 索引
    let activeThinkIdx = -1; // 当前活跃的 thinking segment 索引
    let currentEventType: 'text' | 'thinking' | null = null; // 当前事件类型
    let toolCallMap = new Map<string, number>(); // toolId -> segment index 映射
    let currentMsgs = [...messages, userMessage, assistantMessage];
    let currentAssistantMessageId = assistantMessageId; // 可变的 assistant message ID

    setMessages(currentMsgs);
    setInputValue('');
    setImageUrls([]); // 清空图片
    setLoading(true);

    const flushUI = () => {
      const idx = currentMsgs.findIndex((m) => m.id === currentAssistantMessageId);
      if (idx >= 0) {
        currentMsgs = [...currentMsgs];
        currentMsgs[idx] = { ...currentMsgs[idx], segments: [...currentSegs] };
        setMessages(currentMsgs);
      }
    };

    const abortController = new AbortController();
    abortRef.current = abortController;

    try {
      console.log('[ChatWindow] 发送消息, sessionId:', sessionId);
      const chatBody = {
        sessionId,
        message: text.trim(),
        imageUrl: imageUrls,
        enableThink,
        enableSearch,
        enablePlan,
      };
      console.log('[ChatWindow] chatBody:', chatBody);

      const response = await fetch('/ai/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
          ...getAuthHeaders(),
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
            
            // 添加调试日志，查看接收到的所有事件
            console.log('[SSE Event] eventType:', data.eventType, 'data:', data);

            if (data.eventType === 'TextEvent') {
              // 如果当前不是 text 事件，表示上一个内容块结束
              if (currentEventType !== 'text') {
                accText = '';
                activeTextIdx = -1;
              }
              currentEventType = 'text';
              
              if (data.last === true) {
                // 当前文本段结束
                accText = '';
                activeTextIdx = -1;
                currentEventType = null;
                continue;
              }
              
              accText += data.message || '';
              // 创建或更新 text segment
              if (activeTextIdx < 0 || activeTextIdx >= currentSegs.length) {
                currentSegs.push({ type: 'text', content: accText });
                activeTextIdx = currentSegs.length - 1;
              } else {
                currentSegs[activeTextIdx].content = accText;
              }
              changed = true;
              
            } else if (data.eventType === 'ThinkingEvent') {
              // 如果当前不是 thinking 事件，检查是否需要追加到上一个 thinking segment
              if (currentEventType !== 'thinking') {
                // 检查上一个 segment 是否是 thinking 类型
                const lastSeg = currentSegs.length > 0 ? currentSegs[currentSegs.length - 1] : null;
                if (lastSeg && lastSeg.type === 'thinking') {
                  // 如果最后一个是 thinking，继续追加到它
                  activeThinkIdx = currentSegs.length - 1;
                  // 不读取旧内容，accThinking 只保存当前批次的内容
                  accThinking = '';
                } else {
                  // 否则创建新的 thinking segment
                  accThinking = '';
                  activeThinkIdx = -1;
                }
              }
              currentEventType = 'thinking';
              
              // 累积当前批次的思考内容
              accThinking += data.message || '';
              
              // 创建或更新 thinking segment
              if (activeThinkIdx < 0 || activeThinkIdx >= currentSegs.length) {
                // 创建新 segment
                currentSegs.push({ type: 'thinking', content: accThinking });
                activeThinkIdx = currentSegs.length - 1;
              } else {
                // 更新现有 segment：直接赋值为累积的内容
                currentSegs[activeThinkIdx].content = accThinking;
              }
              changed = true;
              
              if (data.last === true) {
                // 当前思考批次结束，但不重置状态
                // 如果下一次还是 ThinkingEvent，会继续追加到同一个 segment
                // 只有遇到其他类型事件时，才会重新检查并可能创建新 segment
                accThinking = '';
                // 不重置 activeThinkIdx 和 currentEventType
              }
              
            } else if (data.eventType === 'CallToolEvent') {
              const toolName = data.toolName;
              const toolId = data.toolId || `tool-${Date.now()}`;
              
              console.log('[CallToolEvent] toolName:', toolName, 'toolId:', toolId);
              
              // 如果没有工具名称，跳过
              if (!toolName) {
                console.log('[CallToolEvent] No toolName, skipping');
                continue;
              }
              
              // 如果是 create_plan，自动打开执行计划侧边栏并加载当前计划
              if (toolName === 'create_plan' && enablePlan) {
                setShowPlanPanel(true);
                // 立即加载当前计划，后续由 useEffect 自动管理定时器
                loadCurrentPlan();
              }
              
              // 过滤掉计划相关的工具
              if (isPlanRelatedTool(toolName)) {
                console.log('[CallToolEvent] Plan-related tool, skipping:', toolName);
                // 计划相关工具不中断思考的连续性
                continue;
              }
              
              // 非计划工具调用，中断思考的连续性
              currentEventType = null;
              accText = '';
              accThinking = '';
              activeTextIdx = -1;
              activeThinkIdx = -1;
              
              const toolSeg: MessageSegment = {
                type: 'tool_call',
                content: JSON.stringify(data.arguments || {}, null, 2),
                toolName: toolName,
                toolId: toolId,
              };
              currentSegs.push(toolSeg);
              toolCallMap.set(toolId, currentSegs.length - 1);
              console.log('[CallToolEvent] Added tool at index:', currentSegs.length - 1);
              changed = true;
              
            } else if (data.eventType === 'ToolResultEvent') {
              const toolId = data.toolId || '';
              const resultContent = data.message || '';
              const toolName = data.toolName || '';
              
              console.log('[ToolResultEvent] toolName:', toolName, 'toolId:', toolId, 'resultContent:', resultContent);
              console.log('[ToolResultEvent] toolCallMap:', Array.from(toolCallMap.entries()));
              
              // 计划相关工具的结果不中断思考的连续性
              if (isPlanRelatedTool(toolName)) {
                console.log('[ToolResultEvent] Plan-related tool result, not interrupting thinking');
                // 根据 toolId 找到对应的工具调用 segment 并更新（如果有）
                if (toolId && toolCallMap.has(toolId)) {
                  const segIdx = toolCallMap.get(toolId)!;
                  currentSegs = currentSegs.map((seg, idx) => 
                    idx === segIdx && seg.type === 'tool_call'
                      ? { ...seg, toolResult: resultContent }
                      : seg
                  );
                }
                changed = true;
                continue;
              }
              
              // 非计划工具结果，中断思考的连续性
              currentEventType = null;
              accText = '';
              accThinking = '';
              activeTextIdx = -1;
              activeThinkIdx = -1;
              
              // 根据 toolId 找到对应的工具调用 segment 并更新
              if (toolId && toolCallMap.has(toolId)) {
                const segIdx = toolCallMap.get(toolId)!;
                console.log('[ToolResultEvent] Found tool at index:', segIdx);
                // 创建新的 segment 对象以触发 React 重新渲染
                currentSegs = currentSegs.map((seg, idx) => 
                  idx === segIdx && seg.type === 'tool_call'
                    ? { ...seg, toolResult: resultContent }
                    : seg
                );
                console.log('[ToolResultEvent] Updated segment:', currentSegs[segIdx]);
              } else {
                // 如果找不到对应的工具调用，不显示独立的工具结果
                console.log('[ToolResultEvent] Tool not found, ignoring result');
              }
              changed = true;
              
              // 检查是否是 finish_subtask 或 finish_plan 工具
              if (toolName === 'finish_subtask' || toolName === 'finish_plan') {
                console.log('[ToolResultEvent] Detected finish tool, creating new assistant message');
                
                // 完成当前消息
                flushUI();
                
                // 创建新的 assistant 消息
                const newAssistantMessageId = `assistant-${Date.now()}-new`;
                const newAssistantMessage: ChatMessage = {
                  id: newAssistantMessageId,
                  role: 'assistant',
                  segments: [],
                  timestamp: Date.now(),
                };
                
                // 更新 currentMsgs
                currentMsgs = [...currentMsgs, newAssistantMessage];
                setMessages(currentMsgs);
                
                // 更新当前 assistant message ID
                currentAssistantMessageId = newAssistantMessageId;
                
                // 重置所有状态以开始新消息
                currentSegs = [];
                accText = '';
                accThinking = '';
                activeTextIdx = -1;
                activeThinkIdx = -1;
                currentEventType = null;
                toolCallMap = new Map<string, number>();
              }
              
            } else if (data.eventType === 'ToolConfirmEvent') {
              // 收到工具确认事件，暂停并等待用户确认
              currentEventType = null;
              accText = '';
              accThinking = '';
              activeTextIdx = -1;
              activeThinkIdx = -1;
              
              const pendingTools = data.pendingCallTools || [];
              
              // 为每个待确认的工具添加工具调用卡片
              for (const tool of pendingTools) {
                // 如果没有工具名称，跳过
                if (!tool.toolName) {
                  console.log('[ToolConfirmEvent] No toolName, skipping');
                  continue;
                }
                
                // 过滤掉计划相关的工具
                if (isPlanRelatedTool(tool.toolName)) {
                  console.log('[ToolConfirmEvent] Plan-related tool, skipping:', tool.toolName);
                  continue;
                }
                
                const toolSeg: MessageSegment = {
                  type: 'tool_call',
                  content: JSON.stringify(tool.arguments || {}, null, 2),
                  toolName: tool.toolName,
                  toolId: tool.toolId,
                  confirmStatus: 'pending' as const,
                };
                currentSegs.push(toolSeg);
                // 重要：将工具调用添加到 toolCallMap 中，以便 confirm 流中的 ToolResultEvent 能找到它
                toolCallMap.set(tool.toolId, currentSegs.length - 1);
                console.log('[ToolConfirmEvent] Added tool to map:', tool.toolId, 'at index:', currentSegs.length - 1);
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
                  ...getAuthHeaders(),
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
                        activeTextIdx = -1;
                        currentEventType = null;
                        continue;
                      }
                      
                      if (currentEventType !== 'text') {
                        accText = '';
                        activeTextIdx = -1;
                      }
                      currentEventType = 'text';
                      
                      accText += confirmData.message || '';
                      if (activeTextIdx < 0 || activeTextIdx >= currentSegs.length) {
                        currentSegs.push({ type: 'text', content: accText });
                        activeTextIdx = currentSegs.length - 1;
                      } else {
                        currentSegs[activeTextIdx].content = accText;
                      }
                      confirmChanged = true;
                      
                    } else if (confirmData.eventType === 'ThinkingEvent') {
                      if (confirmData.last === true) {
                        accThinking = '';
                        activeThinkIdx = -1;
                        currentEventType = null;
                        continue;
                      }
                      
                      if (currentEventType !== 'thinking') {
                        accThinking = '';
                        activeThinkIdx = -1;
                      }
                      currentEventType = 'thinking';
                      
                      accThinking += confirmData.message || '';
                      if (activeThinkIdx < 0 || activeThinkIdx >= currentSegs.length) {
                        currentSegs.push({ type: 'thinking', content: accThinking });
                        activeThinkIdx = currentSegs.length - 1;
                      } else {
                        currentSegs[activeThinkIdx].content = accThinking;
                      }
                      confirmChanged = true;
                      
                    } else if (confirmData.eventType === 'CallToolEvent') {
                      const confirmToolName = confirmData.toolName || '未知工具';
                      const confirmToolId = confirmData.toolId || `tool-${Date.now()}`;
                      
                      if (confirmToolName === 'create_plan' && enablePlan) {
                        setShowPlanPanel(true);
                        // 立即加载当前计划，后续由 useEffect 自动管理定时器
                        loadCurrentPlan();
                      }
                      
                      if (isPlanRelatedTool(confirmToolName)) {
                        // 计划相关工具不中断思考的连续性
                        confirmChanged = true;
                        continue;
                      }
                      
                      // 非计划工具调用，中断思考的连续性
                      currentEventType = null;
                      accText = '';
                      accThinking = '';
                      activeTextIdx = -1;
                      activeThinkIdx = -1;
                      
                      const toolSeg: MessageSegment = {
                        type: 'tool_call',
                        content: JSON.stringify(confirmData.arguments || {}, null, 2),
                        toolName: confirmToolName,
                        toolId: confirmToolId,
                      };
                      currentSegs.push(toolSeg);
                      toolCallMap.set(confirmToolId, currentSegs.length - 1);
                      confirmChanged = true;
                      
                    } else if (confirmData.eventType === 'ToolResultEvent') {
                      const toolId = confirmData.toolId || '';
                      const resultContent = confirmData.message || '';
                      const toolName = confirmData.toolName || '';
                      
                      // 计划相关工具的结果不中断思考的连续性
                      if (isPlanRelatedTool(toolName)) {
                        // 根据 toolId 找到对应的工具调用 segment 并更新（如果有）
                        if (toolId && toolCallMap.has(toolId)) {
                          const segIdx = toolCallMap.get(toolId)!;
                          currentSegs = currentSegs.map((seg, idx) => 
                            idx === segIdx && seg.type === 'tool_call'
                              ? { ...seg, toolResult: resultContent }
                              : seg
                          );
                        }
                        confirmChanged = true;
                        continue;
                      }
                      
                      // 非计划工具结果，中断思考的连续性
                      currentEventType = null;
                      accText = '';
                      accThinking = '';
                      activeTextIdx = -1;
                      activeThinkIdx = -1;
                      
                      if (toolId && toolCallMap.has(toolId)) {
                        const segIdx = toolCallMap.get(toolId)!;
                        currentSegs = currentSegs.map((seg, idx) => 
                          idx === segIdx && seg.type === 'tool_call'
                            ? { ...seg, toolResult: resultContent }
                            : seg
                        );
                      } else {
                        // 如果找不到对应的工具调用，不显示独立的工具结果
                        console.log('[Confirm ToolResultEvent] Tool not found, ignoring result');
                      }
                      confirmChanged = true;
                      
                      // 检查是否是 finish_subtask 或 finish_plan 工具
                      if (toolName === 'finish_subtask' || toolName === 'finish_plan') {
                        console.log('[Confirm ToolResultEvent] Detected finish tool, creating new assistant message');
                        
                        // 完成当前消息
                        flushUI();
                        
                        // 创建新的 assistant 消息
                        const newAssistantMessageId = `assistant-${Date.now()}-new`;
                        const newAssistantMessage: ChatMessage = {
                          id: newAssistantMessageId,
                          role: 'assistant',
                          segments: [],
                          timestamp: Date.now(),
                        };
                        
                        // 更新 currentMsgs
                        currentMsgs = [...currentMsgs, newAssistantMessage];
                        setMessages(currentMsgs);
                        
                        // 更新当前 assistant message ID
                        currentAssistantMessageId = newAssistantMessageId;
                        
                        // 重置所有状态以开始新消息
                        currentSegs = [];
                        accText = '';
                        accThinking = '';
                        activeTextIdx = -1;
                        activeThinkIdx = -1;
                        currentEventType = null;
                        toolCallMap = new Map<string, number>();
                      }
                    } else if (confirmData.eventType === 'ToolConfirmEvent') {
                      // 递归处理嵌套的工具确认
                      accText = '';
                      const nestedPendingTools = confirmData.pendingCallTools || [];
                      
                      // 为每个待确认的工具添加工具调用卡片（与正常工具调用一致）
                      for (const tool of nestedPendingTools) {
                        // 如果没有工具名称，跳过
                        if (!tool.toolName) {
                          console.log('[Nested ToolConfirmEvent] No toolName, skipping');
                          continue;
                        }
                        
                        // 过滤掉计划相关的工具
                        if (isPlanRelatedTool(tool.toolName)) {
                          console.log('[Nested ToolConfirmEvent] Plan-related tool, skipping:', tool.toolName);
                          continue;
                        }
                        
                        const toolSeg: MessageSegment = {
                          type: 'tool_call',
                          content: JSON.stringify(tool.arguments || {}, null, 2),
                          toolName: tool.toolName,
                          toolId: tool.toolId,
                          confirmStatus: 'pending' as const,
                        };
                        currentSegs.push(toolSeg);
                        // 重要：将工具调用添加到 toolCallMap 中
                        toolCallMap.set(tool.toolId, currentSegs.length - 1);
                        console.log('[Nested ToolConfirmEvent] Added tool to map:', tool.toolId, 'at index:', currentSegs.length - 1);
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
                          ...getAuthHeaders(),
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
                                activeTextIdx = -1;
                                currentEventType = null;
                                continue;
                              }
                              
                              if (currentEventType !== 'text') {
                                accText = '';
                                activeTextIdx = -1;
                              }
                              currentEventType = 'text';
                              
                              accText += nestedData.message || '';
                              if (activeTextIdx < 0 || activeTextIdx >= currentSegs.length) {
                                currentSegs.push({ type: 'text', content: accText });
                                activeTextIdx = currentSegs.length - 1;
                              } else {
                                currentSegs[activeTextIdx].content = accText;
                              }
                              confirmChanged = true;
                              
                            } else if (nestedData.eventType === 'ThinkingEvent') {
                              if (nestedData.last === true) {
                                accThinking = '';
                                activeThinkIdx = -1;
                                currentEventType = null;
                                continue;
                              }
                              
                              if (currentEventType !== 'thinking') {
                                accThinking = '';
                                activeThinkIdx = -1;
                              }
                              currentEventType = 'thinking';
                              
                              accThinking += nestedData.message || '';
                              if (activeThinkIdx < 0 || activeThinkIdx >= currentSegs.length) {
                                currentSegs.push({ type: 'thinking', content: accThinking });
                                activeThinkIdx = currentSegs.length - 1;
                              } else {
                                currentSegs[activeThinkIdx].content = accThinking;
                              }
                              confirmChanged = true;
                              
                            } else if (nestedData.eventType === 'CallToolEvent') {
                              const nestedToolName = nestedData.toolName || '未知工具';
                              const nestedToolId = nestedData.toolId || `tool-${Date.now()}`;
                              
                              if (nestedToolName === 'create_plan' && enablePlan) {
                                setShowPlanPanel(true);
                                // 立即加载当前计划，后续由 useEffect 自动管理定时器
                                loadCurrentPlan();
                              }
                              
                              if (isPlanRelatedTool(nestedToolName)) {
                                // 计划相关工具不中断思考的连续性
                                confirmChanged = true;
                                continue;
                              }
                              
                              // 非计划工具调用，中断思考的连续性
                              currentEventType = null;
                              accText = '';
                              accThinking = '';
                              activeTextIdx = -1;
                              activeThinkIdx = -1;
                              
                              const toolSeg: MessageSegment = {
                                type: 'tool_call',
                                content: JSON.stringify(nestedData.arguments || {}, null, 2),
                                toolName: nestedToolName,
                                toolId: nestedToolId,
                              };
                              currentSegs.push(toolSeg);
                              toolCallMap.set(nestedToolId, currentSegs.length - 1);
                              confirmChanged = true;
                              
                            } else if (nestedData.eventType === 'ToolResultEvent') {
                              const toolId = nestedData.toolId || '';
                              const resultContent = nestedData.message || '';
                              const toolName = nestedData.toolName || '';
                              
                              // 计划相关工具的结果不中断思考的连续性
                              if (isPlanRelatedTool(toolName)) {
                                // 根据 toolId 找到对应的工具调用 segment 并更新（如果有）
                                if (toolId && toolCallMap.has(toolId)) {
                                  const segIdx = toolCallMap.get(toolId)!;
                                  currentSegs = currentSegs.map((seg, idx) => 
                                    idx === segIdx && seg.type === 'tool_call'
                                      ? { ...seg, toolResult: resultContent }
                                      : seg
                                  );
                                }
                                confirmChanged = true;
                                continue;
                              }
                              
                              // 非计划工具结果，中断思考的连续性
                              currentEventType = null;
                              accText = '';
                              accThinking = '';
                              activeTextIdx = -1;
                              activeThinkIdx = -1;
                              
                              if (toolId && toolCallMap.has(toolId)) {
                                const segIdx = toolCallMap.get(toolId)!;
                                currentSegs = currentSegs.map((seg, idx) => 
                                  idx === segIdx && seg.type === 'tool_call'
                                    ? { ...seg, toolResult: resultContent }
                                    : seg
                                );
                              } else {
                                currentSegs.push({ type: 'tool_result', content: resultContent });
                              }
                              confirmChanged = true;
                              
                              // 检查是否是 finish_subtask 或 finish_plan 工具
                              if (toolName === 'finish_subtask' || toolName === 'finish_plan') {
                                console.log('[Nested ToolResultEvent] Detected finish tool, creating new assistant message');
                                
                                // 完成当前消息
                                flushUI();
                                
                                // 创建新的 assistant 消息
                                const newAssistantMessageId = `assistant-${Date.now()}-new`;
                                const newAssistantMessage: ChatMessage = {
                                  id: newAssistantMessageId,
                                  role: 'assistant',
                                  segments: [],
                                  timestamp: Date.now(),
                                };
                                
                                // 更新 currentMsgs
                                currentMsgs = [...currentMsgs, newAssistantMessage];
                                setMessages(currentMsgs);
                                
                                // 更新当前 assistant message ID
                                currentAssistantMessageId = newAssistantMessageId;
                                
                                // 重置所有状态以开始新消息
                                currentSegs = [];
                                accText = '';
                                accThinking = '';
                                activeTextIdx = -1;
                                activeThinkIdx = -1;
                                currentEventType = null;
                                toolCallMap = new Map<string, number>();
                              }
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
      
      // 如果启用了计划功能且卡片处于展开状态，立即刷新一次
      if (enablePlan && currentPlanExpanded) {
        loadCurrentPlan();
      }
    }
  };

  const handleSend = () => doSend(inputValue);

  const handleKeyPress = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      // loading 时不允许发送
      if (loading) return;
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
      const response = await fetch(`/ai/session/${sessionId}/current-plan`, {
        headers: {
          ...getAuthHeaders(),
        },
      });
      
      if (!response.ok) {
        return; // 静默失败，不显示错误
      }
      
      const result = await response.json();
      console.log('Current plan API response:', result);
      
      let hasValidPlan = false;
      let planData: any = null;
      
      if (result.code === 200 && result.data) {
        // 后端直接返回 PlanNote 对象
        planData = result.data;
        console.log('Plan data extracted:', planData);
        
        // 只有当 planData 存在且包含有效字段时才设置
        if (planData && (planData.name || planData.subtasks)) {
          hasValidPlan = true;
          console.log('Current plan is valid');
        } else {
          // 没有计划数据
          planData = null;
          hasValidPlan = false;
          console.log('No current plan data');
        }
      } else {
        // 接口返回错误或无数据
        planData = null;
        hasValidPlan = false;
      }
      
      // 更新消息流中的计划卡片
      if (hasValidPlan) {
        // 有计划数据
        if (!currentPlanMessageIdRef.current) {
          // 第一次检测到计划，创建新的计划消息卡片
          // 使用固定的 ID 格式，避免重复创建
          const planMessageId = `plan-card-${sessionId}`;
          currentPlanMessageIdRef.current = planMessageId;
          
          // 检查是否已经存在计划卡片，避免重复添加
          setMessages(prev => {
            // 检查是否已经存在该计划卡片
            const existingPlanMsg = prev.find(msg => msg.id === planMessageId);
            if (existingPlanMsg) {
              // 如果已存在，只更新 planData
              return prev.map(msg => 
                msg.id === planMessageId ? {
                  ...msg,
                  segments: msg.segments.map(seg => 
                    seg.type === 'plan_card' ? { ...seg, planData } : seg
                  )
                } : msg
              );
            } else {
              // 如果不存在，创建新的计划卡片
              return [...prev, {
                id: planMessageId,
                role: 'assistant',
                segments: [{
                  type: 'plan_card',
                  content: '',
                  planData: planData
                }],
                timestamp: Date.now()
              }];
            }
          });
          console.log('Created or updated plan card message:', planMessageId);
        } else {
          // 更新现有计划消息的 planData
          setMessages(prev => prev.map(msg => {
            if (msg.id === currentPlanMessageIdRef.current) {
              return {
                ...msg,
                segments: msg.segments.map(seg => 
                  seg.type === 'plan_card' ? { ...seg, planData } : seg
                )
              };
            }
            return msg;
          }));
          console.log('Updated existing plan card message');
        }
        
        // 更新 currentPlan 状态（用于控制定时器）
        setCurrentPlan({ activePlan: planData });
      } else {
        // 没有计划数据
        if (currentPlanMessageIdRef.current) {
          // 计划已结束，保留卡片但停止更新
          console.log('Plan ended, keeping card but stopping updates');
          // 不清除 currentPlanMessageIdRef，保留卡片
          // 停止 currentPlan 以触发定时器清除
          setCurrentPlan(null);
        }
      }
      
      // 检测计划从有变为无的情况
      if (previousHasPlanRef.current && !hasValidPlan) {
        console.log('Plan changed from has to none, refreshing history plans');
        // 当前计划从有变为无，增量刷新历史计划
        loadPlans(true);
      }
      
      // 更新上一次的计划状态
      previousHasPlanRef.current = hasValidPlan;
    } catch (error) {
      // 静默失败，不影响用户体验
      console.error('加载当前计划失败:', error);
    }
  };

  /* ─── 加载计划列表 ─── */
  const loadPlans = async (isIncremental = false) => {
    if (!sessionId) return;
    
    try {
      setLoadingPlans(true);
      const response = await fetch(`/ai/session/${sessionId}/plans`, {
        headers: {
          ...getAuthHeaders(),
        },
      });
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const result = await response.json();
      console.log('Plans API response:', result);
      
      if (result.code === 200 && result.data) {
        const newPlans = result.data;
        
        if (isIncremental && plans.length > 0) {
          // 增量刷新：只添加新计划
          const existingPlanIds = new Set(plans.map(p => p.planId));
          const incrementalPlans = newPlans.filter(p => !existingPlanIds.has(p.planId));
          
          if (incrementalPlans.length > 0) {
            // 将新计划添加到列表前面（最新的在前）
            setPlans(prev => [...incrementalPlans, ...prev]);
            console.log(`Incremental update: added ${incrementalPlans.length} new plan(s)`);
          } else {
            console.log('No new plans to add');
          }
        } else {
          // 全量加载：替换所有计划
          setPlans(newPlans);
          console.log('Full load:', newPlans.length, 'plans');
        }
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
      // 打开面板时全量加载历史计划
      loadPlans(false);
    } else {
      // 关闭面板时清除所有定时器
      if (plansListTimerRef.current) {
        clearInterval(plansListTimerRef.current);
        plansListTimerRef.current = null;
      }
      if (planRefreshTimerRef.current) {
        clearInterval(planRefreshTimerRef.current);
        planRefreshTimerRef.current = null;
      }
    }
  };

  /* ─── 手动刷新历史计划 ─── */
  const handleRefreshPlans = async () => {
    // 手动刷新使用增量模式
    await loadPlans(true);
  };

  /* ─── 清空聊天记录 ─── */
  const handleClearChat = async () => {
    try {
      const response = await fetch(`/ai/session/${sessionId}`, {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders(),
        },
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      // 清空前端聊天记录
      setMessages([]);
      setPlans([]);
      setCurrentPlan(null);
      currentPlanMessageIdRef.current = null; // 重置计划消息ID
      previousHasPlanRef.current = false; // 重置计划状态
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
        // 如果隐藏思考过程，不渲染
        if (!showThinking) return null;
        return <ThinkingBlock key={idx} content={seg.content} />;
      case 'tool_call':
        // 如果工具名称为空，不渲染
        if (!seg.toolName) return null;
        return (
          <MergedToolCard
            key={idx}
            toolName={seg.toolName || ''}
            arguments={seg.content}
            result={seg.toolResult}
            confirmStatus={seg.confirmStatus}
          />
        );
      case 'tool_result':
        // 不显示独立的工具返回卡片（已合并到工具调用卡片中）
        return null;
      case 'tool_confirm':
        const status = (seg.content as 'pending' | 'confirmed' | 'rejected') || 'pending';
        return (
          <ToolConfirmChatCard
            key={idx}
            pendingCallTools={seg.pendingCallTools || []}
            status={status}
          />
        );
      case 'plan_card':
        // 渲染计划卡片
        if (!seg.planData || !seg.planData.name) return null;
        
        const plan = seg.planData;
        return (
          <div key={idx} className={styles.currentPlanCard}>
            <div 
              className={styles.currentPlanHeader}
              onClick={() => setCurrentPlanExpanded(!currentPlanExpanded)}
            >
              <div className={styles.currentPlanTitle}>
                <CheckSquareOutlined style={{ marginRight: 6, fontSize: 14, color: '#6366f1' }} />
                <span style={{ fontWeight: 500 }}>{plan.name}</span>
                {plan.subtasks && plan.subtasks.length > 0 && (
                  <span className={styles.planProgress}>
                    {plan.subtasks.filter((t: any) => t.state === 'DONE').length}/{plan.subtasks.length}
                  </span>
                )}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {currentPlanExpanded && (
                  <div className={styles.refreshIndicator}>
                    <ClockCircleOutlined spin style={{ fontSize: 12 }} />
                    <span>实时</span>
                  </div>
                )}
                <div className={styles.collapseIcon}>
                  {currentPlanExpanded ? <DownOutlined /> : <RightOutlined />}
                </div>
              </div>
            </div>
            
            {currentPlanExpanded && (
              <div className={styles.currentPlanContent}>
                {plan.description && (
                  <p className={styles.planDesc}>{plan.description}</p>
                )}
                {plan.subtasks && plan.subtasks.length > 0 && (
                  <div className={styles.currentSubtasks}>
                    {plan.subtasks.map((subtask: any, subIdx: number) => (
                      <div key={subIdx} className={styles.subtaskItem}>
                        <div className={styles.subtaskIcon}>
                          {subtask.state === 'DONE' && <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 14 }} />}
                          {subtask.state === 'IN_PROGRESS' && <ClockCircleOutlined spin style={{ color: '#1890ff', fontSize: 14 }} />}
                          {subtask.state === 'TODO' && <div className={styles.todoIcon} />}
                          {subtask.state === 'ABANDONED' && <CloseOutlined style={{ color: '#ff4d4f', fontSize: 14 }} />}
                        </div>
                        <div className={styles.subtaskInfo}>
                          <div className={styles.subtaskName}>{subtask.name}</div>
                          {subtask.state === 'IN_PROGRESS' && (
                            <div className={styles.subtaskStatus}>执行中...</div>
                          )}
                          {subtask.state === 'ABANDONED' && (
                            <div className={styles.subtaskStatus} style={{ color: '#ff4d4f' }}>已放弃</div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
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

  /* ─── 判断是否为计划相关的工具 ─── */
  const isPlanRelatedTool = (toolName: string): boolean => {
    const planTools = [
      'create_plan',
      'update_plan_info',
      'revise_current_plan',
      'update_subtask_state',
      'finish_subtask',
      'view_subtasks',
      'get_subtask_count',
      'finish_plan',
      'view_historical_plans',
      'recover_historical_plan',
    ];
    return planTools.includes(toolName);
  };

  /* ─── 判断当前计划是否有效 ─── */
  const hasValidCurrentPlan = () => {
    if (!currentPlan || !currentPlan.activePlan) return false;
    const plan = currentPlan.activePlan;
    // 检查是否有有效数据（名称或子任务）
    return !!(plan.name || (plan.subtasks && plan.subtasks.length > 0));
  };

  /* ─── 渲染当前计划卡片 ─── */
  const renderCurrentPlanCard = () => {
    if (!currentPlan || !currentPlan.activePlan) return null;

    const plan = currentPlan.activePlan;
    
    return (
      <div className={styles.currentPlanCard}>
        <div 
          className={styles.currentPlanHeader}
          onClick={() => setCurrentPlanExpanded(!currentPlanExpanded)}
        >
          <div className={styles.currentPlanTitle}>
            <CheckSquareOutlined style={{ marginRight: 6, fontSize: 14, color: '#6366f1' }} />
            <span style={{ fontWeight: 500 }}>{plan.name}</span>
            {plan.subtasks && plan.subtasks.length > 0 && (
              <span className={styles.planProgress}>
                {plan.subtasks.filter((t: any) => t.state === 'DONE').length}/{plan.subtasks.length}
              </span>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {currentPlanExpanded && (
              <div className={styles.refreshIndicator}>
                <ClockCircleOutlined spin style={{ fontSize: 12 }} />
                <span>实时</span>
              </div>
            )}
            <div className={styles.collapseIcon}>
              {currentPlanExpanded ? <DownOutlined /> : <RightOutlined />}
            </div>
          </div>
        </div>
        
        {currentPlanExpanded && (
          <div className={styles.currentPlanContent}>
            {plan.description && (
              <p className={styles.planDesc}>{plan.description}</p>
            )}
            {plan.subtasks && plan.subtasks.length > 0 && (
              <div className={styles.currentSubtasks}>
                {plan.subtasks.map((subtask: any, idx: number) => (
                  <div key={idx} className={styles.subtaskItem}>
                    <div className={styles.subtaskIcon}>
                      {subtask.state === 'DONE' && <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 14 }} />}
                      {subtask.state === 'IN_PROGRESS' && <ClockCircleOutlined spin style={{ color: '#1890ff', fontSize: 14 }} />}
                      {subtask.state === 'TODO' && <div className={styles.todoIcon} />}
                      {subtask.state === 'ABANDONED' && <CloseOutlined style={{ color: '#ff4d4f', fontSize: 14 }} />}
                    </div>
                    <div className={styles.subtaskInfo}>
                      <div className={styles.subtaskName}>{subtask.name}</div>
                      {subtask.state === 'IN_PROGRESS' && (
                        <div className={styles.subtaskStatus}>执行中...</div>
                      )}
                      {subtask.state === 'ABANDONED' && (
                        <div className={styles.subtaskStatus} style={{ color: '#ff4d4f' }}>已放弃</div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    );
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
          {/* 当前计划 */}
          <div className={styles.currentPlanSection}>
            <div className={styles.currentPlanHeader}>
              <h4>当前计划</h4>
              {hasValidCurrentPlan() && (
                <div className={styles.refreshIndicator}>
                  <ClockCircleOutlined spin />
                  <span>实时更新</span>
                </div>
              )}
            </div>
            
            {hasValidCurrentPlan() ? (
              <div className={styles.activePlanCard}>
                {/* 计划头部 */}
                <div className={styles.planCardHeader}>
                  <div className={styles.planTitleSection}>
                    <div className={styles.planIcon}>
                      <CheckSquareOutlined />
                    </div>
                    <div className={styles.planTitleInfo}>
                      <h3 className={styles.planTitle}>{currentPlan.activePlan.name}</h3>
                      {currentPlan.activePlan.description && (
                        <p className={styles.planDesc}>{currentPlan.activePlan.description}</p>
                      )}
                    </div>
                  </div>
                  <div className={styles.planProgressBadge}>
                    <span className={styles.progressText}>
                      {currentPlan.activePlan.subtasks.filter((t: any) => t.state === 'DONE').length}
                    </span>
                    <span className={styles.progressDivider}>/</span>
                    <span className={styles.progressTotal}>
                      {currentPlan.activePlan.subtasks.length}
                    </span>
                  </div>
                </div>
                
                {/* 子任务列表 */}
                {currentPlan.activePlan.subtasks && currentPlan.activePlan.subtasks.length > 0 && (
                  <div className={styles.currentSubtasks}>
                    {currentPlan.activePlan.subtasks.map((subtask: any, idx: number) => (
                      <div key={idx} className={`${styles.subtaskItem} ${styles[`subtask${subtask.state}`]}`}>
                        <div className={styles.subtaskIconWrapper}>
                          {subtask.state === 'DONE' && (
                            <div className={`${styles.subtaskIcon} ${styles.iconDone}`}>
                              <CheckOutlined />
                            </div>
                          )}
                          {subtask.state === 'IN_PROGRESS' && (
                            <div className={`${styles.subtaskIcon} ${styles.iconProgress}`}>
                              <ClockCircleOutlined spin />
                            </div>
                          )}
                          {subtask.state === 'TODO' && (
                            <div className={`${styles.subtaskIcon} ${styles.iconTodo}`} />
                          )}
                          {subtask.state === 'ABANDONED' && (
                            <div className={`${styles.subtaskIcon} ${styles.iconAbandoned}`}>
                              <CloseOutlined />
                            </div>
                          )}
                        </div>
                        <div className={styles.subtaskInfo}>
                          <div className={styles.subtaskName}>{subtask.name}</div>
                          {subtask.state === 'IN_PROGRESS' && (
                            <div className={styles.subtaskStatus}>执行中...</div>
                          )}
                          {subtask.state === 'ABANDONED' && (
                            <div className={styles.subtaskStatus}>已放弃</div>
                          )}
                        </div>
                        {subtask.costTimeSeconds > 0 && (
                          <div className={styles.subtaskTime}>
                            <ClockCircleOutlined />
                            <span>{subtask.costTimeSeconds}s</span>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              <div className={styles.noCurrentPlan}>
                <UnorderedListOutlined style={{ fontSize: 32, color: '#d9d9d9' }} />
                <p>无当前计划</p>
                <span>开启"开启计划"选项后，AI 会自动创建计划</span>
              </div>
            )}
          </div>

          {/* 历史计划 */}
          {loadingPlans && plans.length === 0 && (
            <div className={styles.loadingPlans}>
              <p>加载计划中...</p>
            </div>
          )}
          
          {!loadingPlans && plans.length === 0 && !currentPlan && (
            <div className={styles.planEmpty}>
              <ClockCircleOutlined style={{ fontSize: 32, color: '#d9d9d9' }} />
              <p>暂无计划</p>
              <span>开启"开启计划"选项后，AI 会自动创建计划</span>
            </div>
          )}
          
          {!loadingPlans && plans.length > 0 && (
            <div className={styles.historyPlansSection}>
              <div className={styles.historyPlansHeader}>
                <div className={styles.historyPlansTitle}>
                  <h4>历史计划</h4>
                  <span className={styles.planCount}>{plans.length}</span>
                </div>
                <Button
                  className={styles.refreshPlanButton}
                  type="text"
                  icon={<ReloadOutlined />}
                  onClick={handleRefreshPlans}
                  loading={loadingPlans}
                  size="small"
                >
                  <span className={styles.refreshButtonText}>刷新</span>
                </Button>
              </div>
              <Collapse accordion defaultActiveKey={[plans[0]?.planId]}>
                {plans.map((plan) => (
                  <Collapse.Panel
                    key={plan.planId}
                    header={
                      <div className={styles.planHeader}>
                        <span className={styles.planName}>{plan.name}</span>
                        {plan.status && (
                          <Tag 
                            color={
                              plan.status === 'DONE' ? 'success' :
                              plan.status === 'IN_PROGRESS' ? 'processing' :
                              plan.status === 'ABANDONED' ? 'error' :
                              'default'
                            } 
                            style={{ marginLeft: 8 }}
                          >
                            {plan.status === 'DONE' ? '已完成' :
                             plan.status === 'IN_PROGRESS' ? '执行中' :
                             plan.status === 'ABANDONED' ? '已废弃' :
                             '待执行'}
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
        </div>
      </div>
    );
  };

  return (
    <div className={styles.chatWindow}>
      {/* ─── 历史计划面板 ─── */}
      {renderPlanPanel()}
      
      {/* ─── 历史计划切换按钮（右侧边缘） ─── */}
      {enablePlan && (
        <div 
          className={`${styles.historyPlanToggleButton} ${showPlanPanel ? styles.historyPlanToggleButtonActive : ''}`}
          onClick={handleTogglePlanPanel}
        >
          {showPlanPanel ? <RightOutlined /> : <LeftOutlined />}
        </div>
      )}
      
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
                    <>
                      {msg.segments.map((seg, idx) => renderSegment(seg, idx))}
                      {/* 如果是最后一条消息且正在 loading，显示加载动画 */}
                      {loading && msg.id === messages[messages.length - 1]?.id && (
                        <div className={styles.inlineLoading}>
                          <span className={styles.loadingDot} />
                          <span className={styles.loadingDot} />
                          <span className={styles.loadingDot} />
                        </div>
                      )}
                    </>
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
              placeholder={loading ? "AI 正在输出中，请稍候..." : "输入消息... (Enter 发送，Shift + Enter 换行)"}
              autoSize={{ minRows: 2, maxRows: 6 }}
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
              <Dropdown
                menu={{
                  items: [
                    {
                      key: 'show',
                      label: (
                        <span className={styles.menuItemLabel}>
                          <EyeOutlined className={styles.menuIcon} />
                          <span>显示思考过程</span>
                          {showThinking && <CheckOutlined className={styles.menuCheck} />}
                        </span>
                      ),
                      disabled: showThinking,
                    },
                    {
                      key: 'hide',
                      label: (
                        <span className={styles.menuItemLabel}>
                          <EyeInvisibleOutlined className={styles.menuIcon} />
                          <span>隐藏思考过程</span>
                          {!showThinking && <CheckOutlined className={styles.menuCheck} />}
                        </span>
                      ),
                      disabled: !showThinking,
                    },
                  ],
                  onClick: ({ key }) => {
                    if (key === 'show') {
                      setShowThinking(true);
                    } else if (key === 'hide') {
                      setShowThinking(false);
                    }
                  },
                }}
                trigger={['contextMenu']}
                dropdownRender={(menu) => (
                  <div className={styles.thinkingMenu}>
                    <div className={styles.menuHeader}>
                      <BulbOutlined className={styles.menuHeaderIcon} />
                      <span>思考过程显示设置</span>
                    </div>
                    {menu}
                  </div>
                )}
              >
                <div
                  className={`${styles.optionItem} ${enableThink ? styles.optionActive : ''}`}
                  onClick={() => setEnableThink(!enableThink)}
                >
                  <BulbOutlined />
                  <span>深度思考</span>
                </div>
              </Dropdown>
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
              disabled={!inputValue.trim() && imageUrls.length === 0 || loading}
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
