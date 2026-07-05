import { useIntl } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Tag, Typography, Table, Spin, Empty, Button, Breadcrumb, message } from 'antd';
import { 
  ToolOutlined, 
  ApiOutlined,
  LinkOutlined
} from '@ant-design/icons';
import React, { useEffect, useState } from 'react';
// @ts-ignore
import { useModel, useLocation, history } from '@umijs/max';
import { getMcpServerById, getMcpTools } from '@/services/ant-design-pro/mcp';
import BackButton from '@/components/BackButton';
import DetailPageHeader from '@/components/DetailPageHeader';

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
  const intl = useIntl();
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
      console.error(intl.formatMessage({ id: 'pages.message.loadMcpDetailFailed', defaultMessage: 'Failed to load MCP details' }), error);
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
        const errorMsg = res.message || intl.formatMessage({ id: 'pages.message.loadToolListFailed', defaultMessage: 'Failed to load tool list' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      console.error(intl.formatMessage({ id: 'pages.message.loadToolListFailed', defaultMessage: 'Failed to load tool list' }), error);
      // 显示错误信息给用户
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.loadToolListFailed', defaultMessage: 'Failed to load tool list' });
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
      title: intl.formatMessage({ id: 'pages.mcp.detail.toolName', defaultMessage: 'Tool Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 280,
      render: (text: string) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            width: 32,
            height: 32,
            borderRadius: '8px',
            background: 'var(--vip-bg-layout)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center'
          }}>
            <ToolOutlined style={{ color: 'var(--vip-primary)', fontSize: 14 }} />
          </div>
          <Text strong style={{ color: 'var(--vip-text-primary)', fontSize: 14 }}>
            {text}
          </Text>
        </div>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.mcp.detail.parameterList', defaultMessage: 'Parameter List' }),
      dataIndex: 'parameters',
      key: 'parameters',
      render: (parameters: ToolParameter[]) => {
        if (!parameters || parameters.length === 0) {
          return <Text type="secondary">{intl.formatMessage({ id: 'pages.mcp.detail.noParameters', defaultMessage: 'No parameters' })}</Text>;
        }
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {parameters.map((param, index) => (
              <div
                key={index}
                style={{
                  padding: '12px 16px',
                  background: 'var(--vip-bg-layout)',
                  border: '1px solid var(--vip-border)',
                  borderRadius: '10px',
                  transition: 'all 0.3s ease'
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                  <Tag 
                    color="blue" 
                    style={{ 
                      fontWeight: 500,
                      margin: 0
                    }}
                  >
                    {param.name}
                  </Tag>
                  <Tag 
                    color="cyan" 
                    style={{ 
                      fontWeight: 500,
                      margin: 0
                    }}
                  >
                    {param.type}
                  </Tag>
                  <Text style={{ fontSize: 13, color: 'var(--vip-text-secondary)', flex: 1 }}>
                    {param.description || intl.formatMessage({ id: 'pages.common.noDescription', defaultMessage: 'No description' })}
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
        <Empty description={intl.formatMessage({ id: 'pages.mcp.detail.mcpNotFound', defaultMessage: 'MCP service not found' })} />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      header={{
        title: (
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <BackButton onClick={handleBack} />
            <div style={{ width: 1, height: 24, background: '#e8e8e8' }} />
            <span style={{ fontSize: 18, fontWeight: 600, color: 'var(--vip-text-primary)', margin: 0 }}>
              <ApiOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
              {intl.formatMessage({ id: 'pages.mcp.detail.pageTitle', defaultMessage: 'MCP Service Detail' })}
            </span>
          </div>
        ),
        breadcrumb: {
          items: [
            { title: <a onClick={() => history.push('/context/mcp')}>{intl.formatMessage({ id: 'menu.context.mcp', defaultMessage: 'MCP Management' })}</a> },
            { title: mcpInfo?.name || intl.formatMessage({ id: 'pages.mcp.detail', defaultMessage: 'MCP Detail' }) }
          ]
        }
      }}
    >
      <Spin spinning={loading}>
        {mcpInfo && (
          <>
            {/* MCP 基本信息 - 使用公共组件 */}
            <DetailPageHeader
              icon={<ApiOutlined style={{ fontSize: 18, color: '#fff' }} />}
              iconGradient={MCP_TYPE_CONFIG[mcpInfo.type]?.gradient || 'linear-gradient(135deg, #4f6ef7 0%, #667eea 100%)'}
              iconShadowColor={MCP_TYPE_CONFIG[mcpInfo.type]?.color ? `${MCP_TYPE_CONFIG[mcpInfo.type].color}33` : 'rgba(79, 110, 247, 0.2)'}
              name={mcpInfo.name}
              tags={[
                {
                  color: MCP_TYPE_CONFIG[mcpInfo.type]?.color || '#999',
                  label: MCP_TYPE_CONFIG[mcpInfo.type]?.label || mcpInfo.type
                },
                ...(mcpInfo.isPublic === 1 ? [{ color: 'blue', label: intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' }) }] : []),
                {
                  color: mcpInfo.status === 1 ? 'success' : 'default',
                  label: mcpInfo.status === 1 ? intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })
                }
              ]}
              infoItems={[
                ...(mcpInfo.description ? [{
                  label: intl.formatMessage({ id: 'pages.mcp.detail.description', defaultMessage: 'Description' }),
                  value: mcpInfo.description
                }] : []),
                {
                  label: intl.formatMessage({ id: 'pages.mcp.detail.connectionMethod', defaultMessage: 'Connection Method' }),
                  value: mcpInfo.type === 'stdio' ? (mcpInfo.command || '-') : (mcpInfo.url || '-')
                }
              ]}
              creator={mcpInfo.creator || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}
              createTime={mcpInfo.createTime}
              intl={intl}
            />

            {/* 工具列表 */}
            <Card
              title={
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <ToolOutlined style={{ color: 'var(--vip-primary)' }} />
                  {intl.formatMessage({ id: 'pages.mcp.detail.toolList', defaultMessage: 'Tool List' })}
                  {tools.length > 0 && (
                    <Tag 
                      color="blue" 
                      style={{ 
                        marginLeft: 8,
                        fontWeight: 500
                      }}
                    >
                      {intl.formatMessage({ id: 'pages.mcp.detail.toolCount', defaultMessage: '{count} tools' }, { count: tools.length })}
                    </Tag>
                  )}
                </span>
              }
              style={{
                borderRadius: '16px',
                border: '1px solid var(--vip-border)',
                boxShadow: 'var(--vip-card-shadow)',
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
                    emptyText: toolsLoading ? intl.formatMessage({ id: 'pages.common.loading', defaultMessage: 'Loading...' }) : intl.formatMessage({ id: 'pages.mcp.detail.noTools', defaultMessage: 'No tools' }),
                  }}
                  style={{ borderRadius: '12px' }}
                  components={{
                    header: {
                      cell: (props: any) => (
                        <th 
                          {...props} 
                          style={{ 
                            ...props.style, 
                            background: 'var(--vip-bg-layout)',
                            fontWeight: 600,
                            color: 'var(--vip-text-primary)',
                            borderBottom: '2px solid var(--vip-border)'
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
