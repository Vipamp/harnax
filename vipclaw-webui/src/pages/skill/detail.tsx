import { useIntl } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Descriptions, Tag, Typography, Spin, Empty, Button, Tabs, Tree, Breadcrumb } from 'antd';
import { 
  ArrowLeftOutlined, 
  ThunderboltOutlined, 
  FileOutlined, 
  FolderOutlined, 
  LinkOutlined,
  ClockCircleOutlined,
  UserOutlined,
  FileMarkdownOutlined,
  FolderOpenOutlined
} from '@ant-design/icons';
import React, { useEffect, useState } from 'react';
// @ts-ignore
import { useModel, useLocation, history } from '@umijs/max';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { getSkillById } from '@/services/ant-design-pro/skill';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';

const { Text, Title } = Typography;
const { DirectoryTree } = Tree;

interface ResourceFile {
  key: string;
  title: string;
  icon: React.ReactNode;
  children?: ResourceFile[];
  content?: string;
}

// 根据文件扩展名获取语言类型
const getLanguageFromExtension = (filename: string): string => {
  const ext = filename.split('.').pop()?.toLowerCase();
  const languageMap: Record<string, string> = {
    'md': 'markdown',
    'markdown': 'markdown',
    'py': 'python',
    'python': 'python',
    'sh': 'bash',
    'bash': 'bash',
    'shell': 'bash',
    'js': 'javascript',
    'javascript': 'javascript',
    'jsx': 'javascript',
    'ts': 'typescript',
    'typescript': 'typescript',
    'tsx': 'typescript',
    'json': 'json',
    'yaml': 'yaml',
    'yml': 'yaml',
    'xml': 'xml',
    'html': 'html',
    'css': 'css',
    'txt': 'text',
    'text': 'text',
  };
  return ext ? languageMap[ext] || 'text' : 'text';
};

// Markdown 代码块组件（带语法高亮）
const CodeBlock: React.FC<{ className?: string; children?: React.ReactNode }> = ({ className, children }) => {
  const match = /language-(\w+)/.exec(className || '');
  const language = match ? match[1] : 'text';
  const code = String(children).replace(/\n$/, '');
  
  return (
    <SyntaxHighlighter
      language={language}
      style={oneDark}
      customStyle={{
        margin: '16px 0',
        borderRadius: '8px',
        fontSize: '13px',
        lineHeight: '1.6',
        padding: '16px',
      }}
      wrapLongLines={true}
    >
      {code}
    </SyntaxHighlighter>
  );
};

// 渲染文件内容
const FileContentRenderer: React.FC<{ filename: string; content: string }> = ({ filename, content }) => {
  const language = getLanguageFromExtension(filename);

  // Markdown 文件
  if (language === 'markdown') {
    return (
      <div 
        className="markdown-body" 
        style={{ 
          padding: '24px',
          height: '100%',
          overflow: 'auto'
        }}
      >
        <ReactMarkdown 
          remarkPlugins={[remarkGfm]}
          components={{
            code: ({ node, inline, className, children, ...props }: any) => {
              if (inline) {
                return <code className={className} {...props}>{children}</code>;
              }
              return <CodeBlock className={className}>{children}</CodeBlock>;            }
          }}
        >
          {content}
        </ReactMarkdown>
      </div>
    );
  }

  // 纯文本文件
  if (language === 'text') {
    return (
      <pre
        style={{
          background: 'var(--vip-bg-elevated)',
          padding: '24px',
          overflow: 'auto',
          height: '100%',
          fontSize: '13px',
          lineHeight: '1.7',
          whiteSpace: 'pre-wrap',
          wordWrap: 'break-word',
          fontFamily: '"JetBrains Mono", "Fira Code", "SF Mono", Monaco, monospace',
          color: 'var(--vip-text-primary)',
          margin: 0,
          boxSizing: 'border-box'
        }}
      >
        {content}
      </pre>
    );
  }

  // 代码文件(使用语法高亮)
  return (
    <SyntaxHighlighter
      language={language}
      style={oneDark}
      customStyle={{
        margin: 0,
        borderRadius: '0',
        height: '100%',
        fontSize: '13px',
        lineHeight: '1.6',
        boxSizing: 'border-box'
      }}
      wrapLongLines={true}
    >
      {content}
    </SyntaxHighlighter>
  );
};

