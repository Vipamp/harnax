import React, { useMemo, useState } from 'react';
import { List, Space, Typography, Tag, message, Modal } from 'antd';
import {
  AppstoreOutlined,
  GithubOutlined,
  LinkOutlined,
  CloudOutlined,
  FileZipOutlined,
  DownOutlined,
  UpOutlined,
} from '@ant-design/icons';
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

/** 后端 SkillSyncRecorder 的四种结论，一处映射新增状态时这里就会缺项 */
const SYNC_STATUS: Record<string, { color: string; id: string; defaultMessage: string }> = {
  SUCCESS: { color: 'green', id: 'pages.skill.repository.sync.SUCCESS', defaultMessage: 'Synced' },
  PARTIAL: { color: 'orange', id: 'pages.skill.repository.sync.PARTIAL', defaultMessage: 'Partially synced' },
  EMPTY: { color: 'gold', id: 'pages.skill.repository.sync.EMPTY', defaultMessage: 'Nothing installed' },
  FAILED: { color: 'red', id: 'pages.skill.repository.sync.FAILED', defaultMessage: 'Source failed' },
};

type FormatMessage = (
  descriptor: { id: string; defaultMessage?: string },
  values?: Record<string, any>,
) => string;

/**
 * 「3 分钟前」这类相对时间。
 *
 * 没有注册 dayjs 的 relativeTime 插件，且文案必须走 i18n，所以差值在这里算、句式在 locale 文件里。
 * 超过一个月不再说「30 天前」，直接给绝对时间——那时用户关心的是哪一次，而不是隔了多久。
 */
function formatElapsed(time: string, formatMessage: FormatMessage): string {
  const target = new Date(time.replace(' ', 'T')).getTime();
  if (Number.isNaN(target)) return time;
  const minutes = Math.floor((Date.now() - target) / 60000);
  if (minutes < 1) return formatMessage({ id: 'pages.skill.repository.sync.justNow', defaultMessage: 'just now' });
  if (minutes < 60) {
    return formatMessage({ id: 'pages.skill.repository.sync.minutesAgo', defaultMessage: '{count} min ago' }, { count: minutes });
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return formatMessage({ id: 'pages.skill.repository.sync.hoursAgo', defaultMessage: '{count} h ago' }, { count: hours });
  }
  const days = Math.floor(hours / 24);
  if (days <= 30) {
    return formatMessage({ id: 'pages.skill.repository.sync.daysAgo', defaultMessage: '{count} d ago' }, { count: days });
  }
  return time.replace('T', ' ').slice(0, 16);
}

interface SyncResultProps {
  repository: API.SkillRepositoryItem;
  formatMessage: FormatMessage;
}

/** 上次同步结果是否还有可展开的明细：全绿的一次同步不该多占一行 */
function hasSyncDetail(detail: API.SkillSyncDetail | undefined): boolean {
  if (!detail) return false;
  return (
    (detail.failed?.length ?? 0) > 0 ||
    (detail.flagged?.length ?? 0) > 0 ||
    (detail.stale?.length ?? 0) > 0 ||
    !!detail.error
  );
}

/**
 * 一行同步结论，点开是上次同步的全量明细。
 *
 * 失败明细原先只活在那条 6 秒的 toast 里，刷新即失；徽标把它落到仓库行上，
 * 展开区不再截断条数，所以这里必须自己滚。
 */
