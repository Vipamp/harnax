import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useIntl } from '@umijs/max';
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
  ThunderboltOutlined,
  StopOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { getSessionMessages, getSessionConfig } from '@/services/ant-design-pro/chat';
import { getWorkspaceStatus } from '@/services/ant-design-pro/workspace';
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
  costTimeSeconds: number;
  status: string; // TODO, IN_PROGRESS, DONE, ABANDONED
}

/* ─── 快捷建议 ─── */

/* ─── 辅助函数：获取 JWT Token（与 requestInterceptors 逻辑一致） ─── */
const getAuthHeaders = (): Record<string, string> => {
  try {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    if (tokenInfoStr) {
      const tokenInfo = JSON.parse(tokenInfoStr);
      if (tokenInfo.accessToken) {
        return { 'Authorization': `Bearer ${tokenInfo.accessToken}` };
      }
    }
  } catch (e) {
    console.error(`[ChatWindow] Failed to get token:`, e);
  }
  return {};
};

/* ─── 辅助函数：获取 Router 认证头（优先 X-Api-Key，回退 JWT） ─── */
const getRouterHeaders = (): Record<string, string> => {
  try {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    if (tokenInfoStr) {
      const tokenInfo = JSON.parse(tokenInfoStr);
      if (tokenInfo.routerApiKey) {
        return { 'X-Api-Key': tokenInfo.routerApiKey };
      }
    }
  } catch { /* ignore */ }
  return getAuthHeaders();
};

/* ─── 代码块（带复制 + 语言标签） ─── */
const CodeBlock: React.FC<{ className?: string; children?: React.ReactNode }> = ({
  className,
  children,
}) => {
  const intl = useIntl();
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
          {copied ? intl.formatMessage({ id: 'pages.common.copied', defaultMessage: 'Copied' }) : intl.formatMessage({ id: 'pages.common.copy', defaultMessage: 'Copy' })}
        </button>
      </div>
      <SyntaxHighlighter
        style={oneLight}
        language={match[1]}
        PreTag="div"
        customStyle={{ margin: 0, padding: '14px 16px', fontSize: 13, background: 'var(--vip-bg-elevated)' }}
      >
        {code}
      </SyntaxHighlighter>
    </div>
  );
};

