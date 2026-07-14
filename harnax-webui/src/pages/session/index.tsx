import React, { useState, useEffect } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, List, Typography, Empty, Spin, message } from 'antd';
import { PlusOutlined, BulbOutlined, CloudServerOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { getSessionPage, deleteSession } from '@/services/ant-design-pro/session';
import { getWorkspaceStatus } from '@/services/ant-design-pro/workspace';
import SettingsModal from './components/SettingsModal';
import DetailModal from './components/DetailModal';
import ChatWindow from './components/ChatWindow';
import WorkspaceDrawer from './components/WorkspaceDrawer';
import DeleteButton from '@/components/DeleteButton';
import DetailButton from '@/components/DetailButton';
import { useIsMobile } from '@/utils/responsive';
// @ts-ignore
import { useModel, useLocation, useIntl } from '@umijs/max';

const { Text, Title } = Typography;

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
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

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

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <BulbOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({
              id: 'menu.agent.session',
              defaultMessage: 'Session',
            })}
          </span>
        ),
      }}
    >
      <div style={{
        display: 'flex',
        height: isMobile ? 'calc(100vh - 120px)' : 'calc(100vh - 150px)',
        gap: isMobile ? 0 : 12,
        overflow: 'hidden',
        flexDirection: isMobile ? 'column' : 'row',
      }}>
        {/* 左侧：会话列表 */}
        {(!isMobile || !selectedSession) && (
        <Card
          style={{ 
            width: isMobile ? '100%' : 300, 
            display: 'flex', 
            flexDirection: 'column',
            borderRadius: '14px',
            boxShadow: '0 1px 4px rgba(0,0,0,0.04)',
            border: '1px solid var(--vip-border)',
            background: 'var(--vip-bg-container)',
            flex: isMobile ? 1 : undefined,
          }}
          styles={{ body: { flex: 1, overflow: 'auto', padding: isMobile ? '8px' : '12px' } }}
          title={
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
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
          }
        >
          <Spin spinning={loading}>
            {sessions.length === 0 ? (
              <Empty description={intl.formatMessage({ id: 'pages.session.noSessions', defaultMessage: 'No sessions' })} style={{ marginTop: 40 }} />
            ) : (
              <List
                dataSource={sessions}
                renderItem={(session) => (
                  <List.Item
                    key={session.id}
                    onClick={() => handleSelectSession(session)}
                    style={{
                      cursor: 'pointer',
                      backgroundColor: selectedSession?.id === session.id ? 'rgba(99, 102, 241, 0.06)' : 'transparent',
                      borderRadius: 10,
                      padding: isMobile ? '14px 12px' : '10px 12px',
                      marginBottom: 4,
                      border: `1px solid ${selectedSession?.id === session.id ? 'rgba(99, 102, 241, 0.18)' : 'transparent'}`,
                      transition: 'all 0.2s',
                      minHeight: isMobile ? 48 : undefined,
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                      <Text 
                        ellipsis 
                        style={{ flex: 1, fontWeight: selectedSession?.id === session.id ? 600 : 400, fontSize: isMobile ? 15 : 14 }}
                      >
                        {session.title}
                      </Text>
                      <div style={{ display: 'flex', gap: 4 }}>
                        <DetailButton onClick={() => handleViewDetail(session)} />
                        <DeleteButton onConfirm={() => handleDeleteSession(session.id)} />
                      </div>
                    </div>
                  </List.Item>
                )}
              />
            )}
          </Spin>
        </Card>
        )}

        {/* 右侧：聊天窗口区域 */}
        {(!isMobile || selectedSession) && (
        <Card
          style={{ 
            flex: 1, 
            display: 'flex', 
            flexDirection: 'column',
            borderRadius: isMobile ? '0' : '14px',
            boxShadow: isMobile ? 'none' : '0 1px 4px rgba(0,0,0,0.04)',
            border: isMobile ? 'none' : '1px solid var(--vip-border)',
            background: 'var(--vip-bg-container)',
            overflow: 'hidden',
          }}
          styles={{ body: { flex: 1, display: 'flex', flexDirection: 'column', padding: 0, overflow: 'hidden', minHeight: 0, height: 0 } }}
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
                  background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: isMobile ? 12 : 14, color: '#fff', flexShrink: 0,
                }}>
                  <BulbOutlined />
                </div>
                <Title level={5} style={{ margin: 0, fontSize: isMobile ? 14 : 15, fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {selectedSession.title}
                </Title>
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
              </div>
              
              {/* 聊天窗口 */}
              <ChatWindow sessionId={selectedSession.sessionId} />
            </>
          ) : (
            <div style={{ 
              flex: 1, 
              display: 'flex', 
              flexDirection: 'column',
              alignItems: 'center', 
              justifyContent: 'center',
              gap: 8,
            }}>
              <div style={{
                width: 56, height: 56, borderRadius: 16,
                background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 26, color: '#fff',
                boxShadow: '0 6px 20px rgba(99,102,241,0.2)',
                marginBottom: 8,
              }}>
                <BulbOutlined />
              </div>
              <Text style={{ fontSize: 15, fontWeight: 600, color: 'var(--vip-text-primary)' }}>{intl.formatMessage({ id: 'pages.session.selectSession', defaultMessage: 'Select a session to start conversation' })}</Text>
              <Text style={{ fontSize: 13, color: 'var(--vip-text-tertiary)' }}>{intl.formatMessage({ id: 'pages.session.createNew', defaultMessage: 'or click "Create" on the left to create a new session' })}</Text>
            </div>
          )}
        </Card>
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
    </PageContainer>
  );
};

export default SessionPage;
