import React, { useCallback, useEffect, useState } from 'react';
import { Button, Drawer, Empty, Spin, Table, Tag, Tooltip, Typography, message } from 'antd';
import { CopyOutlined, DownloadOutlined, ReloadOutlined, TeamOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { downloadTeamArtifact, listTeamArtifacts } from '@/services/ant-design-pro/team';

const { Text } = Typography;

interface TeamArtifactsDrawerProps {
  visible: boolean;
  sessionId?: string;
  onClose: () => void;
}

function formatSize(bytes: number): string {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / 1024 ** i).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

/**
 * 成员已发布的产物：跨成员交接靠的就是这份清单，所以 fileId 要能一键复制回给成员。
 */
const TeamArtifactsDrawer: React.FC<TeamArtifactsDrawerProps> = ({ visible, sessionId, onClose }) => {
  const intl = useIntl();
  const [artifacts, setArtifacts] = useState<API.TeamArtifactItem[]>([]);
  const [loading, setLoading] = useState(false);

  const loadArtifacts = useCallback(async () => {
    if (!sessionId) return;
    setLoading(true);
    try {
      const res = await listTeamArtifacts(sessionId);
      if (res.code === 200) {
        setArtifacts(res.data || []);
      } else {
        message.error(res.message || intl.formatMessage({ id: 'pages.session.artifacts.loadFailed', defaultMessage: 'Failed to load artifacts' }));
        setArtifacts([]);
      }
    } catch {
      message.error(intl.formatMessage({ id: 'pages.session.artifacts.loadFailed', defaultMessage: 'Failed to load artifacts' }));
      setArtifacts([]);
    } finally {
      setLoading(false);
    }
  }, [sessionId, intl]);

  useEffect(() => {
    if (visible && sessionId) loadArtifacts();
  }, [visible, sessionId, loadArtifacts]);

  const handleDownload = async (item: API.TeamArtifactItem) => {
    if (!sessionId) return;
    try {
      const blob = await downloadTeamArtifact(item.fileId, sessionId);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = item.fileName;
      a.click();
      window.URL.revokeObjectURL(url);
    } catch {
      message.error(intl.formatMessage({ id: 'pages.session.artifacts.downloadFailed', defaultMessage: 'Download failed' }));
    }
  };

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.session.artifacts.name', defaultMessage: 'File' }),
      dataIndex: 'fileName',
      render: (name: string, record: API.TeamArtifactItem) => (
        <div>
          <Text style={{ fontWeight: 500 }}>{name}</Text>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>
              {record.mimeType} · {formatSize(record.sizeBytes)}
            </Text>
          </div>
        </div>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.session.artifacts.reference', defaultMessage: 'Reference' }),
      dataIndex: 'fileId',
      render: (fileId: string) => (
        <Tooltip title={intl.formatMessage({ id: 'pages.session.artifacts.copyHint', defaultMessage: 'Copy this reference and ask a member to fetch it' })}>
          <Tag
            style={{ cursor: 'pointer', fontFamily: 'monospace' }}
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(fileId);
                message.success(intl.formatMessage({ id: 'pages.session.artifacts.copied', defaultMessage: 'Reference copied' }));
              } catch {
                message.error(intl.formatMessage({ id: 'pages.session.artifacts.copyFailed', defaultMessage: 'Copy failed' }));
              }
            }}
          >
            {fileId.slice(0, 8)}…
            <CopyOutlined style={{ marginLeft: 4 }} />
          </Tag>
        </Tooltip>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.session.artifacts.publishedAt', defaultMessage: 'Published' }),
      dataIndex: 'createTime',
      width: 120,
      render: (time: string) => (
        <Text type="secondary" style={{ fontSize: 12 }}>
          {time}
        </Text>
      ),
    },
    {
      title: '',
      dataIndex: 'action',
      width: 48,
      render: (_: unknown, record: API.TeamArtifactItem) => (
        <Button type="text" size="small" icon={<DownloadOutlined />} onClick={() => handleDownload(record)} />
      ),
    },
  ];

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <TeamOutlined />
          <span>{intl.formatMessage({ id: 'pages.session.artifacts.title', defaultMessage: 'Team Artifacts' })}</span>
        </div>
      }
      open={visible}
      onClose={onClose}
      width={620}
      extra={
        <Button icon={<ReloadOutlined />} size="small" onClick={loadArtifacts} loading={loading}>
          {intl.formatMessage({ id: 'pages.session.artifacts.refresh', defaultMessage: 'Refresh' })}
        </Button>
      }
    >
      <Spin spinning={loading}>
        {artifacts.length === 0 && !loading ? (
          <Empty
            description={intl.formatMessage({ id: 'pages.session.artifacts.empty', defaultMessage: 'No member has published an artifact yet' })}
            style={{ marginTop: 60 }}
          />
        ) : (
          <Table
            columns={columns}
            dataSource={artifacts}
            rowKey="fileId"
            size="small"
            pagination={false}
          />
        )}
      </Spin>
    </Drawer>
  );
};

export default TeamArtifactsDrawer;
