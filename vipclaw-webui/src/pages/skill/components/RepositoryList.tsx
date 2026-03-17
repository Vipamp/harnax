import React from 'react';
import { List, Switch, Button, Space, Typography, Tag, Popconfirm, message, Tooltip } from 'antd';
import { EditOutlined, DeleteOutlined, GithubOutlined, SyncOutlined, LinkOutlined } from '@ant-design/icons';
import { deleteSkillRepository, toggleSkillRepositoryStatus } from '@/services/ant-design-pro/skillRepository';

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
  const handleDelete = async (id: number) => {
    try {
      await deleteSkillRepository(id);
      message.success('删除成功');
      onDelete(id);
    } catch (error) {
      message.error('删除失败');
    }
  };

  const handleToggle = async (id: number, status: number) => {
    try {
      await toggleSkillRepositoryStatus(id, status);
      message.success('状态切换成功');
      onToggle(id, status);
    } catch (error) {
      message.error('状态切换失败');
    }
  };

  return (
    <List
      dataSource={repositories}
      renderItem={(repository) => (
        <List.Item
          onClick={() => onSelect(repository)}
          style={{
            padding: '12px 16px',
            cursor: 'pointer',
            backgroundColor: selectedRepository?.id === repository.id ? '#e6f7ff' : 'transparent',
            borderRadius: '8px',
            marginBottom: '8px',
            border: selectedRepository?.id === repository.id ? '1px solid #1890ff' : '1px solid transparent',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
            <GithubOutlined style={{ fontSize: '20px', color: '#4f6ef7', marginRight: 12 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <Text strong style={{ fontSize: '14px' }}>
                  {repository.name}
                </Text>
                <Space size={4}>
                  <Tooltip title="同步">
                    <Button
                      type="text"
                      size="small"
                      icon={<SyncOutlined />}
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelect(repository);
                        onSync(repository);
                      }}
                    />
                  </Tooltip>
                  <Switch
                    size="small"
                    checked={repository.status === 1}
                    onChange={(checked) => {
                      onSelect(repository);
                      handleToggle(repository.id, checked ? 1 : 0);
                    }}
                  />
                  <Button
                    type="text"
                    size="small"
                    icon={<EditOutlined />}
                    onClick={(e) => {
                      e.stopPropagation();
                      onSelect(repository);
                      onEdit(repository);
                    }}
                  />
                  <Popconfirm
                    title="确定删除此仓库吗？"
                    onConfirm={(e) => {
                      e?.stopPropagation();
                      onSelect(repository);
                      handleDelete(repository.id);
                    }}
                    onCancel={(e) => e?.stopPropagation()}
                  >
                    <Button
                      type="text"
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelect(repository);
                      }}
                    />
                  </Popconfirm>
                </Space>
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
            </div>
          </div>
        </List.Item>
      )}
    />
  );
};

export default RepositoryList;