const SkillDetail: React.FC = () => {
  const intl = useIntl();
  const location = useLocation();
  const [loading, setLoading] = useState(false);
  const [skillInfo, setSkillInfo] = useState<API.SkillItem | null>(null);
  const [selectedFile, setSelectedFile] = useState<string>('');
  const [fileContents, setFileContents] = useState<Record<string, string>>({});

  // 从 URL 中获取 Skill ID
  const pathParts = location.pathname.split('/');
  const skillId = pathParts[pathParts.length - 1];

  useEffect(() => {
    if (skillId) {
      loadSkillDetail(parseInt(skillId, 10));
    }
  }, [skillId]);

  const loadSkillDetail = async (id: number) => {
    setLoading(true);
    try {
      const res = await getSkillById(id);
      if (res.code === 200 && res.data) {
        setSkillInfo(res.data);
        // 解析 resources
        if (res.data.resources) {
          try {
            const resources = typeof res.data.resources === 'string' 
              ? JSON.parse(res.data.resources) 
              : res.data.resources;
            setFileContents(resources);
            // 默认选中第一个文件
            const firstFile = Object.keys(resources)[0];
            if (firstFile) {
              setSelectedFile(firstFile);
            }
          } catch (error) {
            console.error('解析 resources 失败', error);
          }
        }
      }
    } catch (error) {
      console.error('加载技能详情失败', error);
    } finally {
      setLoading(false);
    }
  };

  const handleBack = () => {
    history.push('/context/skill');
  };

  // 构建树形结构
  const buildTree = (): ResourceFile[] => {
    if (!fileContents || Object.keys(fileContents).length === 0) {
      return [];
    }

    const root: Record<string, any> = {};

    Object.entries(fileContents).forEach(([path, content]) => {
      const parts = path.split('/');
      let current = root;

      parts.forEach((part, index) => {
        if (index === parts.length - 1) {
          // 文件节点
          current[part] = {
            key: path,
            title: part,
            icon: <FileOutlined />,
            content: String(content),
          };
        } else {
          // 文件夹节点
          if (!current[part]) {
            current[part] = {
              key: parts.slice(0, index + 1).join('/'),
              title: part,
              icon: <FolderOutlined />,
              children: {},
            };
          }
          current = current[part].children;
        }
      });
    });

    // 转换为数组
    const convertToArray = (obj: Record<string, any>): ResourceFile[] => {
      return Object.values(obj).map((item: any) => {
        if (item.children && Object.keys(item.children).length > 0) {
          return {
            ...item,
            children: convertToArray(item.children),
          };
        }
        return item;
      });
    };

    return convertToArray(root);
  };

  // 处理文件选择
  const handleFileSelect = (selectedKeys: React.Key[]) => {
    if (selectedKeys.length > 0) {
      setSelectedFile(selectedKeys[0] as string);
    }
  };

  const treeData = buildTree();

  const tabItems = [
    {
      key: 'skillmd',
      label: (
        <span>
          <FileOutlined />
          {intl.formatMessage({ id: 'pages.skill.detail.tab.skillmd', defaultMessage: 'SKILL.md' })}
        </span>
      ),
      children: (
        <div style={{ height: '100%' }}>
          <Card
            style={{
              borderRadius: '12px',
              border: '1px solid var(--vip-border)',
              height: '100%',
              display: 'flex',
              flexDirection: 'column'
            }}
            styles={{ 
              body: { 
                padding: 0,
                flex: 1,
                overflow: 'hidden'
              } 
            }}
          >
            {skillInfo?.skillmd ? (
              <div 
                className="markdown-body"
                style={{
                  padding: '24px',
                  height: '100%',
                  overflow: 'auto'
                }}
              >
                <ReactMarkdown 
                  remarkPlugins={[remarkGfm]}
                  components={{
                    code: ({ node, inline, className, children, ...props }: any) => {
                      if (inline) {
                        return <code className={className} {...props}>{children}</code>;
                      }
                      return <CodeBlock className={className}>{children}</CodeBlock>;                    }
                  }}
                >
                  {skillInfo.skillmd}
                </ReactMarkdown>
              </div>
            ) : (
              <Empty description={intl.formatMessage({ id: 'pages.skill.detail.noContent', defaultMessage: 'No SKILL.md content' })} />
            )}
          </Card>
        </div>
      ),
    },
    {
      key: 'resources',
      label: (
        <span>
          <FolderOutlined />
          {intl.formatMessage({ id: 'pages.skill.detail.tab.resources', defaultMessage: 'Resources' })}
        </span>
      ),
      children: (
        <div className="resources-container" style={{ 
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          background: 'var(--vip-bg-layout)',
          borderRadius: '12px',
          overflow: 'hidden',
          border: '1px solid var(--vip-border)'
        }}>
          {/* 顶部工具栏 */}
          <div className="resources-toolbar" style={{
            padding: '12px 16px',
            background: 'var(--vip-bg-container)',
            borderBottom: '1px solid var(--vip-border)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            boxShadow: 'var(--vip-shadow-sm)'
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <FolderOpenOutlined style={{ color: '#722ed1', fontSize: '16px' }} />
              <Text strong style={{ color: 'var(--vip-text-primary)' }}>{intl.formatMessage({ id: 'pages.skill.detail.resources', defaultMessage: 'Resources' })}</Text>
              <Tag color="purple" style={{ marginLeft: '8px', borderRadius: '4px' }}>
                {intl.formatMessage({ id: 'pages.skill.detail.fileCount', defaultMessage: '{count} files' }, { count: Object.keys(fileContents).length })}
              </Tag>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Button 
                size="small" 
                icon={<FolderOutlined />} 
                style={{ 
                  transition: 'all 0.2s ease'
                }}
              >
                {intl.formatMessage({ id: 'pages.skill.detail.expandAll', defaultMessage: 'Expand All' })}
              </Button>
            </div>
          </div>

          {/* 主内容区域 */}
          <div style={{ 
            flex: 1, 
            display: 'flex',
            overflow: 'hidden'
          }}>
            {/* 左侧文件树 */}
            <div className="file-tree-panel" style={{
              width: '320px',
              background: 'var(--vip-bg-container)',
              borderRight: '1px solid var(--vip-border)',
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden'
            }}>
              <div style={{ 
                padding: '12px 16px', 
                background: 'var(--vip-bg-elevated)',
                borderBottom: '1px solid var(--vip-border)',
                fontSize: '12px',
                color: 'var(--vip-text-tertiary)',
                fontWeight: 500
              }}>
                {intl.formatMessage({ id: 'pages.skill.detail.fileStructure', defaultMessage: 'File Structure' })}
              </div>
              <div style={{ 
                flex: 1, 
                overflow: 'auto',
                padding: '8px 0'
              }}>
                {treeData.length > 0 ? (
                  <DirectoryTree
                    treeData={treeData}
                    onSelect={handleFileSelect}
                    defaultExpandAll
                    showIcon
                    className="custom-file-tree"
                    icon={
                      (props: any) => {
                        if (props.isLeaf) {
                          return <FileOutlined style={{ color: '#8c8c8c', fontSize: '14px' }} />;
                        }
                        return <FolderOutlined style={{ color: '#722ed1', fontSize: '14px' }} />;
                      }
                    }
                  />
                ) : (
                  <div style={{ 
                    padding: '40px 20px', 
                    textAlign: 'center',
                    color: 'var(--vip-text-tertiary)'
                  }}>
                    <Empty 
                      description={intl.formatMessage({ id: 'pages.skill.detail.noResources', defaultMessage: 'No resource files' })} 
                      image={Empty.PRESENTED_IMAGE_SIMPLE} 
                    />
                  </div>
                )}
              </div>
            </div>

            {/* 右侧文件内容 */}
            <div className="file-content-panel" style={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              background: 'var(--vip-bg-container)',
              overflow: 'hidden'
            }}>
              {/* 文件路径面包屑 */}
              {selectedFile && (
                <div style={{
                  padding: '12px 16px',
                  background: 'var(--vip-bg-elevated)',
                  borderBottom: '1px solid var(--vip-border)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  fontSize: '13px',
                  color: 'var(--vip-text-secondary)'
                }}>
                  <FileMarkdownOutlined style={{ color: '#722ed1' }} />
                  <Breadcrumb>
                    <Breadcrumb.Item>
                      <span style={{ color: 'var(--vip-text-tertiary)' }}>resources</span>
                    </Breadcrumb.Item>
                    {selectedFile.split('/').map((part, index, arr) => (
                      <Breadcrumb.Item key={index}>
                        <span style={{ 
                          color: index === arr.length - 1 ? 'var(--vip-text-primary)' : 'var(--vip-text-secondary)',
                          fontWeight: index === arr.length - 1 ? 500 : 400
                        }}>
                          {part}
                        </span>
                      </Breadcrumb.Item>
                    ))}
                  </Breadcrumb>
                </div>
              )}

              {/* 内容显示区域 */}
              <div style={{ 
                flex: 1, 
                overflow: 'auto',
                position: 'relative'
              }}>
                {selectedFile && fileContents[selectedFile] ? (
                  <div className="file-content-wrapper" style={{
                    height: '100%',
                    overflow: 'auto'
                  }}>
                    <FileContentRenderer
                      filename={selectedFile}
                      content={fileContents[selectedFile]}
                    />
                  </div>
                ) : (
                  <div style={{ 
                    display: 'flex', 
                    flexDirection: 'column',
                    alignItems: 'center', 
                    justifyContent: 'center', 
                    height: '100%',
                    background: 'var(--vip-bg-layout)',
                    color: 'var(--vip-text-tertiary)'
                  }}>
                    <div style={{
                      width: '80px',
                      height: '80px',
                      borderRadius: '50%',
                      background: 'var(--vip-primary-light)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      marginBottom: '16px',
                      boxShadow: 'var(--vip-shadow-md)'
                    }}>
                      <FileOutlined style={{ fontSize: '32px', color: '#722ed1' }} />
                    </div>
                    <Text style={{ fontSize: '16px', marginBottom: '8px', color: 'var(--vip-text-secondary)' }}>
                      {intl.formatMessage({ id: 'pages.skill.detail.selectFile', defaultMessage: 'Select file to view content' })}
                    </Text>
                    <Text style={{ fontSize: '13px', color: 'var(--vip-text-tertiary)' }}>
                      {intl.formatMessage({ id: 'pages.skill.detail.selectFileHint', defaultMessage: 'Select a file from the left file tree to view its content' })}
                    </Text>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      ),
    },
  ];

  if (!skillInfo && !loading) {
    return (
      <PageContainer>
        <Empty description={intl.formatMessage({ id: 'pages.skill.detail.notFound', defaultMessage: 'Skill not found' })} />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      className="skill-detail-page"
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
              {intl.formatMessage({ id: 'pages.common.back', defaultMessage: 'Back' })}
            </Button>
            <div style={{ width: 1, height: 24, background: 'var(--vip-border)' }} />
            <Title level={3} style={{ margin: 0, fontWeight: 600 }}>
              <ThunderboltOutlined style={{ marginRight: 10, color: '#531dab' }} />
              {intl.formatMessage({ id: 'pages.skill.detail.title', defaultMessage: 'Skill Detail' })}
            </Title>
          </div>
        ),
        breadcrumb: {
          items: [
            { title: <a onClick={() => history.push('/context/skill')}>{intl.formatMessage({ id: 'menu.context.skill', defaultMessage: 'Skill Management' })}</a> },
            { title: skillInfo?.name || intl.formatMessage({ id: 'pages.skill.detail', defaultMessage: 'Skill Detail' }) }
          ]
        }
      }}
    >
      <Spin spinning={loading}>
        {skillInfo && (
          <>
            {/* 基本信息 */}
            <Card
              style={{
                marginBottom: 24,
                borderRadius: '16px',
                border: '1px solid var(--vip-border)',
                boxShadow: 'var(--vip-shadow-sm)',
                overflow: 'hidden'
              }}
              styles={{ body: { padding: 0 } }}
            >
              {/* 顶部标题栏 */}
              <div style={{ 
                padding: '20px 24px', 
                background: 'var(--vip-primary-light)',
                borderBottom: '1px solid var(--vip-border)'
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <div style={{
                      width: 48,
                      height: 48,
                      borderRadius: '12px',
                      background: 'linear-gradient(135deg, #722ed1 0%, #531dab 100%)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      boxShadow: '0 4px 12px rgba(114, 46, 209, 0.25)'
                    }}>
                      <ThunderboltOutlined style={{ fontSize: 24, color: '#fff' }} />
                    </div>
                    <div>
                      <Text strong style={{ fontSize: 20, color: 'var(--vip-text-primary)', display: 'block', marginBottom: 4 }}>
                        {skillInfo.name}
                      </Text>
                      <div style={{ display: 'flex', gap: 8 }}>
                        {skillInfo.isPublic === 1 && (
                          <Tag color="purple" style={{ borderRadius: '6px', fontWeight: 500 }}>{intl.formatMessage({ id: 'pages.skill.detail.public', defaultMessage: 'Public' })}</Tag>
                        )}
                        <Tag 
                          color={skillInfo.status === 1 ? 'success' : 'default'} 
                          style={{ borderRadius: '6px', fontWeight: 500 }}
                        >
                          {skillInfo.status === 1 ? intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
                        </Tag>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              {/* 详细信息 */}
              <Descriptions 
                column={2} 
                size="small"
                styles={{ 
                  label: { color: 'var(--vip-text-tertiary)', fontWeight: 500 },
                  content: { color: 'var(--vip-text-primary)' }
                }}
                style={{ padding: '24px' }}
              >
                <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.detail.repositoryUrl', defaultMessage: 'Repository URL' })} span={2}>
                  {skillInfo.repositoryUrl ? (
                    <a
                      href={skillInfo.repositoryBranch 
                        ? `${skillInfo.repositoryUrl}/tree/${skillInfo.repositoryBranch}`
                        : skillInfo.repositoryUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{ 
                        color: 'var(--vip-primary)',
                        textDecoration: 'none',
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 6,
                        fontWeight: 500,
                        transition: 'all 0.3s ease'
                      }}
                    >
                      <LinkOutlined />
                      {skillInfo.repositoryName || skillInfo.repositoryUrl}
                      {skillInfo.repositoryBranch && (
                        <Tag color="blue" style={{ marginLeft: 8, borderRadius: '4px' }}>
                          {skillInfo.repositoryBranch}
                        </Tag>
                      )}
                    </a>
                  ) : (
                    <Text type="secondary">{intl.formatMessage({ id: 'pages.skill.detail.noRepositoryUrl', defaultMessage: 'No repository URL' })}</Text>
                  )}
                </Descriptions.Item>
                <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.detail.description', defaultMessage: 'Description' })} span={2}>
                  <Text style={{ lineHeight: 1.6 }}>{skillInfo.description || intl.formatMessage({ id: 'pages.common.noDescription', defaultMessage: 'No description' })}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={<span><UserOutlined style={{ marginRight: 4 }} />{intl.formatMessage({ id: 'pages.skill.detail.creator', defaultMessage: 'Creator' })}</span>} span={2}>
                  <Text>{skillInfo.creator || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={<span><ClockCircleOutlined style={{ marginRight: 4 }} />{intl.formatMessage({ id: 'pages.skill.detail.lastSyncTime', defaultMessage: 'Last Sync Time' })}</span>} span={2}>
                  <Text>{skillInfo.updateTime?.replace('T', ' ') || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}</Text>
                </Descriptions.Item>
              </Descriptions>
            </Card>

            {/* Tab 页 */}
            <Card
              style={{
                borderRadius: '16px',
                border: '1px solid var(--vip-border)',
                boxShadow: 'var(--vip-shadow-sm)',
                overflow: 'hidden',
                height: 'calc(100vh - 280px)',
                display: 'flex',
                flexDirection: 'column'
              }}
              styles={{ body: { padding: 0, flex: 1, overflow: 'hidden' } }}
            >
              <Tabs
                defaultActiveKey="skillmd"
                items={tabItems}
                size="large"
                style={{ 
                  padding: '0 24px',
                  height: '100%',
                  display: 'flex',
                  flexDirection: 'column',
                  '--ant-tabs-item-active-color': '#722ed1',
                  '--ant-tabs-ink-bar-color': '#722ed1'
                } as React.CSSProperties}
              />
            </Card>
          </>
        )}
      </Spin>
    </PageContainer>
  );
};

export default SkillDetail;
