import React, { useState, useEffect } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, List, Typography, Empty, Spin, message, Popconfirm } from 'antd';
import { PlusOutlined, DeleteOutlined, MessageOutlined, InfoCircleOutlined } from '@ant-design/icons';
import { getSessionPage, deleteSession } from '@/services/ant-design-pro/session';
import SettingsModal from './components/SettingsModal';
import DetailModal from './components/DetailModal';
// @ts-ignore
import { useModel } from '@umijs/max';

const { Text, Title } = Typography;

const SessionPage: React.FC = () => {
  const [sessions, setSessions] = useState<API.SessionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedSession, setSelectedSession] = useState<API.SessionItem | null>(null);
  const [settingsModalVisible, setSettingsModalVisible] = useState(false);
  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [detailSession, setDetailSession] = useState<API.SessionItem | null>(null);
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

  // 加载会话列表
  const loadSessions = async () => {
    setLoading(true);
    try {
      const res = await getSessionPage({ pageNum: 1, pageSize: 100 });
      if (res.code === 200 && res.data?.records) {
        setSessions(res.data.records);
        // 默认选中第一个会话
        if (res.data.records.length > 0 && !selectedSession) {
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
    loadSessions();
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
        loadSessions();
      } else {
        message.error(res.message || '删除失败');
      }
    } catch (error) {
      message.error('删除失败');
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
    setDetailSession(session);
    setDetailModalVisible(true);
  };

  return (
    <PageContainer>
      <div style={{ display: 'flex', height: 'calc(100vh - 180px)', gap: 16 }}>
        {/* 左侧：会话列表 */}
        <Card
          style={{ width: 300, display: 'flex', flexDirection: 'column' }}
          styles={{ body: { flex: 1, overflow: 'auto', padding: '12px' } }}
          title={
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>会话列表</span>
              <Button type="primary" icon={<PlusOutlined />} size="small" onClick={handleCreateSession}>
                新建会话
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
                      backgroundColor: selectedSession?.id === session.id ? '#e6f4ff' : 'transparent',
                      borderRadius: 8,
                      padding: '8px 12px',
                      marginBottom: 4,
                      border: `1px solid ${selectedSession?.id === session.id ? '#1890ff' : 'transparent'}`,
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
          style={{ flex: 1, display: 'flex', flexDirection: 'column' }}
          styles={{ body: { flex: 1, display: 'flex', flexDirection: 'column' } }}
        >
          {selectedSession ? (
            <>
              {/* 会话标题栏 */}
              <div style={{ 
                padding: '12px 16px', 
                borderBottom: '1px solid #f0f0f0'
              }}>
                <Title level={4} style={{ margin: 0 }}>{selectedSession.title}</Title>
              </div>
              
              {/* 聊天内容区域 - 暂时保留空间 */}
              <div style={{ 
                flex: 1, 
                display: 'flex', 
                flexDirection: 'column',
                alignItems: 'center', 
                justifyContent: 'center',
                color: '#999'
              }}>
                <MessageOutlined style={{ fontSize: 64, marginBottom: 16 }} />
                <Text type="secondary">AI 聊天窗口开发中...</Text>
              </div>
            </>
          ) : (
            <div style={{ 
              flex: 1, 
              display: 'flex', 
              flexDirection: 'column',
              alignItems: 'center', 
              justifyContent: 'center',
              color: '#999'
            }}>
              <MessageOutlined style={{ fontSize: 64, marginBottom: 16 }} />
              <Text type="secondary">请选择或创建一个会话</Text>
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
