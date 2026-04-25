import React, { useState, useEffect } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, List, Typography, Empty, Spin, message, Popconfirm } from 'antd';
import { PlusOutlined, DeleteOutlined, RobotOutlined, InfoCircleOutlined } from '@ant-design/icons';
import { getSessionPage, deleteSession } from '@/services/ant-design-pro/session';
import SettingsModal from './components/SettingsModal';
import DetailModal from './components/DetailModal';
import ChatWindow from './components/ChatWindow';
// @ts-ignore
import { useModel, useLocation } from '@umijs/max';

const { Text, Title } = Typography;

const SessionPage: React.FC = () => {
  const location = useLocation();
  const [sessions, setSessions] = useState<API.SessionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedSession, setSelectedSession] = useState<API.SessionItem | null>(null);
  const [settingsModalVisible, setSettingsModalVisible] = useState(false);
  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [detailSession, setDetailSession] = useState<API.SessionItem | null>(null);
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
      message.error('加载会话列表失败');
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
        message.success('删除成功');
        // 如果删除的是当前选中的会话，清除选中状态
        if (selectedSession?.id === id) {
          setSelectedSession(null);
        }
        // 刷新列表，不指定目标会话 ID
        loadSessions();
      } else {
        // 显示后端返回的具体错误信息
        message.error(res.message || '删除失败');
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || '删除失败';
      message.error(errorMsg);
    }
  };

  // 处理会话选择
  const handleSelectSession = (session: API.SessionItem) => {
    setSelectedSession(session);
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
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            会话管理
          </span>
        ),
      }}
    >
      <div style={{ display: 'flex', height: 'calc(100vh - 150px)', gap: 12, overflow: 'hidden' }}>
        {/* 左侧：会话列表 */}
        <Card
          style={{ 
            width: 300, 
            display: 'flex', 
            flexDirection: 'column',
            borderRadius: '14px',
            boxShadow: '0 1px 4px rgba(0,0,0,0.04)',
            border: '1px solid #ebebf0',
          }}
          styles={{ body: { flex: 1, overflow: 'auto', padding: '12px' } }}
          title={
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontWeight: 600, fontSize: 14 }}>会话列表</span>
              <Button 
                type="primary" 
                icon={<PlusOutlined />} 
                size="small" 
                onClick={handleCreateSession}
                style={{ borderRadius: '8px', background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', border: 'none' }}
              >
                新建
              </Button>
            </div>
          }
        >
          <Spin spinning={loading}>
            {sessions.length === 0 ? (
              <Empty description="暂无会话" style={{ marginTop: 40 }} />
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
                      padding: '10px 12px',
                      marginBottom: 4,
                      border: `1px solid ${selectedSession?.id === session.id ? 'rgba(99, 102, 241, 0.18)' : 'transparent'}`,
                      transition: 'all 0.2s',
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                      <Text 
                        ellipsis 
                        style={{ flex: 1, fontWeight: selectedSession?.id === session.id ? 600 : 400 }}
                      >
                        {session.title}
                      </Text>
                      <div style={{ display: 'flex', gap: 4 }}>
                        <Button
                          type="text"
                          size="small"
                          icon={<InfoCircleOutlined />}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleViewDetail(session);
                          }}
                        />
                        <Popconfirm
                          title="确定删除该会话吗？"
                          onConfirm={(e) => {
                            e?.stopPropagation();
                            handleDeleteSession(session.id);
                          }}
                          onCancel={(e) => e?.stopPropagation()}
                        >
                          <Button
                            type="text"
                            size="small"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={(e) => e.stopPropagation()}
                          />
                        </Popconfirm>
                      </div>
                    </div>
                  </List.Item>
                )}
              />
            )}
          </Spin>
        </Card>

        {/* 右侧：聊天窗口区域 */}
        <Card
          style={{ 
            flex: 1, 
            display: 'flex', 
            flexDirection: 'column',
            borderRadius: '14px',
            boxShadow: '0 1px 4px rgba(0,0,0,0.04)',
            border: '1px solid #ebebf0',
            overflow: 'hidden',
          }}
          styles={{ body: { flex: 1, display: 'flex', flexDirection: 'column', padding: 0, overflow: 'hidden', minHeight: 0, height: 0 } }}
        >
          {selectedSession ? (
            <>
              {/* 会话标题栏 */}
              <div style={{ 
                padding: '14px 24px',
                borderBottom: '1px solid #ebebf0',
                background: '#fff',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                flexShrink: 0,
              }}>
                <div style={{
                  width: 28, height: 28, borderRadius: 8,
                  background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 14, color: '#fff', flexShrink: 0,
                }}>
                  <RobotOutlined />
                </div>
                <Title level={5} style={{ margin: 0, fontSize: 15, fontWeight: 600 }}>
                  {selectedSession.title}
                </Title>
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
                <RobotOutlined />
              </div>
              <Text style={{ fontSize: 15, fontWeight: 600, color: '#1e1e2e' }}>选择一个会话开始对话</Text>
              <Text style={{ fontSize: 13, color: '#9ca3af' }}>或点击左侧「新建」创建新会话</Text>
            </div>
          )}
        </Card>
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
    </PageContainer>
  );
};

export default SessionPage;
