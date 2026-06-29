import React, { useMemo } from 'react';
import { List, Space, Typography, Tag, message, Tooltip } from 'antd';
import { GithubOutlined, LinkOutlined, CloudOutlined, FileZipOutlined } from '@ant-design/icons';
import { toggleSkillRepositoryStatus } from '@/services/ant-design-pro/skillRepository';
import { deleteSkillSource } from '@/services/ant-design-pro/skillSource';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import { useIntl } from '@umijs/max';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import SyncButton from '@/components/SyncButton';
import StatusSwitch from '@/components/StatusSwitch';

const { Text, Paragraph } = Typography;

interface RepositoryListProps {
  repositories: API.SkillRepositoryItem[];
  selectedRepository: API.SkillRepositoryItem | null;
  onSelect: (repository: API.SkillRepositoryItem) => void;
  onEdit: (repository: API.SkillRepositoryItem) => void;
  onToggle: (id: number, status: number) => void;
  onDelete: (id: number) => void;
  onSync: (repository: API.SkillRepositoryItem) => void;
}

const RepositoryList: React.FC<RepositoryListProps> = ({
  repositories,
  selectedRepository,
  onSelect,
  onEdit,
  onToggle,
  onDelete,
  onSync,
}) => {
  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);
  const intl = useIntl();

  const handleDelete = async (id: number) => {
    try {
      const response = await deleteSkillSource(id);
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.skill.repository.delete.success', defaultMessage: 'Delete successful' }));
        onDelete(id);
      } else {
        const errorMsg = response.message || intl.formatMessage({ id: 'pages.skill.repository.delete.failed', defaultMessage: 'Delete failed' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.skill.repository.delete.failed', defaultMessage: 'Delete failed' });
      message.error(errorMsg);
    }
  };

  const handleToggle = async (id: number, status: number) => {
    try {
      const response = await toggleSkillRepositoryStatus(id, status);
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.skill.repository.toggle.success', defaultMessage: 'Status toggled successfully' }));
        onToggle(id, status);
      } else {
        const errorMsg = response.message || intl.formatMessage({ id: 'pages.skill.repository.toggle.failed', defaultMessage: 'Status toggle failed' });
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.skill.repository.toggle.failed', defaultMessage: 'Status toggle failed' });
      message.error(errorMsg);
    }
  };

  return (
    <List
      dataSource={repositories}
      renderItem={(repository) => (
        <List.Item
          onClick={() => onSelect(repository)}
          style={{
            padding: '16px',
            cursor: 'pointer',
            backgroundColor: selectedRepository?.id === repository.id ? 'var(--vip-primary-light)' : 'var(--vip-bg-container)',
            borderRadius: '10px',
            marginBottom: '12px',
            border: selectedRepository?.id === repository.id ? '2px solid #1890ff' : '1.5px solid var(--vip-border)',
            boxShadow: selectedRepository?.id === repository.id 
              ? '0 2px 8px rgba(24, 144, 255, 0.15)' 
              : 'var(--vip-shadow-sm)',
            transition: 'all 0.3s ease',
          }}
          onMouseEnter={(e) => {
            if (selectedRepository?.id !== repository.id) {
              e.currentTarget.style.borderColor = 'var(--vip-primary)';
              e.currentTarget.style.boxShadow = 'var(--vip-shadow-md)';
              e.currentTarget.style.backgroundColor = 'var(--vip-bg-elevated)';
            }
          }}
          onMouseLeave={(e) => {
            if (selectedRepository?.id !== repository.id) {
              e.currentTarget.style.borderColor = 'var(--vip-border)';
              e.currentTarget.style.boxShadow = 'var(--vip-shadow-sm)';
              e.currentTarget.style.backgroundColor = 'var(--vip-bg-container)';
            }
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
            {repository.sourceType === 'NPM' ? (
              <CloudOutlined style={{ fontSize: '20px', color: '#cb3837', marginRight: 12 }} />
            ) : repository.sourceType === 'ZIP' ? (
              <FileZipOutlined style={{ fontSize: '20px', color: '#faad14', marginRight: 12 }} />
            ) : (
              <GithubOutlined style={{ fontSize: '20px', color: '#4f6ef7', marginRight: 12 }} />
            )}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <Space size={4}>
                  <Text strong style={{ fontSize: '14px' }}>
                    {repository.name}
                  </Text>
                  <Tag color={repository.sourceType === 'NPM' ? 'red' : repository.sourceType === 'ZIP' ? 'orange' : 'blue'}
                    style={{ fontSize: '10px', lineHeight: '16px', padding: '0 4px' }}>
                    {repository.sourceType || 'GIT'}
                  </Tag>
                </Space>
                {hasOperationPermission(isAdmin, currentUser, repository.creator) && (
                  <StatusSwitch
                    status={repository.status}
                    onChange={(newStatus) => {
                      onSelect(repository);
                      handleToggle(repository.id, newStatus);
                    }}
                  />
                )}
              </div>
              {(repository.sourceType === 'GIT' || !repository.sourceType) && repository.url && (
                <div style={{ display: 'flex', alignItems: 'center', marginTop: 4 }}>
                  <a
                    href={repository.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={(e) => e.stopPropagation()}
                    style={{
                      fontSize: '12px',
                      color: '#1890ff',
                      textDecoration: 'none',
                      display: 'flex',
                      alignItems: 'center',
                      maxWidth: '100%',
                    }}
                  >
                    <LinkOutlined style={{ marginRight: 4, fontSize: '10px' }} />
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {repository.url}
                    </span>
                  </a>
                </div>
              )}
              {repository.sourceType === 'NPM' && repository.sourceConfig?.packageName && (
                <div style={{ fontSize: '12px', color: '#666', marginTop: 4 }}>
                  {repository.sourceConfig.packageName}
                </div>
              )}
              {repository.sourceType === 'ZIP' && (
                <div style={{ fontSize: '12px', color: '#666', marginTop: 4 }}>
                  {repository.sourceConfig?.originalFilename || 'ZIP Upload'}
                </div>
              )}
              {/* 是否公开和操作按钮 */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, borderTop: '1px solid var(--vip-border-secondary)', paddingTop: 8 }}>
                {repository.isPublic === 1 && (
                  <Tag color="blue" style={{ fontSize: '11px' }}>{intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}</Tag>
                )}
                {hasOperationPermission(isAdmin, currentUser, repository.creator) && (
                  <Space size={8} style={{ marginLeft: 'auto' }}>
                    <SyncButton 
                      onClick={(e) => {
                        e?.stopPropagation();
                        onSelect(repository);
                        onSync(repository);
                      }} 
                    />
                    <EditButton 
                      onClick={(e) => {
                        e?.stopPropagation();
                        onSelect(repository);
                        onEdit(repository);
                      }} 
                    />
                    <DeleteButton 
                      onConfirm={() => handleDelete(repository.id)}
                      confirmTitle={intl.formatMessage({ id: 'pages.skill.repository.confirmDelete', defaultMessage: 'Are you sure to delete this repository?' })}
                    />
                  </Space>
                )}
              </div>
            </div>
          </div>
        </List.Item>
      )}
    />
  );
};

export default RepositoryList;
