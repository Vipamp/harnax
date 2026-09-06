import React, { useMemo, useState } from 'react';
import { List, Space, Typography, Tag, message, Modal } from 'antd';
import { AppstoreOutlined, GithubOutlined, LinkOutlined, CloudOutlined, FileZipOutlined } from '@ant-design/icons';
import {
  deleteSkillSource,
  installSkillSource,
  toggleSkillSourceStatus,
} from '@/services/ant-design-pro/skillSource';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';
import { describeSkillInstall, showSkillInstallFeedback } from '@/utils/skillInstall';
import { useIntl } from '@umijs/max';
import EditButton from '@/components/EditButton';
import DeleteButton from '@/components/DeleteButton';
import InstallButton from '@/components/InstallButton';
import SyncButton from '@/components/SyncButton';
import StatusSwitch from '@/components/StatusSwitch';
import { BUILTIN_CLI_SKILL_REPO } from '@/constants/builtinRepository';

const { Text, Paragraph } = Typography;

interface RepositoryListProps {
  repositories: API.SkillRepositoryItem[];
  selectedRepository: API.SkillRepositoryItem | null;
  onSelect: (repository: API.SkillRepositoryItem) => void;
  onEdit: (repository: API.SkillRepositoryItem) => void;
  onToggle: (id: number, status: number) => void;
  onDelete: (id: number) => void;
  onSync: (repository: API.SkillRepositoryItem) => void;
  /** 重装落库后刷新右侧技能列表 */
  onInstalled: () => void;
}

const RepositoryList: React.FC<RepositoryListProps> = ({
  repositories,
  selectedRepository,
  onSelect,
  onEdit,
  onToggle,
  onDelete,
  onSync,
  onInstalled,
}) => {
  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);
  const intl = useIntl();
  // 正在重装的仓库 id，用于单独给该行的按钮上 loading
  const [installingId, setInstallingId] = useState<number | null>(null);

  // 请求失败时全局 errorHandler 已经弹出后端的具体原因，这里再弹一次会重复提示；
  // 同理，code !== 200 的分支不可达（errorThrower 会先抛异常），故不再判断。
  const handleDelete = async (id: number) => {
    try {
      await deleteSkillSource(id);
      message.success(
        intl.formatMessage({ id: 'pages.skill.repository.delete.success', defaultMessage: 'Delete successful' }),
      );
      onDelete(id);
    } catch {
      // 交给全局 errorHandler
    }
  };

  const handleToggle = async (id: number, status: number) => {
    try {
      await toggleSkillSourceStatus(id, status);
      message.success(
        intl.formatMessage({ id: 'pages.skill.repository.toggle.success', defaultMessage: 'Status toggled successfully' }),
      );
      onToggle(id, status);
    } catch {
      // 交给全局 errorHandler
    }
  };

  // 全量重装：修改 url / branch / packageName 只更新了配置，必须再走一次安装才会刷新库里的技能
  const doInstall = async (repository: API.SkillRepositoryItem) => {
    setInstallingId(repository.id);
    try {
      const response = await installSkillSource(repository.id);
      const feedback = describeSkillInstall(response.data, intl.formatMessage);
      showSkillInstallFeedback(feedback);
      // 全部失败时库里没有任何变化，不必刷新
      if (feedback.level !== 'error') {
        onInstalled();
      }
    } catch {
      // 交给全局 errorHandler
    } finally {
      setInstallingId(null);
    }
  };

  // 重装会覆盖同名技能，先确认再动手
  const handleInstall = (repository: API.SkillRepositoryItem) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.skill.repository.install', defaultMessage: 'Reinstall from source' }),
      content: intl.formatMessage({
        id: 'pages.skill.repository.confirmInstall',
        defaultMessage: 'Reinstall every skill from this source? Existing skills with the same name will be overwritten.',
      }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      onOk: () => doInstall(repository),
    });
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
            {repository.sourceType === 'BUILTIN' ? (
              <AppstoreOutlined style={{ fontSize: '20px', color: '#722ed1', marginRight: 12 }} />
            ) : repository.sourceType === 'NPM' ? (
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
                  <Tag
                    color={
                      repository.sourceType === 'BUILTIN'
                        ? 'purple'
                        : repository.sourceType === 'NPM'
                          ? 'red'
                          : repository.sourceType === 'ZIP'
                            ? 'orange'
                            : 'blue'
                    }
                    style={{ fontSize: '10px', lineHeight: '16px', padding: '0 4px' }}
                  >
                    {repository.sourceType || 'GIT'}
                  </Tag>
                  {repository.name === BUILTIN_CLI_SKILL_REPO && (
                    <Tag color="purple" style={{ fontSize: '10px', lineHeight: '16px', padding: '0 4px' }}>
                      {intl.formatMessage({ id: 'pages.skill.repository.builtin', defaultMessage: 'Built-in' })}
                    </Tag>
                  )}
                </Space>
                {repository.name !== BUILTIN_CLI_SKILL_REPO && hasOperationPermission(isAdmin, currentUser, repository.creator) && (
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
                {repository.name !== BUILTIN_CLI_SKILL_REPO && hasOperationPermission(isAdmin, currentUser, repository.creator) && (
                  <Space size={8} style={{ marginLeft: 'auto' }}>
                    {/* ZIP 源不留存压缩包、内置仓库平台只读，两者都无法重装 */}
                    {repository.sourceType !== 'ZIP' && repository.sourceType !== 'BUILTIN' && (
                      <InstallButton
                        loading={installingId === repository.id}
                        onClick={() => {
                          onSelect(repository);
                          handleInstall(repository);
                        }}
                      />
                    )}
                    {/* ZIP 源不留存压缩包、内置仓库没有远端，两者都拉不到技能清单 */}
                    {repository.sourceType !== 'ZIP' && repository.sourceType !== 'BUILTIN' && (
                      <SyncButton
                        onClick={() => {
                          onSelect(repository);
                          onSync(repository);
                        }}
                      />
                    )}
                    <EditButton
                      onClick={() => {
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