/* ─── 思考折叠 ─── */
const ThinkingBlock: React.FC<{ content: string }> = ({ content }) => {
  const intl = useIntl();
  const [expanded, setExpanded] = useState(true); // 默认展开
  return (
    <div className={styles.thinkingBlock}>
      <div className={styles.thinkingToggle} onClick={() => setExpanded(!expanded)}>
        <BulbOutlined />
        <span>{intl?.formatMessage({ id: 'pages.session.thinkingProcess', defaultMessage: 'Thinking Process' })}</span>
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

/* ─── 参数表格：将 JSON 参数显示为键值对表格 ─── */
const ArgumentsTable: React.FC<{ args: Record<string, any> | string }> = ({ args }) => {
  let parsed: Record<string, any>;
  try {
    if (typeof args === 'string') {
      parsed = JSON.parse(args);
    } else {
      parsed = args || {};
    }
  } catch {
    return <pre style={{ margin: 0, fontSize: 12 }}>{typeof args === 'string' ? args : JSON.stringify(args, null, 2)}</pre>;
  }

  const entries = Object.entries(parsed);
  if (entries.length === 0) return <span style={{ color: 'var(--vip-text-tertiary)', fontSize: 12 }}>—</span>;

  const formatValue = (val: any): React.ReactNode => {
    if (val === null || val === undefined) {
      return <span style={{ color: 'var(--vip-text-tertiary)' }}>null</span>;
    }
    if (typeof val === 'boolean') {
      return <Tag color={val ? 'green' : 'default'}>{val ? 'true' : 'false'}</Tag>;
    }
    if (typeof val === 'number') {
      return <span style={{ fontFamily: 'monospace' }}>{String(val)}</span>;
    }
    if (typeof val === 'string') {
      if (val.length > 200) {
        return (
          <span>
            <span style={{ fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{val.slice(0, 200)}…</span>
            <Collapse ghost size="small" items={[{ key: '1', label: <span style={{ fontSize: 11 }}>Show full</span>, children: <pre style={{ margin: 0, fontSize: 11, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 300, overflow: 'auto' }}>{val}</pre> }]} />
          </span>
        );
      }
      return <span style={{ fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{val}</span>;
    }
    // object or array
    const jsonStr = JSON.stringify(val, null, 2);
    if (jsonStr.length > 300) {
      return (
        <Collapse ghost size="small" items={[{ key: '1', label: <span style={{ fontSize: 11, fontFamily: 'monospace' }}>{jsonStr.slice(0, 80)}…</span>, children: <pre style={{ margin: 0, fontSize: 11, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 300, overflow: 'auto' }}>{jsonStr}</pre> }]} />
      );
    }
    return <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{jsonStr}</pre>;
  };

  return (
    <div className={styles.argsTable}>
      {entries.map(([key, val]) => (
        <div key={key} className={styles.argsRow}>
          <span className={styles.argsKey}>{key}</span>
          <span className={styles.argsValue}>{formatValue(val)}</span>
        </div>
      ))}
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
  const intl = useIntl();
  const [expanded, setExpanded] = useState(false); // 默认折叠
  const hasResult = result !== undefined;
  
  // 根据确认状态显示不同的文本和颜色
  const statusConfig = {
    pending: { text: intl.formatMessage({ id: 'pages.session.confirmStatus.pending', defaultMessage: 'Pending confirmation' }), color: 'var(--vip-warning)', icon: <ExclamationCircleOutlined /> },
    confirmed: { text: intl.formatMessage({ id: 'pages.session.confirmStatus.confirmed', defaultMessage: 'Confirmed' }), color: 'var(--vip-success)', icon: <CheckCircleOutlined /> },
    rejected: { text: intl.formatMessage({ id: 'pages.session.confirmStatus.rejected', defaultMessage: 'Rejected' }), color: 'var(--vip-error)', icon: <CloseOutlined /> },
    calling: { text: intl.formatMessage({ id: 'pages.session.toolStatus.calling', defaultMessage: 'Calling' }), color: 'var(--vip-primary)', icon: <ClockCircleOutlined spin /> },
    completed: { text: intl.formatMessage({ id: 'pages.session.toolStatus.completed', defaultMessage: 'Completed' }), color: 'var(--vip-success)', icon: <CheckCircleOutlined /> },
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
              <div className={styles.mergedToolSectionLabel}>{intl.formatMessage({ id: 'pages.session.toolArguments', defaultMessage: 'Arguments' })}</div>
              <ArgumentsTable args={args} />
            </div>
          )}
          
          {/* 工具返回结果 */}
          {hasResult && (
            <div className={styles.mergedToolResult}>
              <div className={styles.mergedToolSectionLabel}>{intl.formatMessage({ id: 'pages.session.toolResult', defaultMessage: 'Result' })}</div>
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
  const intl = useIntl();
  const [expanded, setExpanded] = useState(false);
  
  // 根据确认状态显示不同的文本
  const statusText = confirmStatus === 'confirmed' 
    ? intl.formatMessage({ id: 'pages.session.allowed', defaultMessage: '(Allowed)' }) 
    : confirmStatus === 'rejected' 
    ? `(${intl.formatMessage({ id: 'pages.session.rejected', defaultMessage: 'Rejected' })})` 
    : confirmStatus === 'pending' 
    ? intl.formatMessage({ id: 'pages.session.pendingConfirm', defaultMessage: '(Pending Confirmation)' }) 
    : '';
  
  return (
    <div style={{ display: 'block', width: '100%' }}>
      <div className={styles.toolCard}>
        <div className={styles.toolCardHeader} onClick={() => setExpanded(!expanded)}>
          <ToolOutlined /> {intl.formatMessage({ id: 'pages.session.callTool', defaultMessage: 'Call Tool:' })} {toolName}{statusText}
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
  const intl = useIntl();
  const [expanded, setExpanded] = useState(false);
  
  const statusText = status === 'confirmed' ? intl.formatMessage({ id: 'pages.session.confirmStatus.confirmed', defaultMessage: 'Confirmed' }) : status === 'rejected' ? intl.formatMessage({ id: 'pages.session.confirmStatus.rejected', defaultMessage: 'Rejected' }) : intl.formatMessage({ id: 'pages.session.confirmStatus.pending', defaultMessage: 'Pending' });
  const statusColor = status === 'confirmed' ? 'green' : status === 'rejected' ? 'red' : 'orange';
  
  return (
    <div className={styles.toolConfirmCard}>
      <div className={styles.toolConfirmCardHeader} onClick={() => setExpanded(!expanded)}>
        <ExclamationCircleOutlined style={{ color: 'var(--vip-warning)' }} />
        <span>{intl.formatMessage({ id: 'pages.session.toolConfirmTitle', defaultMessage: 'Tool Execution Confirmation' })}</span>
        <Tag color={statusColor}>{statusText}</Tag>
        {expanded ? <DownOutlined style={{ fontSize: 10, marginLeft: 4 }} /> : <RightOutlined style={{ fontSize: 10, marginLeft: 4 }} />}
      </div>
      {expanded && (
        <div className={styles.toolConfirmCardBody}>
          <div style={{ marginBottom: 8, fontSize: 13, color: 'var(--vip-text-secondary)' }}>
            {intl.formatMessage({ id: 'pages.session.toolConfirmDescriptionShort', defaultMessage: "AI wants to call the following tools:" })}
          </div>
          {pendingCallTools.map((tool) => (
            <div key={tool.toolId} style={{ marginBottom: 8, padding: 8, background: 'var(--vip-bg-elevated)', borderRadius: 4 }}>
              <div style={{ fontWeight: 500, marginBottom: 4 }}>
                <ToolOutlined /> {tool.toolName}
                {tool.isDangerous && <Tag color="red" style={{ marginLeft: 8 }}>{intl.formatMessage({ id: 'pages.session.highRisk', defaultMessage: 'High Risk' })}</Tag>}
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

  const intl = useIntl();
  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.session.toolName', defaultMessage: 'Tool Name' }),
      dataIndex: 'toolName',
      key: 'toolName',
      render: (name: string) => (
        <span style={{ fontWeight: 600, fontFamily: 'monospace' }}>{name}</span>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.session.toolRiskLevel', defaultMessage: 'Risk Level' }),
      dataIndex: 'isDangerous',
      key: 'isDangerous',
      width: 100,
      render: (isDangerous: boolean) => (
        <Tag color={isDangerous ? 'red' : 'green'}>
          {isDangerous ? intl.formatMessage({ id: 'pages.session.highRisk', defaultMessage: 'High Risk' }) : intl.formatMessage({ id: 'pages.session.lowRisk', defaultMessage: 'Low Risk' })}
        </Tag>
      ),
    },
  ];

  return (
    <Modal
      title={
        <span>
          <ExclamationCircleOutlined style={{ color: '#faad14', marginRight: 8 }} />
          {intl.formatMessage({ id: 'pages.session.toolConfirmTitle', defaultMessage: 'Tool Execution Confirmation' })}
        </span>
      }
      open={modalVisible}
      onCancel={handleReject}
      footer={
        <Space>
          <Button danger onClick={handleReject}>
            {intl.formatMessage({ id: 'pages.common.reject', defaultMessage: 'Reject' })}
          </Button>
          <Button type="primary" onClick={handleConfirm}>
            {intl.formatMessage({ id: 'pages.session.allowExecution', defaultMessage: 'Allow Execution' })}
          </Button>
        </Space>
      }
      width={700}
    >
      <div style={{ marginBottom: 16 }}>
        <p>{intl.formatMessage({ id: 'pages.session.toolConfirmDescription', defaultMessage: "AI wants to call the following tools, please confirm whether to allow execution:" })}</p>
      </div>
      <Table
        columns={columns}
        dataSource={pendingCallTools}
        rowKey="toolId"
        pagination={false}
        size="small"
        expandable={{
          expandedRowRender: (record) => (
            <div style={{ padding: '4px 0' }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--vip-primary)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 8 }}>
                {intl.formatMessage({ id: 'pages.session.toolArguments', defaultMessage: 'Arguments' })}
              </div>
              <ArgumentsTable args={record.arguments} />
            </div>
          ),
          defaultExpandAllRows: true,
        }}
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
  const intl = useIntl();
  const SUGGESTIONS = [
    intl.formatMessage({ id: 'pages.session.quickSuggestion.javaSort', defaultMessage: 'Help me write a Java sorting algorithm' }),
    intl.formatMessage({ id: 'pages.session.quickSuggestion.springBootAutoConfiguration', defaultMessage: 'Explain the automatic configuration principle of Spring Boot' }),
    intl.formatMessage({ id: 'pages.session.quickSuggestion.pythonWebServer', defaultMessage: 'Implement a simple web server in Python' }),
    intl.formatMessage({ id: 'pages.session.quickSuggestion.sqlOptimization', defaultMessage: 'How to optimize slow SQL queries?' }),
  ];
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [enableThink, setEnableThink] = useState(false);
  const [showThinking, setShowThinking] = useState(true); // 是否显示思考过程
  const [enableSearch, setEnableSearch] = useState(false);
  const [enablePlan, setEnablePlan] = useState(false);
  const [modelSupportReasoning, setModelSupportReasoning] = useState(true); // 模型是否支持深度思考
  const [modelThinkingMode, setModelThinkingMode] = useState<number | undefined>(undefined); // 0:不支持 1:可选 2:强制
  const [modelSupportInternet, setModelSupportInternet] = useState(true); // 模型是否支持联网搜索
  const [modelSupportVision, setModelSupportVision] = useState(true); // 模型是否支持视觉
    const [permissionMode, setPermissionMode] = useState('DEFAULT'); // 权限模式：DEFAULT/BYPASS/ACCEPT_EDITS/EXPLORE/DONT_ASK
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
  const [showScrollToBottom, setShowScrollToBottom] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messageListRef = useRef<HTMLDivElement>(null);
  const isNearBottomRef = useRef<boolean>(true); // 是否接近底部（用于决定是否自动滚动）
  const abortRef = useRef<AbortController | null>(null);
  const uploadRef = useRef<HTMLInputElement>(null);
  const planRefreshTimerRef = useRef<NodeJS.Timeout | null>(null); // 当前计划刷新定时器（2秒）
  const plansListTimerRef = useRef<NodeJS.Timeout | null>(null); // 历史计划刷新定时器（5秒）
  const planExitedRef = useRef<boolean>(false); // plan_exit 已触发标记，防止 finally 块重新加载

  const NEAR_BOTTOM_THRESHOLD = 120; // 距离底部多少像素内视为"接近底部"

  const scrollToBottom = useCallback((force = false) => {
    if (!force && !isNearBottomRef.current) return;
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  // 用户滚动时检测是否接近底部
  const handleScroll = useCallback(() => {
    const el = messageListRef.current;
    if (!el) return;
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    const nearBottom = distFromBottom < NEAR_BOTTOM_THRESHOLD;
    isNearBottomRef.current = nearBottom;
    setShowScrollToBottom(!nearBottom);
  }, []);

  // 新消息到来时，仅在用户接近底部时自动滚动
  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  // 用户点击"回到底部"按钮
  const handleScrollToBottomClick = useCallback(() => {
    isNearBottomRef.current = true;
    setShowScrollToBottom(false);
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

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

  // 当 sessionId 变化时，加载历史消息和会话配置
  useEffect(() => {
    if (!sessionId) {
      setMessages([]);
      setPlans([]);
      setShowPlanPanel(false);
      setCurrentPlan(null);
      currentPlanMessageIdRef.current = null; // 重置计划消息ID
      previousHasPlanRef.current = false; // 重置计划状态
      // 重置三个开关
      setEnableThink(false);
      setEnableSearch(false);
      setEnablePlan(false);
      return;
    }

    // 切换会话时重置计划卡片ID
    currentPlanMessageIdRef.current = null;
    previousHasPlanRef.current = false;

    const loadSessionData = async () => {
      try {
        setLoading(true);
        
        // 并行加载历史消息和会话配置
        const [messagesResponse, configResponse] = await Promise.all([
          getSessionMessages(sessionId),
          getSessionConfig(sessionId),
        ]);
        
        // 加载会话配置
        if (configResponse.code === 200 && configResponse.data) {
          const config = configResponse.data;
          setEnableThink(config.enableThink || false);
          setEnableSearch(config.enableSearch || false);
          setEnablePlan(config.enablePlan || false);
          setPermissionMode(config.permissionMode || 'DEFAULT');
          setModelSupportReasoning(config.modelSupportReasoning !== 0);
          setModelThinkingMode(
            config.modelThinkingMode != null
              ? config.modelThinkingMode
              : config.modelSupportReasoning !== 0 ? 1 : 0,
          );
          if ((config.modelThinkingMode ?? 0) === 2) {
            setEnableThink(true);
          }
          setModelSupportInternet(config.modelSupportInternet !== 0);
          setModelSupportVision(config.modelSupportVision !== 0);
        }
        
        // 加载历史消息
        if (messagesResponse.code === 200 && messagesResponse.data) {
          const logs: any[] = messagesResponse.data;
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
        console.error(`${intl.formatMessage({ id: 'pages.session.loadHistoryFailed', defaultMessage: 'Failed to load history messages' })}:`, error);
        message.error(intl.formatMessage({ id: 'pages.session.loadHistoryFailed', defaultMessage: 'Failed to load history messages' }));
      } finally {
        setLoading(false);
      }
    };

    loadSessionData();
  }, [sessionId]);

  /* ─── Slash-command keyword → command type mapping ─── */
  const SLASH_COMMANDS: Record<string, string> = {
    interrupt: 'INTERRUPT',
    stop: 'INTERRUPT',
    clear: 'CLEAR',
    compact: 'COMPACT',
    approve: 'APPROVE',
    'stop-sandbox': 'STOP_SANDBOX',
    enable: 'ENABLE',
    disable: 'DISABLE',
    permission: 'PERMISSION',
  };

  /**
   * Parse slash-command text. Returns { command, args } or null.
   */
  const parseSlashCommand = (text: string): { command: string; args: string } | null => {
    if (!text.startsWith('/')) return null;
    const afterSlash = text.substring(1).trim();
    if (!afterSlash) return null;

    const spaceIdx = afterSlash.indexOf(' ');
    const colonIdx = afterSlash.indexOf(':');
    const sepIdx =
      spaceIdx < 0 && colonIdx < 0
        ? -1
        : spaceIdx < 0
          ? colonIdx
          : colonIdx < 0
            ? spaceIdx
            : Math.min(spaceIdx, colonIdx);

    const keyword = (sepIdx >= 0 ? afterSlash.substring(0, sepIdx) : afterSlash).toLowerCase();
    const args = sepIdx >= 0 ? afterSlash.substring(sepIdx + 1).trim() : '';

    const command = SLASH_COMMANDS[keyword];
    if (!command) return null;
    return { command, args };
  };

  /* ─── 发送消息（fetch + ReadableStream，走代理） ─── */
  const doSend = async (text: string) => {
    if (!text.trim() || loading) return;

    // Detect slash commands and route to /command endpoint
    const parsed = parseSlashCommand(text.trim());
    if (parsed) {
      const userMessage: ChatMessage = {
        id: `user-${Date.now()}`,
        role: 'user',
        segments: [{ type: 'text', content: text.trim() }],
        timestamp: Date.now(),
      };
      setMessages((prev) => [...prev, userMessage]);
      setInputValue('');
      setLoading(true);

      try {
        const res = await fetch('/api/router/agent/command', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...getRouterHeaders(),
          },
          body: JSON.stringify({ type: 'COMMAND', sessionId, command: parsed.command, args: parsed.args }),
        });
        const json = await res.json();
        const reply = json.data?.message || (json.data?.success ? 'Done' : json.message || 'Command failed');
        const assistantMsg: ChatMessage = {
          id: `assistant-${Date.now()}`,
          role: 'assistant',
          segments: [{ type: 'text', content: reply }],
          timestamp: Date.now(),
        };
        setMessages((prev) => [...prev, assistantMsg]);
      } catch (e: any) {
        const errMsg: ChatMessage = {
          id: `assistant-${Date.now()}`,
          role: 'assistant',
          segments: [{ type: 'text', content: `\n\n> **Error**: ${e.message}` }],
          timestamp: Date.now(),
        };
        setMessages((prev) => [...prev, errMsg]);
      } finally {
        setLoading(false);
      }
      return;
    }

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

    // Helper: find tool_call segment index by toolId (via map) or by toolName (fallback)
    const findToolSegmentIdx = (toolId: string, toolName: string): number => {
      // Primary: exact match by toolId in toolCallMap
      if (toolId && toolCallMap.has(toolId)) {
        return toolCallMap.get(toolId)!;
      }
      // Fallback 1: find last tool_call segment with matching toolName that has no result yet
      for (let i = currentSegs.length - 1; i >= 0; i--) {
        const seg = currentSegs[i];
        if (seg.type === 'tool_call' && seg.toolName === toolName && seg.toolResult === undefined) {
          if (toolId) toolCallMap.set(toolId, i);
          return i;
        }
      }
      // Fallback 2: find ANY tool_call segment without confirmStatus and without result
      // (handles case where CallToolEvent and ToolConfirmEvent have different toolName formats)
      for (let i = currentSegs.length - 1; i >= 0; i--) {
        const seg = currentSegs[i];
        if (seg.type === 'tool_call' && !seg.confirmStatus && seg.toolResult === undefined) {
          console.log('[findToolSegmentIdx] Broad fallback matched segment at index:', i, 'segName:', seg.toolName, 'searchName:', toolName);
          if (toolId) toolCallMap.set(toolId, i);
          return i;
        }
      }
      return -1;
    };

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
        type: 'CHAT',
        sessionId,
        message: text.trim(),
        imageUrls: imageUrls,
      };
      console.log('[ChatWindow] chatBody:', chatBody);

      const response = await fetch('/api/router/agent/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
          ...getRouterHeaders(),
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
              // 如果当前不是 text 事件，检查是否需要追加到上一个 text segment
              if (currentEventType !== 'text') {
                // 检查上一个 segment 是否是 text 类型
                const lastSeg = currentSegs.length > 0 ? currentSegs[currentSegs.length - 1] : null;
                if (lastSeg && lastSeg.type === 'text') {
                  // 如果最后一个是 text，继续追加到它
                  activeTextIdx = currentSegs.length - 1;
                  accText = lastSeg.content || ''; // 读取已有内容
                } else {
                  // 否则创建新的 text segment
                  accText = '';
                  activeTextIdx = -1;
                }
              }
              currentEventType = 'text';
              
              if (data.isLast === true) {
                // 当前文本段结束
                accText = '';
                activeTextIdx = -1;
                currentEventType = null;
                continue;
              }
              
              accText += data.message || '';
              // Skip empty text events (e.g. tokenUsage-only events with empty message)
              if (!accText) {
                continue;
              }
              // 创建或更新 text segment
              if (activeTextIdx < 0 || activeTextIdx >= currentSegs.length) {
                currentSegs.push({ type: 'text', content: accText });
                activeTextIdx = currentSegs.length - 1;
              } else {
                currentSegs[activeTextIdx].content = accText;
              }
              changed = true;
              
            } else if (data.eventType === 'ThinkingEvent') {
              // isLast=true 表示思考内容结束，不显示此事件的内容
              if (data.isLast === true) {
                accThinking = '';
                activeThinkIdx = -1;
                currentEventType = null;
                continue;
              }
              
              // 如果当前不是 thinking 事件，检查是否需要追加到上一个 thinking segment
              if (currentEventType !== 'thinking') {
                // 检查上一个 segment 是否是 thinking 类型
                const lastSeg = currentSegs.length > 0 ? currentSegs[currentSegs.length - 1] : null;
                if (lastSeg && lastSeg.type === 'thinking') {
                  // 如果最后一个是 thinking，继续追加到它
                  activeThinkIdx = currentSegs.length - 1;
                  accThinking = lastSeg.content || ''; // 读取已有内容
                } else {
                  // 否则创建新的 thinking segment
                  accThinking = '';
                  activeThinkIdx = -1;
                }
              }
              currentEventType = 'thinking';
              
              // 累积当前批次的思考内容
              accThinking += data.message || '';
              // Skip empty thinking events
              if (!accThinking) {
                continue;
              }
              
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
              
            } else if (data.eventType === 'CallToolEvent') {
              const toolName = data.toolName;
              const toolId = data.toolId || `tool-${Date.now()}`;
              
              console.log('[CallToolEvent] toolName:', toolName, 'toolId:', toolId);
              
              // 如果没有工具名称，跳过
              if (!toolName) {
                console.log('[CallToolEvent] No toolName, skipping');
                continue;
              }
              
              // 如果是 plan_write 或 plan_enter，自动打开执行计划侧边栏并加载当前计划
              if ((toolName === 'plan_write' || toolName === 'plan_enter') && enablePlan) {
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
              
              // Dedup: find by toolId or toolName fallback
              const existIdx = findToolSegmentIdx(toolId, toolName);
              if (existIdx >= 0) {
                console.log('[CallToolEvent] Tool already exists at index:', existIdx, '- updating in place');
                currentSegs = currentSegs.map((seg, idx) =>
                  idx === existIdx && seg.type === 'tool_call'
                    ? { ...seg, content: JSON.stringify(data.arguments || {}, null, 2), toolName: toolName, toolId: toolId }
                    : seg
                );
              } else {
                const toolSeg: MessageSegment = {
                  type: 'tool_call',
                  content: JSON.stringify(data.arguments || {}, null, 2),
                  toolName: toolName,
                  toolId: toolId,
                };
                currentSegs.push(toolSeg);
                toolCallMap.set(toolId, currentSegs.length - 1);
                console.log('[CallToolEvent] Added tool at index:', currentSegs.length - 1);
              }
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
                const segIdx = findToolSegmentIdx(toolId, toolName);
                if (segIdx >= 0) {
                  currentSegs = currentSegs.map((seg, idx) => 
                    idx === segIdx && seg.type === 'tool_call'
                      ? { ...seg, toolResult: resultContent, ...(data.success === false ? { confirmStatus: 'rejected' } : {}) }
                      : seg
                  );
                }
                changed = true;
                
                // plan_exit 完成后创建新消息分段（plan 阶段 → 执行阶段）
                if (toolName === 'plan_exit') {
                  console.log('[ToolResultEvent] Detected plan_exit, creating new assistant message');
                  flushUI();
                  const newAssistantMessageId = `assistant-${Date.now()}-new`;
                  const newAssistantMessage: ChatMessage = {
                    id: newAssistantMessageId,
                    role: 'assistant',
                    segments: [],
                    timestamp: Date.now(),
                  };
                  currentMsgs = [...currentMsgs, newAssistantMessage];
                  setMessages(currentMsgs);
                  currentAssistantMessageId = newAssistantMessageId;
                  currentSegs = [];
                  accText = '';
                  accThinking = '';
                  activeTextIdx = -1;
                  activeThinkIdx = -1;
                  currentEventType = null;
                  toolCallMap = new Map<string, number>();
                  // plan 已结束，清理状态以停止轮询
                  setCurrentPlan(null);
                  currentPlanMessageIdRef.current = null;
                  previousHasPlanRef.current = false;
                  planExitedRef.current = true;
                }
                continue;
              }
              
              // 非计划工具结果，中断思考的连续性
              currentEventType = null;
              accText = '';
              accThinking = '';
              activeTextIdx = -1;
              activeThinkIdx = -1;
              
              // 根据 toolId 找到对应的工具调用 segment 并更新
              const segIdx = findToolSegmentIdx(toolId, toolName);
              if (segIdx >= 0) {
                console.log('[ToolResultEvent] Found tool at index:', segIdx);
                currentSegs = currentSegs.map((seg, idx) => 
                  idx === segIdx && seg.type === 'tool_call'
                    ? { ...seg, toolResult: resultContent, ...(data.success === false ? { confirmStatus: 'rejected' } : {}) }
                    : seg
                );
                console.log('[ToolResultEvent] Updated segment:', currentSegs[segIdx]);
              } else {
                console.log('[ToolResultEvent] Tool not found, ignoring result');
              }
              changed = true;
              
            } else if (data.eventType === 'ErrorEvent') {
              // 后端错误事件：把错误信息展示给用户，避免静默无响应
              console.error('[ErrorEvent]', data.code, data.message);
              currentEventType = null;
              accText = '';
              accThinking = '';
              activeTextIdx = -1;
              activeThinkIdx = -1;
              const errText = `${intl.formatMessage({ id: 'pages.session.errorOccurred', defaultMessage: 'An error occurred' })}: ${data.message || data.code || 'Unknown error'}`;
              currentSegs = [...currentSegs, { type: 'text', content: errText }];
              message.error(errText, 8);
              setLoading(false);
              changed = true;

            } else if (data.eventType === 'EndEvent') {
              // 收到结束事件，表示 AI 输出已完成
              console.log('[EndEvent] AI output completed');
              currentEventType = null;
              accText = '';
              accThinking = '';
              activeTextIdx = -1;
              activeThinkIdx = -1;
              
              // Clean up empty segments to prevent LoadingDots from appearing
              // after stream ends (e.g. empty text/thinking segments from tokenUsage-only events)
              currentSegs = currentSegs.filter((seg) => {
                if ((seg.type === 'text' || seg.type === 'thinking') && !seg.content) return false;
                return true;
              });
              
              // 立即释放 loading 状态，允许用户发送新消息
              setLoading(false);
              changed = true;
              
            } else if (data.eventType === 'ToolConfirmEvent') {
              // 收到工具确认事件，暂停并等待用户确认
              currentEventType = null;
              accText = '';
              accThinking = '';
              activeTextIdx = -1;
              activeThinkIdx = -1;
              
              const pendingTools = data.pendingCallTools || [];
              
              // 为每个待确认的工具添加工具调用卡片（去重：复用 CallToolEvent 已创建的卡片）
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
                
                // 去重：检查 CallToolEvent 是否已创建该工具卡片
                console.log('[ToolConfirmEvent] Searching for tool:', tool.toolId, tool.toolName, '| currentSegs:', currentSegs.map((s, i) => `[${i}]${s.type}:${s.toolName||'?'}/${s.toolId||'?'}/${s.confirmStatus||'-'}/${s.toolResult!==undefined?'hasResult':'noResult'}`).join(', '), '| toolCallMap:', Array.from(toolCallMap.entries()).map(([k,v]) => `${k}→${v}`).join(', '));
                const existIdx = findToolSegmentIdx(tool.toolId, tool.toolName);
                if (existIdx >= 0) {
                  // 复用已有卡片，仅更新 confirmStatus 和参数
                  console.log('[ToolConfirmEvent] Tool already exists at index:', existIdx, '- updating confirmStatus to pending');
                  currentSegs = currentSegs.map((seg, idx) =>
                    idx === existIdx && seg.type === 'tool_call'
                      ? { ...seg, confirmStatus: 'pending' as const, content: JSON.stringify(tool.arguments || {}, null, 2) }
                      : seg
                  );
                } else {
                  const toolSeg: MessageSegment = {
                    type: 'tool_call',
                    content: JSON.stringify(tool.arguments || {}, null, 2),
                    toolName: tool.toolName,
                    toolId: tool.toolId,
                    confirmStatus: 'pending' as const,
                  };
                  currentSegs.push(toolSeg);
                  toolCallMap.set(tool.toolId, currentSegs.length - 1);
                  console.log('[ToolConfirmEvent] Added tool to map:', tool.toolId, 'at index:', currentSegs.length - 1);
                }
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
                type: 'CONFIRM',
                sessionId,
                isConfirmed: confirmResult,
                toolInfoList: pendingTools.map((tool: PendingCallTool) => ({
                  toolId: tool.toolId,
                  toolName: tool.toolName,
                })),
              };
              
              const confirmResponse = await fetch('/api/router/agent/confirm', {
                method: 'POST',
                headers: {
                  'Content-Type': 'application/json',
                  'Accept': 'text/event-stream',
                  ...getRouterHeaders(),
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
                      if (confirmData.isLast === true) {
                        accText = '';
                        activeTextIdx = -1;
                        currentEventType = null;
                        continue;
                      }
                      
                      // 如果当前不是 text 事件，检查是否需要追加到上一个 text segment
                      if (currentEventType !== 'text') {
                        // 检查上一个 segment 是否是 text 类型
                        const lastSeg = currentSegs.length > 0 ? currentSegs[currentSegs.length - 1] : null;
                        if (lastSeg && lastSeg.type === 'text') {
                          // 如果最后一个是 text，继续追加到它
                          activeTextIdx = currentSegs.length - 1;
                          accText = lastSeg.content || ''; // 读取已有内容
                        } else {
                          // 否则创建新的 text segment
                          accText = '';
                          activeTextIdx = -1;
                        }
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
                      if (confirmData.isLast === true) {
                        accThinking = '';
                        activeThinkIdx = -1;
                        currentEventType = null;
                        continue;
                      }
                      
                      // 如果当前不是 thinking 事件，检查是否需要追加到上一个 thinking segment
                      if (currentEventType !== 'thinking') {
                        // 检查上一个 segment 是否是 thinking 类型
                        const lastSeg = currentSegs.length > 0 ? currentSegs[currentSegs.length - 1] : null;
                        if (lastSeg && lastSeg.type === 'thinking') {
                          // 如果最后一个是 thinking，继续追加到它
                          activeThinkIdx = currentSegs.length - 1;
                          accThinking = lastSeg.content || ''; // 读取已有内容
                        } else {
                          // 否则创建新的 thinking segment
                          accThinking = '';
                          activeThinkIdx = -1;
                        }
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
                      
                      if ((confirmToolName === 'plan_write' || confirmToolName === 'plan_enter') && enablePlan) {
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
                      
                      // Dedup: if toolId already in toolCallMap, or find by toolName fallback
                      const existIdx = findToolSegmentIdx(confirmToolId, confirmToolName);
                      if (existIdx >= 0) {
                        console.log('[Confirm CallToolEvent] Tool already exists at index:', existIdx, '- updating in place');
                        currentSegs = currentSegs.map((seg, idx) =>
                          idx === existIdx && seg.type === 'tool_call'
                            ? { ...seg, content: JSON.stringify(confirmData.arguments || {}, null, 2), toolName: confirmToolName, toolId: confirmToolId }
                            : seg
                        );
                      } else {
                        const toolSeg: MessageSegment = {
                          type: 'tool_call',
                          content: JSON.stringify(confirmData.arguments || {}, null, 2),
                          toolName: confirmToolName,
                          toolId: confirmToolId,
                        };
                        currentSegs.push(toolSeg);
                        toolCallMap.set(confirmToolId, currentSegs.length - 1);
                      }
                      confirmChanged = true;
                      
                    } else if (confirmData.eventType === 'ToolResultEvent') {
                      const toolId = confirmData.toolId || '';
                      const resultContent = confirmData.message || '';
                      const toolName = confirmData.toolName || '';
                      
                      // 计划相关工具的结果不中断思考的连续性
                      if (isPlanRelatedTool(toolName)) {
                        const segIdx = findToolSegmentIdx(toolId, toolName);
                        if (segIdx >= 0) {
                          currentSegs = currentSegs.map((seg, idx) => 
                            idx === segIdx && seg.type === 'tool_call'
                              ? { ...seg, toolResult: resultContent, ...(confirmData.success === false ? { confirmStatus: 'rejected' } : {}) }
                              : seg
                          );
                        }
                        confirmChanged = true;
                        
                        // plan_exit 完成后创建新消息分段
                        if (toolName === 'plan_exit') {
                          console.log('[Confirm ToolResultEvent] Detected plan_exit, creating new assistant message');
                          flushUI();
                          const newAssistantMessageId = `assistant-${Date.now()}-new`;
                          const newAssistantMessage: ChatMessage = {
                            id: newAssistantMessageId,
                            role: 'assistant',
                            segments: [],
                            timestamp: Date.now(),
                          };
                          currentMsgs = [...currentMsgs, newAssistantMessage];
                          setMessages(currentMsgs);
                          currentAssistantMessageId = newAssistantMessageId;
                          currentSegs = [];
                          accText = '';
                          accThinking = '';
                          activeTextIdx = -1;
                          activeThinkIdx = -1;
                          currentEventType = null;
                          toolCallMap = new Map<string, number>();
                          // plan 已结束，清理状态以停止轮询
                          setCurrentPlan(null);
                          currentPlanMessageIdRef.current = null;
                          previousHasPlanRef.current = false;
                          planExitedRef.current = true;
                        }
                        continue;
                      }
                      
                      // 非计划工具结果，中断思考的连续性
                      currentEventType = null;
                      accText = '';
                      accThinking = '';
                      activeTextIdx = -1;
                      activeThinkIdx = -1;
                      
                      const segIdx = findToolSegmentIdx(toolId, toolName);
                      if (segIdx >= 0) {
                        currentSegs = currentSegs.map((seg, idx) => 
                          idx === segIdx && seg.type === 'tool_call'
                            ? { ...seg, toolResult: resultContent, ...(confirmData.success === false ? { confirmStatus: 'rejected' } : {}) }
                            : seg
                        );
                      } else {
                        console.log('[Confirm ToolResultEvent] Tool not found, ignoring result');
                      }
                      confirmChanged = true;
                    } else if (confirmData.eventType === 'ErrorEvent') {
                      // 后端错误事件：把错误信息展示给用户，避免静默无响应
                      console.error('[Confirm ErrorEvent]', confirmData.code, confirmData.message);
                      currentEventType = null;
                      accText = '';
                      accThinking = '';
                      activeTextIdx = -1;
                      activeThinkIdx = -1;
                      const errText = `${intl.formatMessage({ id: 'pages.session.errorOccurred', defaultMessage: 'An error occurred' })}: ${confirmData.message || confirmData.code || 'Unknown error'}`;
                      currentSegs = [...currentSegs, { type: 'text', content: errText }];
                      message.error(errText, 8);
                      setLoading(false);
                      confirmChanged = true;
                    } else if (confirmData.eventType === 'EndEvent') {
                      // 收到结束事件，表示 AI 输出已完成
                      console.log('[Confirm EndEvent] AI output completed');
                      currentEventType = null;
                      accText = '';
                      accThinking = '';
                      activeTextIdx = -1;
                      activeThinkIdx = -1;
                      // 立即释放 loading 状态，允许用户发送新消息
                      setLoading(false);
                      confirmChanged = true;
                    } else if (confirmData.eventType === 'ToolConfirmEvent') {
                      // 递归处理嵌套的工具确认
                      accText = '';
                      const nestedPendingTools = confirmData.pendingCallTools || [];
                      
                      // 为每个待确认的工具添加工具调用卡片（去重：复用已有卡片）
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
                        
                        // 去重：检查是否已存在该工具卡片
                        console.log('[Nested ToolConfirmEvent] Searching for tool:', tool.toolId, tool.toolName, '| currentSegs:', currentSegs.map((s, i) => `[${i}]${s.type}:${s.toolName||'?'}/${s.toolId||'?'}/${s.confirmStatus||'-'}/${s.toolResult!==undefined?'hasResult':'noResult'}`).join(', '));
                        const nestedExistIdx = findToolSegmentIdx(tool.toolId, tool.toolName);
                        if (nestedExistIdx >= 0) {
                          console.log('[Nested ToolConfirmEvent] Tool already exists at index:', nestedExistIdx, '- updating confirmStatus to pending');
                          currentSegs = currentSegs.map((seg, idx) =>
                            idx === nestedExistIdx && seg.type === 'tool_call'
                              ? { ...seg, confirmStatus: 'pending' as const, content: JSON.stringify(tool.arguments || {}, null, 2) }
                              : seg
                          );
                        } else {
                          const toolSeg: MessageSegment = {
                            type: 'tool_call',
                            content: JSON.stringify(tool.arguments || {}, null, 2),
                            toolName: tool.toolName,
                            toolId: tool.toolId,
                            confirmStatus: 'pending' as const,
                          };
                          currentSegs.push(toolSeg);
                          toolCallMap.set(tool.toolId, currentSegs.length - 1);
                          console.log('[Nested ToolConfirmEvent] Added tool to map:', tool.toolId, 'at index:', currentSegs.length - 1);
                        }
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
                        type: 'CONFIRM',
                        sessionId,
                        isConfirmed: nestedConfirmResult,
                        toolInfoList: nestedPendingTools.map((tool: PendingCallTool) => ({
                          toolId: tool.toolId,
                          toolName: tool.toolName,
                        })),
                      };
                      
                      const nestedConfirmResponse = await fetch('/api/router/agent/confirm', {
                        method: 'POST',
                        headers: {
                          'Content-Type': 'application/json',
                          'Accept': 'text/event-stream',
                          ...getRouterHeaders(),
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
                              if (nestedData.isLast === true) {
                                accText = '';
                                activeTextIdx = -1;
                                currentEventType = null;
                                continue;
                              }
                              
                              // 如果当前不是 text 事件，检查是否需要追加到上一个 text segment
                              if (currentEventType !== 'text') {
                                // 检查上一个 segment 是否是 text 类型
                                const lastSeg = currentSegs.length > 0 ? currentSegs[currentSegs.length - 1] : null;
                                if (lastSeg && lastSeg.type === 'text') {
                                  // 如果最后一个是 text，继续追加到它
                                  activeTextIdx = currentSegs.length - 1;
                                  accText = lastSeg.content || ''; // 读取已有内容
                                } else {
                                  // 否则创建新的 text segment
                                  accText = '';
                                  activeTextIdx = -1;
                                }
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
                              // isLast=true 表示思考内容结束，不显示此事件的内容
                              if (nestedData.isLast === true) {
                                accThinking = '';
                                activeThinkIdx = -1;
                                currentEventType = null;
                                continue;
                              }
                              
                              // 如果当前不是 thinking 事件，检查是否需要追加到上一个 thinking segment
                              if (currentEventType !== 'thinking') {
                                // 检查上一个 segment 是否是 thinking 类型
                                const lastSeg = currentSegs.length > 0 ? currentSegs[currentSegs.length - 1] : null;
                                if (lastSeg && lastSeg.type === 'thinking') {
                                  // 如果最后一个是 thinking，继续追加到它
                                  activeThinkIdx = currentSegs.length - 1;
                                  accThinking = lastSeg.content || ''; // 读取已有内容
                                } else {
                                  // 否则创建新的 thinking segment
                                  accThinking = '';
                                  activeThinkIdx = -1;
                                }
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
                              
                              if ((nestedToolName === 'plan_write' || nestedToolName === 'plan_enter') && enablePlan) {
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
                              
                              // Dedup: find by toolId or toolName fallback
                              const nestedExistIdx = findToolSegmentIdx(nestedToolId, nestedToolName);
                              if (nestedExistIdx >= 0) {
                                console.log('[Nested Confirm CallToolEvent] Tool already exists at index:', nestedExistIdx, '- updating in place');
                                currentSegs = currentSegs.map((seg, idx) =>
                                  idx === nestedExistIdx && seg.type === 'tool_call'
                                    ? { ...seg, content: JSON.stringify(nestedData.arguments || {}, null, 2), toolName: nestedToolName, toolId: nestedToolId }
                                    : seg
                                );
                              } else {
                                const toolSeg: MessageSegment = {
                                  type: 'tool_call',
                                  content: JSON.stringify(nestedData.arguments || {}, null, 2),
                                  toolName: nestedToolName,
                                  toolId: nestedToolId,
                                };
                                currentSegs.push(toolSeg);
                                toolCallMap.set(nestedToolId, currentSegs.length - 1);
                              }
                              confirmChanged = true;
                              
                            } else if (nestedData.eventType === 'ToolResultEvent') {
                              const toolId = nestedData.toolId || '';
                              const resultContent = nestedData.message || '';
                              const toolName = nestedData.toolName || '';
                              
                              // 计划相关工具的结果不中断思考的连续性
                              if (isPlanRelatedTool(toolName)) {
                                const planSegIdx = findToolSegmentIdx(toolId, toolName);
                                if (planSegIdx >= 0) {
                                  currentSegs = currentSegs.map((seg, idx) => 
                                    idx === planSegIdx && seg.type === 'tool_call'
                                      ? { ...seg, toolResult: resultContent, ...(nestedData.success === false ? { confirmStatus: 'rejected' } : {}) }
                                      : seg
                                  );
                                }
                                confirmChanged = true;
                                
                                // plan_exit 完成后创建新消息分段
                                if (toolName === 'plan_exit') {
                                  console.log('[Nested ToolResultEvent] Detected plan_exit, creating new assistant message');
                                  flushUI();
                                  const newAssistantMessageId = `assistant-${Date.now()}-new`;
                                  const newAssistantMessage: ChatMessage = {
                                    id: newAssistantMessageId,
                                    role: 'assistant',
                                    segments: [],
                                    timestamp: Date.now(),
                                  };
                                  currentMsgs = [...currentMsgs, newAssistantMessage];
                                  setMessages(currentMsgs);
                                  currentAssistantMessageId = newAssistantMessageId;
                                  currentSegs = [];
                                  accText = '';
                                  accThinking = '';
                                  activeTextIdx = -1;
                                  activeThinkIdx = -1;
                                  currentEventType = null;
                                  toolCallMap = new Map<string, number>();
                                  // plan 已结束，清理状态以停止轮询
                                  setCurrentPlan(null);
                                  currentPlanMessageIdRef.current = null;
                                  previousHasPlanRef.current = false;
                                  planExitedRef.current = true;
                                }
                                continue;
                              }
                              
                              // 非计划工具结果，中断思考的连续性
                              currentEventType = null;
                              accText = '';
                              accThinking = '';
                              activeTextIdx = -1;
                              activeThinkIdx = -1;
                              
                              const nestedSegIdx = findToolSegmentIdx(toolId, toolName);
                              if (nestedSegIdx >= 0) {
                                currentSegs = currentSegs.map((seg, idx) => 
                                  idx === nestedSegIdx && seg.type === 'tool_call'
                                    ? { ...seg, toolResult: resultContent, ...(nestedData.success === false ? { confirmStatus: 'rejected' } : {}) }
                                    : seg
                                );
                              } else {
                                console.log('[Nested ToolResultEvent] Tool not found, ignoring result');
                              }
                              confirmChanged = true;
                            } else if (nestedData.eventType === 'ErrorEvent') {
                              // 后端错误事件：把错误信息展示给用户，避免静默无响应
                              console.error('[Nested ErrorEvent]', nestedData.code, nestedData.message);
                              currentEventType = null;
                              accText = '';
                              accThinking = '';
                              activeTextIdx = -1;
                              activeThinkIdx = -1;
                              const errText = `${intl.formatMessage({ id: 'pages.session.errorOccurred', defaultMessage: 'An error occurred' })}: ${nestedData.message || nestedData.code || 'Unknown error'}`;
                              currentSegs = [...currentSegs, { type: 'text', content: errText }];
                              message.error(errText, 8);
                              setLoading(false);
                              confirmChanged = true;
                            } else if (nestedData.eventType === 'EndEvent') {
                              // 收到结束事件，表示 AI 输出已完成
                              console.log('[Nested EndEvent] AI output completed');
                              currentEventType = null;
                              accText = '';
                              accThinking = '';
                              activeTextIdx = -1;
                              activeThinkIdx = -1;
                              // 立即释放 loading 状态，允许用户发送新消息
                              setLoading(false);
                              confirmChanged = true;
                            }
                          } catch (err) {
                            console.error(`${intl.formatMessage({ id: 'pages.session.parseNestedSSEFailed', defaultMessage: "Failed to parse nested SSE message" })}:`, err, nestedLine);
                          }
                        }
                        
                        if (confirmChanged) {
                          flushUI();
                        }
                      }
                    }
                  } catch (err) {
                    console.error(`${intl.formatMessage({ id: 'pages.session.parseConfirmSSEFailed', defaultMessage: "Failed to parse confirm SSE message" })}:`, err, line);
                  }
                }
                
                if (confirmChanged) {
                  flushUI();
                }
              }
            }
          } catch (err) {
            console.error(`${intl.formatMessage({ id: 'pages.session.parseSSEFailed', defaultMessage: "Failed to parse SSE message" })}:`, err, line);
          }
        }

        // 每个 chunk 处理完后统一刷新 UI
        if (changed) {
          flushUI();
        }
      }
    } catch (error: any) {
      if (error?.name === 'AbortError') return;
      console.error(`${intl.formatMessage({ id: 'pages.session.sendMessageFailed', defaultMessage: 'Failed to send message' })}:`, error);
      message.error(intl.formatMessage({ id: 'pages.session.sendMessageFailed', defaultMessage: 'Failed to send message' }));
      if (currentSegs.length === 0) {
        currentSegs.push({ type: 'text', content: intl.formatMessage({ id: 'pages.session.sendMessageFailedContent', defaultMessage: "Message sending failed, please try again" }) });
        flushUI();
      }
    } finally {
      setLoading(false);
      abortRef.current = null;
      
      // 如果启用了计划功能且卡片处于展开状态，立即刷新一次
      // 但如果 plan_exit 已触发，跳过刷新避免重新加载已完成的计划
      if (enablePlan && currentPlanExpanded && !planExitedRef.current) {
        loadCurrentPlan();
      }
      // 重置 planExited 标记
      planExitedRef.current = false;
    }
  };

  const handleSend = () => doSend(inputValue);

  /* ─── 停止当前 agent 运行 ─── */
  const handleStop = () => {
    abortRef.current?.abort();
    sendSilentCommand('INTERRUPT');
    setLoading(false);
  };

  /* ─── 发送静默命令（不显示在聊天中，用于配置切换/清空） ─── */
  const sendSilentCommand = async (command: string, args: string = '') => {
    if (!sessionId) return;
    try {
      const response = await fetch('/api/router/agent/command', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...getRouterHeaders(),
        },
        body: JSON.stringify({ type: 'COMMAND', sessionId, command, args }),
      });
      const result = await response.json();
      // Check if command failed and show warning
      if (result?.data && result.data.success === false && result.data.message) {
        message.warning(result.data.message);
        return false;
      }
      return true;
    } catch (error) {
      console.error('Failed to send command:', error);
      return false;
    }
  };

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
      console.error(`${intl.formatMessage({ id: 'pages.session.imageConversionFailed', defaultMessage: 'Failed to convert image' })}:`, error);
      message.error(intl.formatMessage({ id: 'pages.session.imageConversionFailed', defaultMessage: 'Failed to convert image' }));
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
      const response = await fetch(`/api/router/agent/session/${sessionId}/current-plan`, {
        headers: {
          ...getRouterHeaders(),
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
      console.error(`${intl.formatMessage({ id: 'pages.session.loadCurrentPlanFailed', defaultMessage: "Failed to load current plan" })}:`, error);
    }
  };

  /* ─── 加载计划列表 ─── */
  const loadPlans = async (isIncremental = false) => {
    if (!sessionId) return;
    
    try {
      setLoadingPlans(true);
      const response = await fetch(`/api/router/agent/session/${sessionId}/plans`, {
        headers: {
          ...getRouterHeaders(),
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
      console.error(`${intl.formatMessage({ id: 'pages.session.loadPlansFailed', defaultMessage: "Failed to load plans list" })}:`, error);
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
      // 发送 /clear 命令到后端
      await sendSilentCommand('CLEAR');

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
      
      message.success(intl.formatMessage({ id: 'pages.session.clearChatSuccess', defaultMessage: 'Chat history cleared' }));
    } catch (error) {
      console.error(`${intl.formatMessage({ id: 'pages.session.clearChatFailed', defaultMessage: 'Failed to clear chat history' })}:`, error);
      message.error(intl.formatMessage({ id: 'pages.session.clearChatFailed', defaultMessage: 'Failed to clear chat history' }));
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
                    <span>{intl.formatMessage({ id: 'pages.session.realtime', defaultMessage: 'Live' })}</span>
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
                            <div className={styles.subtaskStatus}>{intl.formatMessage({ id: 'pages.session.executing', defaultMessage: 'Executing...' })}</div>
                          )}
                          {subtask.state === 'ABANDONED' && (
                            <div className={styles.subtaskStatus} style={{ color: '#ff4d4f' }}>{intl.formatMessage({ id: 'pages.session.abandoned', defaultMessage: 'Abandoned' })}</div>
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
      TODO: { color: 'default', text: intl.formatMessage({ id: 'pages.session.planState.todo', defaultMessage: 'To Do' }) },
      IN_PROGRESS: { color: 'processing', text: intl.formatMessage({ id: 'pages.session.planState.inProgress', defaultMessage: 'In Progress' }) },
      DONE: { color: 'success', text: intl.formatMessage({ id: 'pages.session.planState.done', defaultMessage: 'Done' }) },
      ABANDONED: { color: 'error', text: intl.formatMessage({ id: 'pages.session.planState.abandoned', defaultMessage: 'Abandoned' }) },
    };
    const config = stateMap[state] || { color: 'default', text: state };
    return <Tag color={config.color}>{config.text}</Tag>;
  };

  /* ─── 判断是否为计划相关的工具 ─── */
  const isPlanRelatedTool = (toolName: string): boolean => {
    const planTools = [
      'plan_enter',
      'plan_write',
      'plan_exit',
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
                <span>{intl.formatMessage({ id: 'pages.session.realtime', defaultMessage: 'Live' })}</span>
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
                        <div className={styles.subtaskStatus}>{intl.formatMessage({ id: 'pages.session.executing', defaultMessage: 'Executing...' })}</div>
                      )}
                      {subtask.state === 'ABANDONED' && (
                        <div className={styles.subtaskStatus} style={{ color: '#ff4d4f' }}>{intl.formatMessage({ id: 'pages.session.abandoned', defaultMessage: 'Abandoned' })}</div>
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
          <h3>{intl.formatMessage({ id: 'pages.session.planPanelTitle', defaultMessage: 'Execution Plan' })}</h3>
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
              <h4>{intl.formatMessage({ id: 'pages.session.currentPlanTitle', defaultMessage: 'Current Plan' })}</h4>
              {hasValidCurrentPlan() && (
                <div className={styles.refreshIndicator}>
                  <ClockCircleOutlined spin />
                  <span>{intl.formatMessage({ id: 'pages.session.realtimeUpdate', defaultMessage: 'Realtime Update' })}</span>
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
                  {currentPlan.activePlan.subtasks && currentPlan.activePlan.subtasks.length > 0 && (
                    <div className={styles.planProgressBadge}>
                      <span className={styles.progressText}>
                        {currentPlan.activePlan.subtasks.filter((t: any) => t.state === 'DONE').length}
                      </span>
                      <span className={styles.progressDivider}>/</span>
                      <span className={styles.progressTotal}>
                        {currentPlan.activePlan.subtasks.length}
                      </span>
                    </div>
                  )}
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
                            <div className={styles.subtaskStatus}>{intl.formatMessage({ id: 'pages.session.executing', defaultMessage: 'Executing...' })}</div>
                          )}
                          {subtask.state === 'ABANDONED' && (
                            <div className={styles.subtaskStatus}>{intl.formatMessage({ id: 'pages.session.abandoned', defaultMessage: 'Abandoned' })}</div>
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
                <p>{intl.formatMessage({ id: 'pages.session.noCurrentPlan', defaultMessage: 'No Current Plan' })}</p>
                <span>{intl.formatMessage({ id: 'pages.session.noCurrentPlanHint', defaultMessage: 'Enable "Enable Plan" option and AI will automatically create plans' })}</span>
              </div>
            )}
          </div>

          {/* 历史计划 */}
          {loadingPlans && plans.length === 0 && (
            <div className={styles.loadingPlans}>
              <p>{intl.formatMessage({ id: 'pages.session.loadingPlans', defaultMessage: 'Loading plans...' })}</p>
            </div>
          )}
          
          {!loadingPlans && plans.length === 0 && !currentPlan && (
            <div className={styles.planEmpty}>
              <ClockCircleOutlined style={{ fontSize: 32, color: '#d9d9d9' }} />
              <p>{intl.formatMessage({ id: 'pages.session.noPlans', defaultMessage: 'No Plans' })}</p>
              <span>{intl.formatMessage({ id: 'pages.session.noPlansHint', defaultMessage: 'Enable "Enable Plan" option and AI will automatically create plans' })}</span>
            </div>
          )}
          
          {!loadingPlans && plans.length > 0 && (
            <div className={styles.historyPlansSection}>
              <div className={styles.historyPlansHeader}>
                <div className={styles.historyPlansTitle}>
                  <h4>{intl.formatMessage({ id: 'pages.session.historyPlansTitle', defaultMessage: 'History Plans' })}</h4>
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
                  <span className={styles.refreshButtonText}>{intl.formatMessage({ id: 'pages.common.refresh', defaultMessage: 'Refresh' })}</span>
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
                            {plan.status === 'DONE' ? intl.formatMessage({ id: 'pages.session.completed', defaultMessage: 'Completed' }) :
                             plan.status === 'IN_PROGRESS' ? intl.formatMessage({ id: 'pages.session.inProgress', defaultMessage: 'In Progress' }) :
                             plan.status === 'ABANDONED' ? intl.formatMessage({ id: 'pages.session.abandoned', defaultMessage: 'Abandoned' }) :
                             intl.formatMessage({ id: 'pages.session.pending', defaultMessage: 'Pending' })}
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
                        <strong>{intl.formatMessage({ id: 'pages.session.expectedResult', defaultMessage: 'Expected Result:' })}</strong>
                        <span>{plan.expectedOutcome}</span>
                      </div>
                    )}
                    {plan.subtasks && plan.subtasks.length > 0 && (
                      <div className={styles.planSubtasks}>
                        <h4>{intl.formatMessage({ id: 'pages.session.subtasks', defaultMessage: 'Subtasks' })} ({plan.subtasks.length})</h4>
                        <Table
                          dataSource={plan.subtasks}
                          rowKey={(record) => record.name}
                          size="small"
                          pagination={false}
                          scroll={{ x: 'max-content' }}
                          columns={[
                            {
                              title: intl.formatMessage({ id: 'pages.session.status', defaultMessage: 'Status' }),
                              dataIndex: 'state',
                              key: 'state',
                              width: 90,
                              render: (state: string) => renderPlanState(state),
                            },
                            {
                              title: intl.formatMessage({ id: 'pages.session.taskName', defaultMessage: 'Task Name' }),
                              dataIndex: 'name',
                              key: 'name',
                              ellipsis: true,
                            },
                            {
                              title: intl.formatMessage({ id: 'pages.session.costTime', defaultMessage: 'Cost Time' }),
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
                                    <strong>{intl.formatMessage({ id: 'pages.session.description', defaultMessage: 'Description:' })}</strong>
                                    {record.description}
                                  </p>
                                )}
                                {record.expectedOutcome && (
                                  <p>
                                    <strong>{intl.formatMessage({ id: 'pages.session.expectedResult', defaultMessage: 'Expected Result:' })}</strong>
                                    {record.expectedOutcome}
                                  </p>
                                )}
                                {record.outcome && (
                                  <p>
                                    <strong>{intl.formatMessage({ id: 'pages.session.actualResult', defaultMessage: 'Actual Result:' })}</strong>
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
                      <span>{intl.formatMessage({ id: 'pages.session.createdAt', defaultMessage: 'Created At:' })}{plan.createdAt}</span>
                      {plan.costTimeSeconds > 0 && (
                        <span>{intl.formatMessage({ id: 'pages.session.totalCostTime', defaultMessage: 'Total Cost Time:' })}{plan.costTimeSeconds}s</span>
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
      <div className={styles.messageListWrap}>
        <div ref={messageListRef} className={styles.messageList} onScroll={handleScroll}>
        {messages.length === 0 ? (
          <div className={styles.emptyState}>
            <div className={styles.emptyIcon}>
              <RobotOutlined style={{ color: '#fff' }} />
            </div>
            <div className={styles.emptyTitle}>{intl.formatMessage({ id: 'pages.session.emptyTitle', defaultMessage: "What can I help you with?" })}</div>
            <div className={styles.emptySubtitle}>{intl.formatMessage({ id: 'pages.session.emptySubtitle', defaultMessage: "Select a topic or enter your question directly" })}</div>
            <div className={styles.suggestions}>
              {SUGGESTIONS.map((s) => (
                <div key={s} className={styles.suggestionChip} onClick={() => doSend(s)}>
                  {s}
                </div>
              ))}
            </div>
          </div>
        ) : (
          messages.map((msg) => {
            // Skip empty assistant messages after stream ended (EndEvent received)
            if (msg.role === 'assistant' && isEmpty(msg) && !loading) return null;
            return (
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
                    loading && msg.id === messages[messages.length - 1]?.id ? <LoadingDots /> : null
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
            );
          })
        )}
        <div ref={messagesEndRef} />
        </div>

        {/* ─── 回到底部按钮 ─── */}
        {showScrollToBottom && (
          <button className={styles.scrollToBottomBtn} onClick={handleScrollToBottomClick} title="Scroll to bottom">
            <DownOutlined />
          </button>
        )}
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
              placeholder={loading 
                ? intl.formatMessage({ id: 'pages.session.inputPlaceholder.loading', defaultMessage: 'AI is outputting, please wait...' })
                : intl.formatMessage({ id: 'pages.session.inputPlaceholder.default', defaultMessage: 'Enter message... (Enter to send, Shift + Enter for new line)' })
              }
              autoSize={{ minRows: 2, maxRows: 6 }}
            />
          </div>
          <div className={styles.inputCardFooter}>
            <div className={styles.options}>
              <div
                className={`${styles.optionItem} ${imageUrls.length > 0 ? styles.optionActive : ''} ${!modelSupportVision ? styles.optionDisabled : ''}`}
                onClick={() => {
                  if (!modelSupportVision) {
                    message.warning(intl.formatMessage({ id: 'pages.session.modelNotSupportVision', defaultMessage: 'Current model does not support image input' }));
                    return;
                  }
                  uploadRef.current?.click();
                }}
              >
                <PictureOutlined />
                <span>{intl.formatMessage({ id: 'pages.session.imageUpload', defaultMessage: 'Image' })} ({imageUrls.length})</span>
              </div>
              <Dropdown
                menu={{
                  items: [
                    {
                      key: 'show',
                      label: (
                        <span className={styles.menuItemLabel}>
                          <EyeOutlined className={styles.menuIcon} />
                          <span>{intl.formatMessage({ id: 'pages.session.showThinking', defaultMessage: 'Show Thinking Process' })}</span>
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
                          <span>{intl.formatMessage({ id: 'pages.session.hideThinking', defaultMessage: 'Hide Thinking Process' })}</span>
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
                      <span>{intl.formatMessage({ id: 'pages.session.thinkingMenuTitle', defaultMessage: 'Thinking Process Display Settings' })}</span>
                    </div>
                    {menu}
                  </div>
                )}
              >
                <div
                  className={`${styles.optionItem} ${enableThink || modelThinkingMode === 2 ? styles.optionActive : ''} ${!modelSupportReasoning ? styles.optionDisabled : ''}`}
                  onClick={() => {
                    if (!modelSupportReasoning) {
                      message.warning(intl.formatMessage({ id: 'pages.session.modelNotSupportReasoning', defaultMessage: 'Current model does not support Deep Thinking' }));
                      return;
                    }
                    if (modelThinkingMode === 2) {
                      message.info(intl.formatMessage({ id: 'pages.session.thinkingRequired', defaultMessage: 'Current model requires Deep Thinking, it cannot be turned off' }));
                      return;
                    }
                    const newValue = !enableThink;
                    setEnableThink(newValue);
                    sendSilentCommand(newValue ? 'ENABLE' : 'DISABLE', 'thinking').then((success) => {
                      if (success === false) setEnableThink(!newValue); // Revert on failure
                    });
                  }}
                >
                  <BulbOutlined />
                  <span>{intl.formatMessage({ id: 'pages.session.deepThinking', defaultMessage: 'Deep Thinking' })}</span>
                </div>
              </Dropdown>
              <div
                className={`${styles.optionItem} ${enableSearch ? styles.optionActive : ''} ${!modelSupportInternet ? styles.optionDisabled : ''}`}
                onClick={() => {
                  if (!modelSupportInternet) {
                    message.warning(intl.formatMessage({ id: 'pages.session.modelNotSupportInternet', defaultMessage: 'Current model does not support Internet Search' }));
                    return;
                  }
                  const newValue = !enableSearch;
                  setEnableSearch(newValue);
                  sendSilentCommand(newValue ? 'ENABLE' : 'DISABLE', 'search').then((success) => {
                    if (success === false) setEnableSearch(!newValue); // Revert on failure
                  });
                }}
              >
                <SearchOutlined />
                <span>{intl.formatMessage({ id: 'pages.session.internetSearch', defaultMessage: 'Internet Search' })}</span>
              </div>
              <div
                className={`${styles.optionItem} ${enablePlan ? styles.optionActive : ''}`}
                onClick={() => {
                  const newValue = !enablePlan;
                  setEnablePlan(newValue);
                  sendSilentCommand(newValue ? 'ENABLE' : 'DISABLE', 'plan');
                }}
              >
                <UnorderedListOutlined />
                <span>{intl.formatMessage({ id: 'pages.session.enablePlan', defaultMessage: 'Enable Plan' })}</span>
              </div>
              <Dropdown
                menu={{
                  items: [
                    { key: 'DEFAULT', label: intl.formatMessage({ id: 'pages.session.permissionMode.DEFAULT', defaultMessage: 'Default (ask for risky tools)' }) },
                    { key: 'BYPASS', label: intl.formatMessage({ id: 'pages.session.permissionMode.BYPASS', defaultMessage: 'Auto Execute (no confirmation)' }) },
                    { key: 'ACCEPT_EDITS', label: intl.formatMessage({ id: 'pages.session.permissionMode.ACCEPT_EDITS', defaultMessage: 'Accept Edits (auto-approve file edits)' }) },
                    { key: 'EXPLORE', label: intl.formatMessage({ id: 'pages.session.permissionMode.EXPLORE', defaultMessage: 'Explore (read-only mode)' }) },
                    { key: 'DONT_ASK', label: intl.formatMessage({ id: 'pages.session.permissionMode.DONT_ASK', defaultMessage: 'Don\'t Ask (auto-deny risky tools)' }) },
                  ],
                  selectedKeys: [permissionMode],
                  onClick: ({ key }) => {
                    if (key === permissionMode) return;
                    const prevMode = permissionMode;
                    setPermissionMode(key);
                    sendSilentCommand('PERMISSION', key).then((success) => {
                      if (success === false) setPermissionMode(prevMode);
                    });
                  },
                }}
                trigger={['click']}
                dropdownRender={(menu) => (
                  <div className={styles.thinkingMenu}>
                    <div className={styles.menuHeader}>
                      <ThunderboltOutlined className={styles.menuHeaderIcon} />
                      <span>{intl.formatMessage({ id: 'pages.session.permissionMenuTitle', defaultMessage: 'Tool Execution Permission' })}</span>
                    </div>
                    {menu}
                  </div>
                )}
              >
                <div
                  className={`${styles.optionItem} ${permissionMode !== 'DEFAULT' ? styles.optionActive : ''}`}
                  title={intl.formatMessage({ id: 'pages.session.permissionMenuTitle', defaultMessage: 'Tool Execution Permission' })}
                >
                  <ThunderboltOutlined />
                  <span>{intl.formatMessage({ id: `pages.session.permissionMode.${permissionMode}`, defaultMessage: permissionMode })}</span>
                </div>
              </Dropdown>
              <div
                className={`${styles.optionItem} ${styles.clearOption}`}
                onClick={async () => {
                  if (!sessionId) return;
                  try {
                    const res = await getWorkspaceStatus(sessionId);
                    if (res.code === 200 && res.data?.active) {
                      Modal.confirm({
                        title: intl.formatMessage({ id: 'pages.session.stopSandboxConfirmTitle', defaultMessage: 'Stop Sandbox' }),
                        content: intl.formatMessage({ id: 'pages.session.stopSandboxConfirm', defaultMessage: 'This will stop and destroy the sandbox container. The workspace snapshot will be saved.' }),
                        okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
                        cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
                        okButtonProps: { danger: true },
                        onOk: () => sendSilentCommand('STOP_SANDBOX'),
                      });
                    } else {
                      message.warning(intl.formatMessage({ id: 'pages.session.sandboxNotActive', defaultMessage: 'Sandbox is not running for this session' }));
                    }
                  } catch {
                    message.error(intl.formatMessage({ id: 'pages.session.sandboxCheckFailed', defaultMessage: 'Failed to check sandbox status' }));
                  }
                }}
              >
                <StopOutlined />
                <span>{intl.formatMessage({ id: 'pages.session.stopSandbox', defaultMessage: 'Stop Sandbox' })}</span>
              </div>
              <Popconfirm
                title={intl.formatMessage({ id: 'pages.session.clearChatConfirmTitle', defaultMessage: 'Clear Chat History' })}
                description={intl.formatMessage({ id: 'pages.session.clearChatConfirm', defaultMessage: 'Are you sure you want to clear all chat history for this session?' })}
                onConfirm={handleClearChat}
                okText={intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' })}
                cancelText={intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
                placement="topLeft"
              >
                <div className={`${styles.optionItem} ${styles.clearOption}`}>
                  <DeleteOutlined />
                  <span>{intl.formatMessage({ id: 'pages.session.clearRecord', defaultMessage: 'Clear Record' })}</span>
                </div>
              </Popconfirm>
            </div>
            <Button
              type="primary"
              icon={loading ? <StopOutlined /> : <SendOutlined />}
              onClick={loading ? handleStop : handleSend}
              disabled={!loading && (!inputValue.trim() && imageUrls.length === 0)}
              danger={loading}
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
