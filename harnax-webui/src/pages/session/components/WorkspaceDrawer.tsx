import React, { useState, useEffect, useCallback } from 'react';
import { Drawer, Spin, Empty, Tag, Button, Typography, message, Breadcrumb, Upload, Tooltip } from 'antd';
import {
  FolderOutlined,
  FileOutlined,
  ReloadOutlined,
  CloudServerOutlined,
  ArrowLeftOutlined,
  UploadOutlined,
  DownloadOutlined,
} from '@ant-design/icons';
import {
  listWorkspaceFiles,
  readWorkspaceFile,
  getWorkspaceStatus,
  uploadWorkspaceFile,
  downloadWorkspaceFile,
} from '@/services/ant-design-pro/workspace';

const { Text, Paragraph } = Typography;

interface WorkspaceDrawerProps {
  visible: boolean;
  sessionId?: string;
  onClose: () => void;
}

/** Human-readable file size */
function formatSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

const WorkspaceDrawer: React.FC<WorkspaceDrawerProps> = ({ visible, sessionId, onClose }) => {
  const [files, setFiles] = useState<API.WorkspaceFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [currentPath, setCurrentPath] = useState('/workspace');
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [fileContent, setFileContent] = useState<string>('');
  const [fileLoading, setFileLoading] = useState(false);
  const [sandboxStatus, setSandboxStatus] = useState<API.WorkspaceStatus | null>(null);

  const loadFiles = useCallback(
    async (path: string) => {
      if (!sessionId) return;
      setLoading(true);
      try {
        const res = await listWorkspaceFiles(sessionId, path);
        if (res.code === 200 && res.data) {
          // Sort: directories first, then files
          const sorted = [...res.data].sort((a, b) => {
            if (a.type === 'directory' && b.type !== 'directory') return -1;
            if (a.type !== 'directory' && b.type === 'directory') return 1;
            return a.name.localeCompare(b.name);
          });
          setFiles(sorted);
          setCurrentPath(path);
          setSelectedFile(null);
          setFileContent('');
        } else {
          message.error(res.message || 'Failed to load files');
          setFiles([]);
        }
      } catch {
        message.error('Failed to load workspace files');
        setFiles([]);
      } finally {
        setLoading(false);
      }
    },
    [sessionId],
  );

  const loadStatus = useCallback(async () => {
    if (!sessionId) return;
    try {
      const res = await getWorkspaceStatus(sessionId);
      if (res.code === 200 && res.data) {
        setSandboxStatus(res.data);
      }
    } catch {
      // ignore
    }
  }, [sessionId]);

  const handleReadFile = useCallback(
    async (path: string) => {
      if (!sessionId) return;
      setFileLoading(true);
      setSelectedFile(path);
      try {
        const res = await readWorkspaceFile(sessionId, path);
        if (res.code === 200 && res.data) {
          setFileContent(res.data.content);
        } else {
          setFileContent(`Error: ${res.message || 'Failed to read file'}`);
        }
      } catch {
        setFileContent('Error: Failed to read file');
      } finally {
        setFileLoading(false);
      }
    },
    [sessionId],
  );

  // Load files and status when drawer opens
  useEffect(() => {
    if (visible && sessionId) {
      loadFiles('/workspace');
      loadStatus();
    }
  }, [visible, sessionId, loadFiles, loadStatus]);

  // Navigate into a directory
  const handleDirClick = (dirName: string) => {
    const newPath = currentPath === '/workspace' ? `/workspace/${dirName}` : `${currentPath}/${dirName}`;
    loadFiles(newPath);
  };

  // Navigate up one level
  const handleGoUp = () => {
    if (currentPath === '/workspace') return;
    const parts = currentPath.split('/');
    parts.pop();
    const parentPath = parts.join('/') || '/workspace';
    loadFiles(parentPath);
  };

  // Build breadcrumb parts
  const breadcrumbParts = currentPath.split('/').filter(Boolean);

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <CloudServerOutlined />
          <span>Workspace</span>
          {sandboxStatus && (
            <Tag color={sandboxStatus.active ? 'green' : 'default'} style={{ marginLeft: 8 }}>
              {sandboxStatus.active ? 'Running' : 'Inactive'}
            </Tag>
          )}
        </div>
      }
      open={visible}
      onClose={onClose}
      width={600}
      extra={
        <div style={{ display: 'flex', gap: 8 }}>
          <Upload
            beforeUpload={async (file) => {
              if (!sessionId) return false;
              try {
                const res = await uploadWorkspaceFile(sessionId, file, currentPath);
                if (res.code === 200) {
                  message.success(`Uploaded ${file.name} successfully`);
                  loadFiles(currentPath);
                } else {
                  message.error(res.message || 'Upload failed');
                }
              } catch {
                message.error('Upload failed');
              }
              return false;
            }}
            showUploadList={false}
          >
            <Tooltip title="Upload file">
              <Button icon={<UploadOutlined />} size="small" />
            </Tooltip>
          </Upload>
          <Button
            icon={<ReloadOutlined />}
            size="small"
            onClick={() => loadFiles(currentPath)}
            loading={loading}
          >
            Refresh
          </Button>
        </div>
      }
    >
      {!sandboxStatus?.active ? (
        <Empty
          description="No active sandbox for this session"
          style={{ marginTop: 80 }}
        />
      ) : (
        <>
          {/* Breadcrumb navigation */}
          <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
            {currentPath !== '/workspace' && (
              <Button
                type="text"
                size="small"
                icon={<ArrowLeftOutlined />}
                onClick={handleGoUp}
              />
            )}
            <Breadcrumb
              items={breadcrumbParts.map((part, idx) => ({
                title:
                  idx === breadcrumbParts.length - 1 ? (
                    <Text strong>{part}</Text>
                  ) : (
                    <a
                      onClick={() => {
                        const pathTo = '/' + breadcrumbParts.slice(0, idx + 1).join('/');
                        loadFiles(pathTo);
                      }}
                    >
                      {part}
                    </a>
                  ),
              }))}
            />
          </div>

          {/* File list */}
          <Spin spinning={loading}>
            {files.length === 0 && !loading ? (
              <Empty description="Empty directory" style={{ marginTop: 40 }} />
            ) : (
              <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, overflow: 'hidden' }}>
                {files.map((file) => (
                  <div
                    key={file.name}
                    onClick={() => {
                      if (file.type === 'directory') {
                        handleDirClick(file.name);
                      } else {
                        const filePath =
                          currentPath === '/workspace'
                            ? `/workspace/${file.name}`
                            : `${currentPath}/${file.name}`;
                        handleReadFile(filePath);
                      }
                    }}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      padding: '8px 12px',
                      cursor: 'pointer',
                      borderBottom: '1px solid #f5f5f5',
                      backgroundColor:
                        selectedFile?.endsWith(file.name) ? 'rgba(99, 102, 241, 0.06)' : 'transparent',
                      transition: 'background-color 0.15s',
                    }}
                    onMouseEnter={(e) => {
                      (e.currentTarget as HTMLElement).style.backgroundColor = 'rgba(0,0,0,0.02)';
                    }}
                    onMouseLeave={(e) => {
                      (e.currentTarget as HTMLElement).style.backgroundColor =
                        selectedFile?.endsWith(file.name) ? 'rgba(99, 102, 241, 0.06)' : 'transparent';
                    }}
                  >
                    {file.type === 'directory' ? (
                      <FolderOutlined style={{ color: '#faad14', fontSize: 16, marginRight: 10 }} />
                    ) : (
                      <FileOutlined style={{ color: '#8c8c8c', fontSize: 16, marginRight: 10 }} />
                    )}
                    <Text
                      style={{
                        flex: 1,
                        fontWeight: file.type === 'directory' ? 500 : 400,
                      }}
                    >
                      {file.name}
                    </Text>
                    {file.type !== 'directory' && (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {formatSize(file.size)}
                        </Text>
                        <Tooltip title="Download">
                          <Button
                            type="text"
                            size="small"
                            icon={<DownloadOutlined />}
                            onClick={async (e) => {
                              e.stopPropagation();
                              if (!sessionId) return;
                              const filePath =
                                currentPath === '/workspace'
                                  ? `/workspace/${file.name}`
                                  : `${currentPath}/${file.name}`;
                              try {
                                const blob = await downloadWorkspaceFile(sessionId, filePath);
                                const url = window.URL.createObjectURL(blob);
                                const a = document.createElement('a');
                                a.href = url;
                                a.download = file.name;
                                a.click();
                                window.URL.revokeObjectURL(url);
                                message.success(`Downloaded ${file.name}`);
                              } catch {
                                message.error('Download failed');
                              }
                            }}
                          />
                        </Tooltip>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </Spin>

          {/* File content preview */}
          {selectedFile && (
            <div style={{ marginTop: 16 }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  marginBottom: 8,
                }}
              >
                <Text strong style={{ fontSize: 13 }}>
                  {selectedFile.split('/').pop()}
                </Text>
                <Tag>{selectedFile}</Tag>
              </div>
              <Spin spinning={fileLoading}>
                <div
                  style={{
                    background: '#fafafa',
                    border: '1px solid #f0f0f0',
                    borderRadius: 8,
                    padding: 12,
                    maxHeight: 400,
                    overflow: 'auto',
                  }}
                >
                  <pre
                    style={{
                      margin: 0,
                      fontSize: 12,
                      lineHeight: '1.5',
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-all',
                      fontFamily: "'Menlo', 'Monaco', 'Consolas', monospace",
                    }}
                  >
                    {fileContent || '(empty file)'}
                  </pre>
                </div>
              </Spin>
            </div>
          )}
        </>
      )}
    </Drawer>
  );
};

export default WorkspaceDrawer;
