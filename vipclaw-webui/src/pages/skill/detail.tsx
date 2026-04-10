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

// 渲染文件内容
const FileContentRenderer: React.FC<{ filename: string; content: string }> = ({ filename, content }) => {
  const language = getLanguageFromExtension(filename);

  // Markdown 文件
  if (language === 'markdown') {
    return (
      <div 
        className="markdown-body" 
        style={{ 
          padding: '32px',
          background: '#ffffff',
          lineHeight: '1.8',
          fontSize: '15px'
        }}
      >
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
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
          background: '#fafbfc',
          padding: '24px',
          overflow: 'auto',
          maxHeight: '600px',
          fontSize: '13px',
          lineHeight: '1.7',
          whiteSpace: 'pre-wrap',
          wordWrap: 'break-word',
          fontFamily: '"JetBrains Mono", "Fira Code", "SF Mono", Monaco, monospace',
          color: '#24292e'
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
        maxHeight: '600px',
        fontSize: '13px',
        lineHeight: '1.6'
      }}
      wrapLongLines={true}
    >
      {content}
    </SyntaxHighlighter>
  );
};

const SkillDetail: React.FC = () => {
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
          SKILL.md
        </span>
      ),
      children: (
        <Card
          style={{
            borderRadius: '12px',
            border: '1px solid #f0f0f0',
          }}
          styles={{ body: { padding: '24px' } }}
        >
          {skillInfo?.skillmd ? (
            <div className="markdown-body">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {skillInfo.skillmd}
              </ReactMarkdown>
            </div>
          ) : (
            <Empty description="暂无 SKILL.md 内容" />
          )}
        </Card>
      ),
    },
    {
      key: 'resources',
      label: (
        <span>
          <FolderOutlined />
          Resources
        </span>
      ),
      children: (
        <div style={{ display: 'flex', gap: 20, height: 650 }}>
          {/* 左侧文件树 */}
          <Card
            title={
              <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <FolderOpenOutlined style={{ color: '#722ed1' }} />
                文件结构
              </span>
            }
            style={{
              width: 320,
              borderRadius: '12px',
              border: '1px solid #f0f0f5',
              boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)'
            }}
            styles={{ 
              body: { 
                padding: '16px',
                background: '#fafbfc',
                borderRadius: '0 0 12px 12px'
              } 
            }}
          >
            {treeData.length > 0 ? (
              <DirectoryTree
                treeData={treeData}
                onSelect={handleFileSelect}
                defaultExpandAll
                showIcon
                icon={
                  (props: any) => {
                    if (props.isLeaf) {
                      return <FileOutlined style={{ color: '#8c8c8c' }} />;
                    }
                    return <FolderOutlined style={{ color: '#722ed1' }} />;
                  }
                }
              />
            ) : (
              <Empty description="暂无资源文件" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>

          {/* 右侧文件内容 */}
          <Card
            title={
              selectedFile ? (
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <FileMarkdownOutlined style={{ color: '#722ed1' }} />
                  <Text strong>{selectedFile}</Text>
                </span>
              ) : (
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <FileOutlined />
                  文件内容
                </span>
              )
            }
            style={{
              flex: 1,
              borderRadius: '12px',
              border: '1px solid #f0f0f5',
              boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)'
            }}
            styles={{ body: { padding: 0 } }}
          >
            {selectedFile && fileContents[selectedFile] ? (
              <FileContentRenderer
                filename={selectedFile}
                content={fileContents[selectedFile]}
              />
            ) : (
              <div style={{ 
                display: 'flex', 
                alignItems: 'center', 
                justifyContent: 'center', 
                height: 400,
                background: '#fafbfc'
              }}>
                <Empty 
                  description="请从左侧选择文件查看内容" 
                  image={Empty.PRESENTED_IMAGE_SIMPLE} 
                />
              </div>
            )}
          </Card>
        </div>
      ),
    },
  ];

  if (!skillInfo && !loading) {
    return (
      <PageContainer>
        <Empty description="技能不存在" />
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
              <ThunderboltOutlined style={{ marginRight: 10, color: '#531dab' }} />
              技能详情
            </Title>
          </div>
        ),
        breadcrumb: {
          items: [
            { title: <a onClick={() => history.push('/context/skill')}>技能管理</a> },
            { title: skillInfo?.name || '技能详情' }
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
                border: '1px solid #f0f0f5',
                boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
                overflow: 'hidden'
              }}
              styles={{ body: { padding: 0 } }}
            >
              {/* 顶部标题栏 */}
              <div style={{ 
                padding: '20px 24px', 
                background: 'linear-gradient(135deg, #faf5ff 0%, #f0e6ff 100%)',
                borderBottom: '1px solid #e8d5ff'
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
                      <Text strong style={{ fontSize: 20, color: '#1a1a2e', display: 'block', marginBottom: 4 }}>
                        {skillInfo.name}
                      </Text>
                      <div style={{ display: 'flex', gap: 8 }}>
                        {skillInfo.isPublic === 1 && (
                          <Tag color="purple" style={{ borderRadius: '6px', fontWeight: 500 }}>公开</Tag>
                        )}
                        <Tag 
                          color={skillInfo.status === 1 ? 'success' : 'default'} 
                          style={{ borderRadius: '6px', fontWeight: 500 }}
                        >
                          {skillInfo.status === 1 ? '启用' : '禁用'}
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
                  label: { color: '#8c8c8c', fontWeight: 500 },
                  content: { color: '#262626' }
                }}
                style={{ padding: '24px' }}
              >
                <Descriptions.Item label="仓库地址" span={2}>
                  {skillInfo.repositoryName ? (
                    <a
                      href={skillInfo.repositoryName}
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{ 
                        color: '#722ed1',
                        textDecoration: 'none',
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 6,
                        fontWeight: 500,
                        transition: 'all 0.3s ease'
                      }}
                    >
                      <LinkOutlined />
                      {skillInfo.repositoryName}
                    </a>
                  ) : (
                    <Text type="secondary">暂无仓库地址</Text>
                  )}
                </Descriptions.Item>
                <Descriptions.Item label="技能描述" span={2}>
                  <Text style={{ lineHeight: 1.6 }}>{skillInfo.description || '暂无描述'}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={<span><UserOutlined style={{ marginRight: 4 }} />创建人</span>}>
                  <Text>{skillInfo.creator || '未知'}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={<span><ClockCircleOutlined style={{ marginRight: 4 }} />最近同步时间</span>}>
                  <Text>{skillInfo.updateTime?.replace('T', ' ') || '未知'}</Text>
                </Descriptions.Item>
              </Descriptions>
            </Card>

            {/* Tab 页 */}
            <Card
              style={{
                borderRadius: '16px',
                border: '1px solid #f0f0f5',
                boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
                overflow: 'hidden'
              }}
              styles={{ body: { padding: 0 } }}
            >
              <Tabs
                defaultActiveKey="skillmd"
                items={tabItems}
                size="large"
                style={{ 
                  padding: '0 24px',
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
