import { useIntl } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Descriptions, Tag, Typography, Spin, Empty, Tabs, Tree, Breadcrumb } from 'antd';
import { 
  ThunderboltOutlined, 
  FileOutlined, 
  FolderOutlined, 
  LinkOutlined,
  FileMarkdownOutlined
} from '@ant-design/icons';
import React, { useEffect, useState } from 'react';
// @ts-ignore
import { useModel, useLocation, history } from '@umijs/max';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { getSkillById } from '@/services/ant-design-pro/skill';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import BackButton from '@/components/BackButton';
import DetailPageHeader from '@/components/DetailPageHeader';

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
  // 提取语言类型，支持多种格式：language-xxx 或 hljs language-xxx
  const match = /language-(\w+)/.exec(className || '');
  const language = match ? match[1].toLowerCase() : 'text';
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
      codeTagProps={{
        style: {
          background: 'none',
        }
      }}
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
            code: ({ className, children, ...props }: any) => {
              // 检查是否有语言标记（有 language-xxx 类名的都是代码块）
              const match = /language-(\w+)/.exec(className || '');
              // 如果有语言标记，说明是多行代码块，需要语法高亮
              if (match) {
                return <CodeBlock className={className}>{children}</CodeBlock>;
              }
              // 否则是行内代码，使用简单样式（不使用黑色背景）
              return <code className={className} {...props} style={{padding: '2px 6px', whiteSpace: 'nowrap'}}>{children}</code>;
            }
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
            // resources 解析失败，忽略
          }
        }
      }
    } catch (error) {
      // 加载技能详情失败
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
            <div 
              className="markdown-body" 
              style={{ 
                padding: '24px',
                height: '100%',
                overflow: 'auto'
              }}
            >
              {skillInfo?.skillmd ? (
                <ReactMarkdown 
                  remarkPlugins={[remarkGfm]}
                  components={{
                    code: ({ className, children, ...props }: any) => {
                      // 检查是否有语言标记（有 language-xxx 类名的都是代码块）
                      const match = /language-(\w+)/.exec(className || '');
                      // 如果有语言标记，说明是多行代码块，需要语法高亮
                      if (match) {
                        return <CodeBlock className={className}>{children}</CodeBlock>;
                      }
                      // 否则是行内代码，使用简单样式（不使用黑色背景）
                      return <code className={className} {...props} style={{padding: '2px 6px', whiteSpace: 'nowrap'}}>{children}</code>;
                    }
                  }}
                >
                  {skillInfo.skillmd}
                </ReactMarkdown>
              ) : (
                <Empty 
                  description={intl.formatMessage({ id: 'pages.skill.detail.noSkillmd', defaultMessage: 'No SKILL.md content' })} 
                  image={Empty.PRESENTED_IMAGE_SIMPLE} 
                />
              )}
            </div>
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
                  <FileContentRenderer 
                    filename={selectedFile} 
                    content={fileContents[selectedFile]} 
                  />
                ) : selectedFile ? (
                  <div style={{ 
                    padding: '40px 20px', 
                    textAlign: 'center',
                    color: 'var(--vip-text-tertiary)',
                    height: '100%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}>
                    <Empty 
                      description={intl.formatMessage({ id: 'pages.skill.detail.noContent', defaultMessage: 'No file content' })} 
                      image={Empty.PRESENTED_IMAGE_SIMPLE} 
                    />
                  </div>
                ) : (
                  <div style={{ 
                    padding: '40px 20px', 
                    textAlign: 'center',
                    color: 'var(--vip-text-tertiary)',
                    height: '100%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}>
                    <Empty 
                      description={intl.formatMessage({ id: 'pages.skill.detail.selectFileHint', defaultMessage: 'Please select a file from the left to view content' })} 
                      image={Empty.PRESENTED_IMAGE_SIMPLE} 
                    />
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
            <BackButton onClick={handleBack} />
            <div style={{ width: 1, height: 24, background: 'var(--vip-border)' }} />
            <span style={{ fontSize: 18, fontWeight: 600, color: 'var(--vip-text-primary)', margin: 0 }}>
              <ThunderboltOutlined style={{ marginRight: 10, color: '#531dab' }} />
              {intl.formatMessage({ id: 'pages.skill.detail.title', defaultMessage: 'Skill Detail' })}
            </span>
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
            {/* 基本信息 - 使用公共组件 */}
            <DetailPageHeader
              icon={<ThunderboltOutlined style={{ fontSize: 18, color: '#fff' }} />}
              iconGradient="linear-gradient(135deg, #722ed1 0%, #531dab 100%)"
              iconShadowColor="rgba(114, 46, 209, 0.2)"
              name={skillInfo.name}
              repositoryUrl={skillInfo.repositoryUrl}
              repositoryName={skillInfo.repositoryName}
              repositoryBranch={skillInfo.repositoryBranch}
              tags={[
                ...(skillInfo.isPublic === 1 ? [{ color: 'purple', label: intl.formatMessage({ id: 'pages.skill.detail.public', defaultMessage: 'Public' }) }] : []),
                {
                  color: skillInfo.status === 1 ? 'success' : 'default',
                  label: skillInfo.status === 1 ? intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }) : intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })
                }
              ]}
              infoItems={skillInfo.description ? [{
                label: intl.formatMessage({ id: 'pages.skill.detail.description', defaultMessage: 'Description' }),
                value: skillInfo.description
              }] : undefined}
              creator={skillInfo.creator || intl.formatMessage({ id: 'pages.common.unknown', defaultMessage: 'Unknown' })}
              updateTime={skillInfo.updateTime}
              intl={intl}
            />

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
