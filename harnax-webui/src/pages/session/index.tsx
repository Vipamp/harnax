import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Avatar, Button, Typography, Spin, message, Tag, Tooltip } from 'antd';
import { PlusOutlined, BulbOutlined, CloudServerOutlined, ArrowLeftOutlined, TeamOutlined, FolderOpenOutlined, ThunderboltOutlined, SettingOutlined } from '@ant-design/icons';
import { getSessionPage, deleteSession } from '@/services/ant-design-pro/session';
import { getContextUsage } from '@/services/ant-design-pro/chat';
import { getWorkspaceStatus } from '@/services/ant-design-pro/workspace';
import {
  contextUsageBasis,
  contextUsageTokenText,
  formatContextPercent,
  isAtAutoTrigger,
  isCompactionPointless,
  isContextUsageReadable,
} from './components/contextUsage';
import SettingsModal from './components/SettingsModal';
import DetailModal from './components/DetailModal';
import ChatWindow from './components/ChatWindow';
import WorkspaceDrawer from './components/WorkspaceDrawer';
import TeamArtifactsDrawer from './components/TeamArtifactsDrawer';
import SessionSkillsDrawer from './components/SessionSkillsDrawer';
import DeleteButton from '@/components/DeleteButton';
import DetailButton from '@/components/DetailButton';
import { AvatarDropdown, SelectLang, ThemeSwitcher } from '@/components';
import { useIsMobile } from '@/utils/responsive';
import dayjs from 'dayjs';
import styles from './index.less';
import { formatSessionTime, groupSessions } from './listModel';
import { loginRedirect } from '@/utils/loginRedirect';
// @ts-ignore
import { history, useModel, useLocation, useIntl } from '@umijs/max';

const { Text, Title } = Typography;

/**
 * Read-only occupancy of the context the agent holds. The caller hides it when there is no reading — an
 * absent reading means the router cannot see this context, not that the context is empty.
 */
const ContextUsageTag: React.FC<{ usage: API.ContextUsage }> = ({ usage }) => {
  const intl = useIntl();
  const rows: Array<[string, React.ReactNode]> = [
    [
      intl.formatMessage({ id: 'pages.session.context.billed', defaultMessage: 'Billed input' }),
      contextUsageTokenText(usage.lastCallInputTokens) ??
        intl.formatMessage({ id: 'pages.session.context.noneYet', defaultMessage: 'not recorded yet' }),
    ],
    [
      intl.formatMessage({ id: 'pages.session.context.estimated', defaultMessage: 'Estimated' }),
      contextUsageTokenText(usage.estimatedTokens),
    ],
    // A count of messages is not a count of tokens: `1.50K` would read as a window, not as a transcript length.
    [intl.formatMessage({ id: 'pages.session.context.messages', defaultMessage: 'Messages' }), usage.messageCount],
    [
      `${intl.formatMessage({ id: 'pages.session.context.window', defaultMessage: 'Window' })} · ${intl.formatMessage({
        id: `pages.session.context.windowSource.${usage.windowSource || 'FALLBACK'}`,
        defaultMessage: usage.windowSource || 'FALLBACK',
      })}`,
      contextUsageTokenText(usage.contextWindow),
    ],
    [
      intl.formatMessage({ id: 'pages.session.context.trigger', defaultMessage: 'Auto-compaction at' }),
      contextUsageTokenText(usage.triggerTokens) ?? '-',
    ],
  ];
  return (
    <Tooltip
      title={
        <div style={{ minWidth: 190 }}>
          <div style={{ fontWeight: 600, marginBottom: 4 }}>
            {intl.formatMessage({ id: 'pages.session.context.usage', defaultMessage: 'Context usage' })}
          </div>
          {rows.map(([label, value]) => (
            <div key={label} style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ opacity: 0.75 }}>{label}</span>
              <span>{value}</span>
            </div>
          ))}
        </div>
      }
    >
      <Tag color={isAtAutoTrigger(usage) ? 'orange' : 'default'} style={{ margin: 0, flexShrink: 0 }}>
        {formatContextPercent(usage.ratio)} ·{' '}
        {intl.formatMessage({
          id: `pages.session.context.basis.${contextUsageBasis(usage)}`,
          defaultMessage: contextUsageBasis(usage),
        })}
      </Tag>
    </Tooltip>
  );
};

