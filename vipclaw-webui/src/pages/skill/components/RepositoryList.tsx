import React, { useMemo } from 'react';
import { List, Switch, Button, Space, Typography, Tag, Popconfirm, message, Tooltip } from 'antd';
import { EditOutlined, DeleteOutlined, GithubOutlined, SyncOutlined, LinkOutlined } from '@ant-design/icons';
import { deleteSkillRepository, toggleSkillRepositoryStatus } from '@/services/ant-design-pro/skillRepository';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import { useIntl } from '@umijs/max';

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
      const response = await deleteSkillRepository(id);
      if (response.code === 200) {
        message.success('删除成功');
        onDelete(id);
      } else {
        const errorMsg = response.message || '删除失败';
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || '删除失败';
      message.error(errorMsg);
    }
  };

  const handleToggle = async (id: number, status: number) => {
    try {
      const response = await toggleSkillRepositoryStatus(id, status);
      if (response.code === 200) {
        message.success('状态切换成功');
        onToggle(id, status);
      } else {
        const errorMsg = response.message || '状态切换失败';
        message.error(errorMsg);
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || '状态切换失败';
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
            <GithubOutlined style={{ fontSize: '20px', color: '#4f6ef7', marginRight: 12 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <Text strong style={{ fontSize: '14px' }}>
                  {repository.name}
                </Text>
                {hasOperationPermission(isAdmin, currentUser, repository.creator) && (
                  <Switch
                    checked={repository.status === 1}
                    onChange={(checked) => {
                      onSelect(repository);
                      handleToggle(repository.id, checked ? 1 : 0);
                    }}
                    checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
                    unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
                    style={{
                      backgroundColor: repository.status === 1 ? '#4f6ef7' : '#d9d9d9',
                    }}
                  />
                )}
              </div>
              {repository.url && (
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
              {/* 是否公开、创建时间、创建人和操作按钮 */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, borderTop: '1px solid var(--vip-border-secondary)', paddingTop: 8 }}>
                {repository.isPublic === 1 && (
                  <Tag color="blue" style={{ fontSize: '11px' }}>{intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}</Tag>
                )}
                <Text type="secondary" style={{ fontSize: '11px' }}>
                  {repository.createTime?.replace('T', ' ')}
                </Text>
                {repository.creator && (
                  <Text type="secondary" style={{ fontSize: '11px' }}>{repository.creator}</Text>
                )}
                {hasOperationPermission(isAdmin, currentUser, repository.creator) && (
                  <Space size={8} style={{ marginLeft: 'auto' }}>
                    <Tooltip title={intl.formatMessage({ id: 'pages.skill.repository.sync', defaultMessage: 'Sync' })}>
                      <Button
                        type="link"
                        size="small"
                        icon={<SyncOutlined />}
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelect(repository);
                          onSync(repository);
                        }}
                        style={{ padding: '4px', color: '#1890ff' }}
                      />
                    </Tooltip>
                    <Tooltip title={intl.formatMessage({ id: 'pages.skill.repository.edit', defaultMessage: 'Edit' })}>
                      <Button
                        type="link"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelect(repository);
                          onEdit(repository);
                        }}
                        style={{ padding: '4px', color: '#1890ff' }}
                      />
                    </Tooltip>
                    <Tooltip title={intl.formatMessage({ id: 'pages.skill.repository.delete', defaultMessage: 'Delete' })}>
                      <Button
                        type="link"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelect(repository);
                          handleDelete(repository.id);
                        }}
                        style={{ padding: '4px' }}
                      />
                    </Tooltip>
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
