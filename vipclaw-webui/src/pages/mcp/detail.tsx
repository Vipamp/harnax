import { PageContainer } from '@ant-design/pro-components';
import { Card, Descriptions, Tag, Typography, Table, Spin, Empty, Button, Breadcrumb, message } from 'antd';
import { 
  ArrowLeftOutlined, 
  ToolOutlined, 
  ApiOutlined,
  ClockCircleOutlined,
  UserOutlined,
  LinkOutlined,
  CodeOutlined
} from '@ant-design/icons';
import React, { useEffect, useState } from 'react';
// @ts-ignore
import { useModel, useLocation, history } from '@umijs/max';
import { getMcpServerById, getMcpTools } from '@/services/ant-design-pro/mcp';

const { Text, Title } = Typography;

interface ToolParameter {
  name: string;
  type: string;
  description: string;
}

interface ToolItem {
  name: string;
  parameters: ToolParameter[];
}

const McpDetail: React.FC = () => {
  const location = useLocation();
  const [loading, setLoading] = useState(false);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [mcpInfo, setMcpInfo] = useState<API.McpServerItem | null>(null);
  const [tools, setTools] = useState<ToolItem[]>([]);

  // 从 URL 中获取 MCP ID
  const pathParts = location.pathname.split('/');
  const mcpId = pathParts[pathParts.length - 1];

  const MCP_TYPE_CONFIG: Record<string, { color: string; label: string; gradient: string }> = {
    stdio: { 
      color: '#4f6ef7', 
      label: 'STDIO',
      gradient: 'linear-gradient(135deg, #4f6ef7 0%, #667eea 100%)'
    },
    sse: { 
      color: '#52c41a', 
      label: 'SSE',
      gradient: 'linear-gradient(135deg, #52c41a 0%, #73d13d 100%)'
    },
    streamablehttp: { 
      color: '#fa8c16', 
      label: 'Streamable HTTP',
      gradient: 'linear-gradient(135deg, #fa8c16 0%, #ffc53d 100%)'
    },
  };

  useEffect(() => {
    if (mcpId) {
      loadMcpDetail(parseInt(mcpId, 10));
      loadTools(parseInt(mcpId, 10));
    }
  }, [mcpId]);

  const loadMcpDetail = async (mcpId: number) => {
    setLoading(true);
    try {
      const res = await getMcpServerById(mcpId);
      if (res.code === 200 && res.data) {
        setMcpInfo(res.data);
      }
    } catch (error) {
      console.error('加载 MCP 详情失败', error);
    } finally {
      setLoading(false);
    }
  };

  const loadTools = async (mcpId: number) => {
    setToolsLoading(true);
    try {
      const res = await getMcpTools(mcpId);
      if (res.code === 200 && res.data) {
        setTools(res.data);
      } else {
        // 显示后端返回的错误信息
        const errorMsg = res.message || '加载工具列表失败';
        message.error(errorMsg);
      }
    } catch (error: any) {
      console.error('加载工具列表失败', error);
      // 显示错误信息给用户
      const errorMsg = error?.message || error?.info?.errorMessage || '加载工具列表失败';
      message.error(errorMsg);
    } finally {
      setToolsLoading(false);
    }
  };

  const handleBack = () => {
    history.push('/context/mcp');
  };

  const columns = [
    {
      title: '工具名称',
      dataIndex: 'name',
      key: 'name',
      width: 280,
      render: (text: string) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            width: 32,
            height: 32,
            borderRadius: '8px',
            background: 'linear-gradient(135deg, #f0f5ff 0%, #e6f0ff 100%)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center'
          }}>
            <ToolOutlined style={{ color: '#4f6ef7', fontSize: 14 }} />
          </div>
          <Text strong style={{ color: '#1a1a2e', fontSize: 14 }}>
            {text}
          </Text>
        </div>
      ),
    },
    {
      title: '参数列表',
      dataIndex: 'parameters',
      key: 'parameters',
      render: (parameters: ToolParameter[]) => {
        if (!parameters || parameters.length === 0) {
          return <Text type="secondary">无参数</Text>;
        }
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {parameters.map((param, index) => (
              <div
                key={index}
                style={{
                  padding: '12px 16px',
                  background: '#fafbfc',
                  border: '1px solid #f0f0f5',
                  borderRadius: '10px',
                  transition: 'all 0.3s ease'
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                  <Tag 
                    color="blue" 
                    style={{ 
                      borderRadius: '6px', 
                      fontWeight: 500,
                      margin: 0
                    }}
                  >
                    {param.name}
                  </Tag>
                  <Tag 
                    color="cyan" 
                    style={{ 
                      borderRadius: '6px', 
                      fontWeight: 500,
                      margin: 0
                    }}
                  >
                    {param.type}
                  </Tag>
                  <Text style={{ fontSize: 13, color: '#595959', flex: 1 }}>
                    {param.description || '无描述'}
                  </Text>
                </div>
              </div>
            ))}
          </div>
        );
      },
    },
  ];

  if (!mcpInfo && !loading) {
    return (
      <PageContainer>
        <Empty description="MCP 服务不存在" />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      header={{
        title: (
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={handleBack}
              style={{ 
                borderRadius: '8px',
                padding: '8px 12px',
                transition: 'all 0.3s ease'
              }}
            >
              返回
            </Button>
            <div style={{ width: 1, height: 24, background: '#e8e8e8' }} />
            <Title level={3} style={{ margin: 0, fontWeight: 600 }}>
              <ApiOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
              MCP 服务详情
            </Title>
          </div>
        ),
        breadcrumb: {
          items: [
            { title: <a onClick={() => history.push('/context/mcp')}>MCP 管理</a> },
            { title: mcpInfo?.name || 'MCP 详情' }
          ]
        }
      }}
    >
      <Spin spinning={loading}>
        {mcpInfo && (
          <>
            {/* MCP 基本信息 */}
            <Card
              style={{
                marginBottom: 24,
                borderRadius: '16px',
                border: '1px solid #f0f0f5',
                boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
                overflow: 'hidden'
              }}
              styles={{ body: { padding: 0 } }}
            >
              {/* 顶部标题栏 */}
              <div style={{ 
                padding: '20px 24px', 
                background: 'linear-gradient(135deg, #f0f5ff 0%, #e6f0ff 100%)',
                borderBottom: '1px solid #d6e4ff'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <div style={{
                      width: 48,
                      height: 48,
                      borderRadius: '12px',
                      background: 'linear-gradient(135deg, #4f6ef7 0%, #667eea 100%)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      boxShadow: '0 4px 12px rgba(79, 110, 247, 0.25)'
                    }}>
                      <ApiOutlined style={{ fontSize: 22, color: '#fff' }} />
                    </div>
                    <div>
                      <Text strong style={{ fontSize: 20, color: '#1a1a2e', display: 'block', marginBottom: 4 }}>
                        {mcpInfo.name}
                      </Text>
                      <div style={{ display: 'flex', gap: 8 }}>
                        <Tag 
                          color={MCP_TYPE_CONFIG[mcpInfo.type]?.color || '#999'}
                          style={{ borderRadius: '6px', fontWeight: 500 }}
                        >
                          {MCP_TYPE_CONFIG[mcpInfo.type]?.label || mcpInfo.type}
                        </Tag>
                        {mcpInfo.isPublic === 1 && (
                          <Tag color="blue" style={{ borderRadius: '6px', fontWeight: 500 }}>公开</Tag>
                        )}
                        <Tag 
                          color={mcpInfo.status === 1 ? 'success' : 'default'} 
                          style={{ borderRadius: '6px', fontWeight: 500 }}
                        >
                          {mcpInfo.status === 1 ? '启用' : '禁用'}
                        </Tag>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              {/* 详细信息 - 参考技能详情页设计 */}
              <Descriptions 
                column={1} 
                size="small"
                styles={{ 
                  label: { color: '#8c8c8c', fontWeight: 500 },
                  content: { color: '#262626' }
                }}
                style={{ padding: '24px' }}
              >
                <Descriptions.Item label="MCP 描述" span={1}>
                  <Text style={{ lineHeight: 1.6 }}>{mcpInfo.description || '暂无描述'}</Text>
                </Descriptions.Item>
                <Descriptions.Item label="连接方式" span={1}>
                  <Text>
                    <CodeOutlined style={{ marginRight: 6, color: '#4f6ef7' }} />
                    {mcpInfo.type === 'stdio' ? mcpInfo.command : mcpInfo.url || '-'}
                  </Text>
                </Descriptions.Item>
                <Descriptions.Item label={<span><UserOutlined style={{ marginRight: 4 }} />创建人</span>}>
                  <Text>{mcpInfo.creator || '未知'}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={<span><ClockCircleOutlined style={{ marginRight: 4 }} />创建时间</span>}>
                  <Text>{mcpInfo.createTime?.replace('T', ' ') || '未知'}</Text>
                </Descriptions.Item>
              </Descriptions>
            </Card>

            {/* 工具列表 */}
            <Card
              title={
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <ToolOutlined style={{ color: '#4f6ef7' }} />
                  工具列表
                  {tools.length > 0 && (
                    <Tag 
                      color="blue" 
                      style={{ 
                        marginLeft: 8,
                        borderRadius: '6px',
                        fontWeight: 500
                      }}
                    >
                      {tools.length} 个工具
                    </Tag>
                  )}
                </span>
              }
              style={{
                borderRadius: '16px',
                border: '1px solid #f0f0f5',
                boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
                overflow: 'hidden'
              }}
              styles={{ body: { padding: 0 } }}
            >
              <Spin spinning={toolsLoading}>
                <Table
                  columns={columns}
                  dataSource={tools}
                  rowKey="name"
                  pagination={false}
                  locale={{
                    emptyText: toolsLoading ? '加载中...' : '暂无工具',
                  }}
                  style={{ borderRadius: '12px' }}
                  components={{
                    header: {
                      cell: (props: any) => (
                        <th 
                          {...props} 
                          style={{ 
                            ...props.style, 
                            background: '#fafbfc',
                            fontWeight: 600,
                            color: '#262626',
                            borderBottom: '2px solid #f0f0f5'
                          }}
                        />
                      ),
                    },
                  }}
                />
              </Spin>
            </Card>
          </>
        )}
      </Spin>
    </PageContainer>
  );
};

export default McpDetail;