const SessionPage: React.FC = () => {
  const intl = useIntl();
  const location = useLocation();
  const isMobile = useIsMobile();
  const [sessions, setSessions] = useState<API.SessionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedSession, setSelectedSession] = useState<API.SessionItem | null>(null);
  const [settingsModalVisible, setSettingsModalVisible] = useState(false);
  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [detailSession, setDetailSession] = useState<API.SessionItem | null>(null);
  const [workspaceDrawerVisible, setWorkspaceDrawerVisible] = useState(false);
  const [artifactsDrawerVisible, setArtifactsDrawerVisible] = useState(false);
  /** Skills this conversation drafted, and which of them the human has let into this session. */
  const [sessionSkillsVisible, setSessionSkillsVisible] = useState(false);
  /** Null means the router has no reading for this session, and then the header shows nothing at all. */
  const [contextUsage, setContextUsage] = useState<API.ContextUsage | null>(null);
  /** Counts the asks, so a reply to a session the user has left cannot land after the newer one and redraw this title. */
  const contextUsageGeneration = useRef(0);
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

  // 聊天壳走 layout:false，全局 onPageChange 守卫不生效，这里自挂一份（规格 §3.4-4）
  useEffect(() => {
    if (initialState && !initialState.currentUser) {
      history.replace({ pathname: '/login', search: loginRedirect(history.location) });
    }
  }, [initialState]);

  // 加载会话列表
  const loadSessions = async (targetSessionId?: number) => {
    setLoading(true);
    try {
      const res = await getSessionPage({ pageNum: 1, pageSize: 100 });
      if (res.code === 200 && res.data?.records) {
        setSessions(res.data.records);
        
        // 如果指定了目标会话 ID，选中它
        if (targetSessionId) {
          const targetSession = res.data.records.find(s => s.id === targetSessionId);
          if (targetSession) {
            setSelectedSession(targetSession);
          }
        } else if (res.data.records.length > 0 && !selectedSession) {
          // 否则默认选中第一个会话
          setSelectedSession(res.data.records[0]);
        }
      }
    } catch (error) {
      console.error('加载会话列表失败', error);
      message.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // 从 URL 参数中获取会话 ID
    const params = new URLSearchParams(location.search);
    const sessionId = params.get('id');
    
    if (sessionId) {
      loadSessions(parseInt(sessionId, 10));
    } else {
      loadSessions();
    }
  }, []);

  /** A business failure comes back without data (code 500 when no instance holds the session, code 200 with null data when it was never bound). */
  const loadContextUsage = useCallback(async (sessionId?: string) => {
    contextUsageGeneration.current += 1;
    const generation = contextUsageGeneration.current;
    if (!sessionId) {
      setContextUsage(null);
      return;
    }
    try {
      const res = await getContextUsage(sessionId);
      const usage = res?.data;
      if (generation !== contextUsageGeneration.current) return;
      setContextUsage(usage && isContextUsageReadable(res) ? usage : null);
    } catch {
      if (generation === contextUsageGeneration.current) setContextUsage(null);
    }
  }, []);

  useEffect(() => {
    // What the last session occupied is no answer about this one — drop it now rather than when the new read
    // happens to land.
    setContextUsage(null);
    loadContextUsage(selectedSession?.sessionId);
  }, [selectedSession?.sessionId, loadContextUsage]);

  // 处理创建新会话
  const handleCreateSession = () => {
    setSettingsModalVisible(true);
  };

  // 处理删除会话
  const handleDeleteSession = async (id: number) => {
    try {
      const res = await deleteSession(id);
      if (res.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
        // 如果删除的是当前选中的会话，清除选中状态
        if (selectedSession?.id === id) {
          setSelectedSession(null);
        }
        // 刷新列表，不指定目标会话 ID
        loadSessions();
      } else {
        // 显示后端返回的具体错误信息
        message.error(res.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' }));
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
      message.error(errorMsg);
    }
  };

  // 处理会话选择
  const handleSelectSession = (session: API.SessionItem) => {
    setSelectedSession(session);
  };

  // 移动端：返回列表
  const handleBackToList = () => {
    setSelectedSession(null);
  };

  // 处理设置弹窗关闭
  const handleSettingsModalClose = () => {
    setSettingsModalVisible(false);
  };

  // 处理设置弹窗提交成功
  const handleSettingsSuccess = (session: API.SessionItem) => {
    loadSessions();
    // 选中新创建的会话
    setSelectedSession(session);
  };

  // 处理查看详情
  const handleViewDetail = (session: API.SessionItem) => {
    setSelectedSession(session);
    setDetailSession(session);
    setDetailModalVisible(true);
  };

  const now = dayjs();
  const sessionGroups = groupSessions(sessions, now);

  return (
    <>
      <div
        style={{
          display: 'flex',
          height: '100vh',
          overflow: 'hidden',
          flexDirection: isMobile ? 'column' : 'row',
        }}
      >
        {/* 左侧：会话列表 */}
        {(!isMobile || !selectedSession) && (
          <div
            style={{
              width: isMobile ? '100%' : 300,
              flex: isMobile ? 1 : undefined,
              display: 'flex',
              flexDirection: 'column',
              background: 'var(--vip-bg-container)',
              borderRight: isMobile ? 'none' : '1px solid var(--vip-border)',
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                padding: '14px 16px',
                borderBottom: '1px solid var(--vip-border)',
                flexShrink: 0,
              }}
            >
              <span style={{ fontWeight: 600, fontSize: 14 }}>{intl.formatMessage({ id: 'pages.session.listTitle', defaultMessage: 'Session List' })}</span>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                size="small"
                onClick={handleCreateSession}
                style={{ borderRadius: '8px', background: 'var(--vip-primary)', border: 'none' }}
              >
                {intl.formatMessage({ id: 'pages.common.create', defaultMessage: 'Create' })}
              </Button>
            </div>
            <div style={{ flex: 1, overflow: 'auto', padding: isMobile ? 8 : 12 }}>
              <Spin spinning={loading}>
                {sessions.length === 0 ? (
                  <div className={styles.listEmpty}>
                    <div className={styles.listEmptyOrb}>
                      <span className={styles.listEmptyDot} />
                      <span className={styles.listEmptyDot} />
                      <span className={styles.listEmptyDot} />
                    </div>
                    <div className={styles.listEmptyTitle}>
                      {intl.formatMessage({ id: 'pages.session.emptyList.title', defaultMessage: 'No sessions yet' })}
                    </div>
                    <div className={styles.listEmptyHint}>
                      {intl.formatMessage({ id: 'pages.session.emptyList.hint', defaultMessage: 'Hit "New" to create one' })}
                    </div>
                  </div>
                ) : (
                  sessionGroups.map((group) => (
                    <div key={group.key}>
                      <div className={styles.groupLabel}>
                        {intl.formatMessage({ id: `pages.session.group.${group.key}`, defaultMessage: group.key })}
                      </div>
                      {group.items.map((session) => {
                        const timeText = formatSessionTime(session.updateTime ?? '', now);
                        const selected = selectedSession?.id === session.id;
                        return (
                          <div
                            key={session.id}
                            className={`${styles.sessionRow} ${selected ? styles.sessionRowSelected : ''}`}
                            onClick={() => handleSelectSession(session)}
                          >
                            <div className={styles.sessionRowTop}>
                              {session.teamId && (
                                <Tag color="blue" className={styles.sessionTeamTag} icon={<TeamOutlined />}>
                                  {intl.formatMessage({ id: 'pages.session.teamTag', defaultMessage: 'Team' })}
                                </Tag>
                              )}
                              <Text ellipsis className={`${styles.sessionRowTitle} ${selected ? styles.sessionRowTitleActive : ''}`}>
                                {session.title}
                              </Text>
                              <div className={styles.sessionRowActions}>
                                <DetailButton onClick={() => handleViewDetail(session)} />
                                <DeleteButton onConfirm={() => handleDeleteSession(session.id)} />
                              </div>
                            </div>
                            {(session.sessionDescription || timeText) && (
                              <div className={styles.sessionRowMeta}>
                                <span className={styles.sessionRowDesc}>{session.sessionDescription}</span>
                                {timeText && <span className={styles.sessionRowTime}>{timeText}</span>}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  ))
                )}
              </Spin>
            </div>
            {!isMobile && (
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 4,
                  padding: '8px 12px',
                  borderTop: '1px solid var(--vip-border)',
                  flexShrink: 0,
                }}
              >
                <AvatarDropdown>
                  <span style={{ cursor: 'pointer', display: 'inline-flex' }}>
                    <Avatar size={24} src={currentUser?.avatar} />
                  </span>
                </AvatarDropdown>
                <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <SelectLang />
                  <ThemeSwitcher />
                  <Button type="text" size="small" icon={<SettingOutlined />} onClick={() => history.push('/welcome')}>
                    设置
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* 右侧：聊天窗口区域 */}
        {(!isMobile || selectedSession) && (
          <div
            style={{
              flex: 1,
              minWidth: 0,
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
              background: 'var(--vip-bg-container)',
            }}
          >
            {selectedSession ? (
              <>
                {/* 会话标题栏 */}
                <div style={{
                  padding: isMobile ? '10px 12px' : '14px 24px',
                  borderBottom: '1px solid var(--vip-border)',
                  background: 'var(--vip-bg-container)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: isMobile ? 6 : 10,
                  flexShrink: 0,
                }}>
                  {isMobile && (
                    <Button
                      type="text"
                      icon={<ArrowLeftOutlined />}
                      onClick={handleBackToList}
                      size="small"
                      style={{ flexShrink: 0 }}
                    />
                  )}
                  <div style={{
                    width: isMobile ? 24 : 28, height: isMobile ? 24 : 28, borderRadius: 8,
                    background: 'var(--vip-gradient-primary)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: isMobile ? 12 : 14, color: '#fff', flexShrink: 0,
                  }}>
                    <BulbOutlined />
                  </div>
                  <Title level={5} style={{ margin: 0, fontSize: isMobile ? 14 : 15, fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {selectedSession.title}
                  </Title>
                  {contextUsage && <ContextUsageTag usage={contextUsage} />}
                  {!isMobile && <div style={{ flex: 1 }} />}
                  <Button
                    type="text"
                    icon={<CloudServerOutlined />}
                    size="small"
                    onClick={async () => {
                      if (!selectedSession?.sessionId) return;
                      try {
                        const res = await getWorkspaceStatus(selectedSession.sessionId);
                        if (res.code === 200 && res.data?.active) {
                          setWorkspaceDrawerVisible(true);
                        } else {
                          message.warning(intl.formatMessage({ id: 'pages.session.sandboxNotActive', defaultMessage: 'Sandbox is not running for this session' }));
                        }
                      } catch {
                        message.error(intl.formatMessage({ id: 'pages.session.sandboxCheckFailed', defaultMessage: 'Failed to check sandbox status' }));
                      }
                    }}
                  >
                    {isMobile ? '' : 'Workspace'}
                  </Button>
                  {selectedSession.teamId && (
                    <Button
                      type="text"
                      icon={<FolderOpenOutlined />}
                      size="small"
                      onClick={() => setArtifactsDrawerVisible(true)}
                    >
                      {isMobile ? '' : intl.formatMessage({ id: 'pages.session.artifacts.entry', defaultMessage: 'Artifacts' })}
                    </Button>
                  )}
                  <Button
                    type="text"
                    icon={<ThunderboltOutlined />}
                    size="small"
                    onClick={() => setSessionSkillsVisible(true)}
                  >
                    {isMobile ? '' : intl.formatMessage({ id: 'pages.session.skills.entry', defaultMessage: 'Session skills' })}
                  </Button>
                </div>

                {/* 聊天窗口 */}
                <ChatWindow
                  sessionId={selectedSession.sessionId}
                  compactionSkippable={isCompactionPointless(contextUsage)}
                  onContextChanged={() => loadContextUsage(selectedSession.sessionId)}
                />
              </>
            ) : (
              <div className={styles.noSession}>
                <div className={styles.noSessionOrb}>
                  <span className={styles.noSessionOrbCore}>
                    <BulbOutlined />
                  </span>
                </div>
                <div className={styles.noSessionTitle}>
                  {intl.formatMessage({ id: 'pages.session.noSession.title', defaultMessage: 'No session to open yet' })}
                </div>
                <div className={styles.noSessionHint}>
                  {intl.formatMessage({ id: 'pages.session.noSession.hint', defaultMessage: 'Create one with "New" on the left' })}
                </div>
                <button type="button" className={styles.noSessionInputCard} onClick={handleCreateSession}>
                  {intl.formatMessage({ id: 'pages.session.inputPlaceholder.noSession', defaultMessage: 'Create a session first, then start chatting…' })}
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 创建会话弹窗 */}
      <SettingsModal
        visible={settingsModalVisible}
        onCancel={handleSettingsModalClose}
        onSuccess={handleSettingsSuccess}
      />

      {/* 详情弹窗 */}
      <DetailModal
        visible={detailModalVisible}
        session={detailSession}
        onCancel={() => setDetailModalVisible(false)}
      />

      {/* Workspace 文件浏览器 */}
      <WorkspaceDrawer
        visible={workspaceDrawerVisible}
        sessionId={selectedSession?.sessionId}
        onClose={() => setWorkspaceDrawerVisible(false)}
      />

      {/* 团队成员产物 */}
      <TeamArtifactsDrawer
        visible={artifactsDrawerVisible}
        sessionId={selectedSession?.sessionId}
        onClose={() => setArtifactsDrawerVisible(false)}
      />

      {/* Skills this session wrote */}
      <SessionSkillsDrawer
        visible={sessionSkillsVisible}
        sessionId={selectedSession?.sessionId}
        onClose={() => setSessionSkillsVisible(false)}
      />
    </>
  );
};

export default SessionPage;