const SyncResult: React.FC<SyncResultProps & { expanded: boolean; onToggle: () => void }> = ({
  repository,
  formatMessage,
  expanded,
  onToggle,
}) => {
  const status = repository.lastSyncStatus ? SYNC_STATUS[repository.lastSyncStatus] : undefined;
  if (!status) return null;

  const detail = repository.lastSyncDetail;
  const expandable = hasSyncDetail(detail);
  const failedCount = detail?.failed?.length ?? 0;

  return (
    <div onClick={(e) => e.stopPropagation()} style={{ marginTop: 6 }}>
      <Space size={6} wrap>
        <Tag color={status.color} style={{ fontSize: '11px', lineHeight: '16px', padding: '0 4px', margin: 0 }}>
          {formatMessage(status)}
        </Tag>
        {repository.lastSyncTime && (
          <Text type="secondary" style={{ fontSize: '11px' }}>
            {formatMessage(
              { id: 'pages.skill.repository.sync.lastAt', defaultMessage: 'Last sync {elapsed}' },
              { elapsed: formatElapsed(repository.lastSyncTime, formatMessage) },
            )}
          </Text>
        )}
        {failedCount > 0 && (
          <Text type="danger" style={{ fontSize: '11px' }}>
            {formatMessage(
              { id: 'pages.skill.repository.sync.failedShort', defaultMessage: '{count} not saved' },
              { count: failedCount },
            )}
          </Text>
        )}
        {expandable && (
          <a style={{ fontSize: '11px' }} onClick={onToggle}>
            {expanded ? <UpOutlined style={{ fontSize: 9 }} /> : <DownOutlined style={{ fontSize: 9 }} />}{' '}
            {formatMessage({ id: 'pages.skill.repository.sync.detail', defaultMessage: 'Detail' })}
          </a>
        )}
      </Space>
      {expanded && detail && (
        <div
          style={{
            marginTop: 6,
            padding: '8px 10px',
            fontSize: '11px',
            lineHeight: '18px',
            background: 'var(--vip-bg-layout)',
            border: '1px solid var(--vip-border-secondary)',
            borderRadius: 6,
            maxHeight: 180,
            overflowY: 'auto',
          }}
        >
          {detail.error && (
            <div style={{ marginBottom: 6 }}>
              <Text type="danger" style={{ fontSize: '11px' }}>
                {formatMessage({ id: 'pages.skill.repository.sync.errorTitle', defaultMessage: 'Source error' })}：
                {detail.error}
              </Text>
            </div>
          )}
          <DetailSection
            title={formatMessage(
              { id: 'pages.skill.repository.sync.failedTitle', defaultMessage: 'Not saved ({count})' },
              { count: detail.failed?.length ?? 0 },
            )}
            items={(detail.failed ?? []).map((item) => `${item.name} — ${item.reason}`)}
          />
          <DetailSection
            title={formatMessage(
              { id: 'pages.skill.repository.sync.flaggedTitle', defaultMessage: 'Disabled pending review ({count})' },
              { count: detail.flagged?.length ?? 0 },
            )}
            hint={formatMessage({
              id: 'pages.skill.repository.sync.flaggedHint',
              defaultMessage: 'the content scan flagged these, they were stored disabled',
            })}
            items={(detail.flagged ?? []).map(
              (item) => `${item.name}${item.reasons?.length ? ` — ${item.reasons.join('、')}` : ''}`,
            )}
          />
          <DetailSection
            title={formatMessage(
              { id: 'pages.skill.repository.sync.staleTitle', defaultMessage: 'No longer in the source ({count})' },
              { count: detail.stale?.length ?? 0 },
            )}
            hint={formatMessage({
              id: 'pages.skill.repository.sync.staleHint',
              defaultMessage: 'reported only, nothing was deleted',
            })}
            items={detail.stale ?? []}
          />
        </div>
      )}
    </div>
  );
};

/** 展开区的一段：没有条目就整段不渲染，标题连同「仅提示」这类说明一起收起 */
const DetailSection: React.FC<{ title: string; hint?: string; items: string[] }> = ({ title, hint, items }) => {
  if (items.length === 0) return null;
  return (
    <div style={{ marginBottom: 6 }}>
      <div style={{ fontWeight: 500 }}>
        {title}
        {hint && (
          <Text type="secondary" style={{ fontSize: '11px', fontWeight: 400 }}>
            {' '}
            （{hint}）
          </Text>
        )}
      </div>
      {items.map((item) => (
        <Paragraph
          key={item}
          style={{ margin: 0, fontSize: '11px', color: 'var(--vip-text-secondary)', wordBreak: 'break-all' }}
        >
          {item}
        </Paragraph>
      ))}
    </div>
  );
};

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
  // 展开着上次同步明细的仓库 id，一次只开一个，免得左栏被撑长
  const [expandedId, setExpandedId] = useState<number | null>(null);

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
      // 失败也要刷新：整源读不动时后端照样把这次结论写进了仓库行，
      // 跳过刷新就等于让行上的徽标停在上一次同步，而常驻结果正是它存在的理由
      onInstalled();
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

  // 后端要求仓库下启用中的技能全部停用才允许删除，这里事前就把还差几个说清楚
  const deleteBlockedReason = (repository: API.SkillRepositoryItem) => {
    const remaining = repository.enabledSkillCount ?? 0;
    if (remaining <= 0) return undefined;
    return intl.formatMessage(
      {
        id: 'pages.skill.repository.delete.blocked',
        defaultMessage: '{count} skills are still enabled. Disable them all to delete this source',
      },
      { count: remaining },
    );
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
              <SyncResult
                repository={repository}
                formatMessage={intl.formatMessage}
                expanded={expandedId === repository.id}
                onToggle={() => setExpandedId(expandedId === repository.id ? null : repository.id)}
              />
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
                      disabled={!!deleteBlockedReason(repository)}
                      tooltip={deleteBlockedReason(repository)}
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
