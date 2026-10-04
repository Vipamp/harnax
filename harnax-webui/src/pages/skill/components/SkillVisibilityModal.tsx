import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Descriptions, InputNumber, Radio, Select, Spin, Tag, Typography, message } from 'antd';
import { FilterOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { getSkillVisibility, updateSkillVisibility } from '@/services/ant-design-pro/skill';
import { getUserPage } from '@/services/ant-design-pro/user';
import { FormModal } from '@/components/FormModal';

/** Backend constants on `SkillVisibilityPolicy`; the four modes the runtime filter knows. */
const MODE_ALL = 'ALL';
const MODE_CANARY = 'CANARY';
const MODE_ALLOW_LIST = 'ALLOW_LIST';
const MODE_ENV = 'ENV';

interface UserOption {
  value: number;
  label: string;
}

interface SkillVisibilityModalProps {
  skillId: number;
  skillName: string;
  open: boolean;
  onCancel: () => void;
  /** Called after a save landed, so the list re-reads the column this form just changed */
  onSaved: () => void;
}

/**
 * Who may load one skill, and from which environment.
 *
 * The whole rule is posted on every save and the fields the chosen mode does not use are cleared by the
 * server, so switching away from a rollout takes its number along with it — a stale percentage left on a
 * row that now reads ALL would be trusted as the current state by the next operator.
 */
const SkillVisibilityModal: React.FC<SkillVisibilityModalProps> = ({
  skillId,
  skillName,
  open,
  onCancel,
  onSaved,
}) => {
  const intl = useIntl();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [searching, setSearching] = useState(false);
  const [editable, setEditable] = useState(true);
  const [tenantId, setTenantId] = useState<number | undefined>(undefined);
  const [updateTime, setUpdateTime] = useState<string | undefined>(undefined);
  const [mode, setMode] = useState<string>(MODE_ALL);
  const [canaryPct, setCanaryPct] = useState<number | null>(null);
  const [userIds, setUserIds] = useState<number[]>([]);
  const [environments, setEnvironments] = useState<string[]>([]);
  /** The users the stored policy named, kept so a selected id still reads as a person after a search */
  const [storedUsers, setStoredUsers] = useState<UserOption[]>([]);
  const [searchUsers, setSearchUsers] = useState<UserOption[]>([]);

  useEffect(() => {
    if (!open) return undefined;
    // A reload started before the previous read landed must not be overwritten by it
    let cancelled = false;
    setLoading(true);
    getSkillVisibility(skillId)
      .then((res) => {
        if (cancelled) return;
        const info: API.SkillVisibilityInfo | undefined = res?.data;
        if (res?.code !== 200 || !info) {
          message.error(
            res?.message ||
              intl.formatMessage({ id: 'pages.skill.visibility.loadFailed', defaultMessage: 'Failed to load visibility' }),
          );
          return;
        }
        setEditable(info.editable !== false);
        setTenantId(info.tenantId);
        setUpdateTime(info.updateTime);
        setMode(info.mode || MODE_ALL);
        setCanaryPct(info.canaryPct ?? null);
        setUserIds(info.userIds || []);
        setEnvironments(info.environments || []);
        setStoredUsers(
          (info.users || []).map((user) => ({
            value: user.id,
            label:
              user.username ||
              intl.formatMessage(
                { id: 'pages.skill.visibility.goneUser', defaultMessage: 'id {id} (account gone)' },
                { id: user.id },
              ),
          })),
        );
        setSearchUsers([]);
      })
      .catch(() => {
        if (!cancelled) {
          message.error(intl.formatMessage({ id: 'pages.skill.visibility.loadFailed', defaultMessage: 'Failed to load visibility' }));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, skillId]);

  const userOptions = useMemo(() => {
    const merged = new Map<number, string>();
    for (const option of storedUsers) merged.set(option.value, option.label);
    // A search hit re-names the same person with more text than the stored row had
    for (const option of searchUsers) merged.set(option.value, option.label);
    return Array.from(merged, ([value, label]) => ({ value, label }));
  }, [storedUsers, searchUsers]);

  const searchTenantUsers = async (keyword: string) => {
    setSearching(true);
    try {
      // Scoped to the skill's own tenant, which is exactly the set the save checks membership against.
      // Asking without it would offer people the write is certain to refuse.
      const res = await getUserPage({ pageNum: 1, pageSize: 50, keyword: keyword || undefined, tenantId });
      setSearchUsers(
        (res?.data?.records || []).map((user: any) => ({
          value: user.id,
          label: user.nickname ? `${user.username} (${user.nickname})` : user.username,
        })),
      );
    } catch (error: any) {
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({ id: 'pages.skill.visibility.userSearchFailed', defaultMessage: 'Failed to search users' }),
      );
    } finally {
      setSearching(false);
    }
  };

  // Switching mode clears the parameter the previous mode was using, so nothing survives a mode the form
  // no longer shows. The server clears the stored columns the same way.
  const handleModeChange = (next: string) => {
    setMode(next);
    if (next !== MODE_CANARY) setCanaryPct(null);
    if (next !== MODE_ALLOW_LIST) setUserIds([]);
    if (next !== MODE_ENV) setEnvironments([]);
  };

  // The wide limits (200 users, 64 characters per label, 255 in total) are the server's to enforce and its
  // message names the offending value; only "this mode was given nothing to gate by" is gated here.
  const missingParameter =
    (mode === MODE_CANARY && (canaryPct === null || canaryPct === undefined)) ||
    (mode === MODE_ALLOW_LIST && userIds.length === 0) ||
    (mode === MODE_ENV && environments.length === 0);

  const handleSave = async () => {
    const payload: API.SkillVisibilityUpdateRequest = {
      mode,
      canaryPct: mode === MODE_CANARY ? canaryPct : undefined,
      userIds: mode === MODE_ALLOW_LIST ? userIds : undefined,
      environments: mode === MODE_ENV ? environments : undefined,
    };
    setSaving(true);
    try {
      const res = await updateSkillVisibility(skillId, payload);
      if (res?.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.skill.visibility.saved', defaultMessage: 'Visibility updated' }));
        onSaved();
        onCancel();
      } else {
        message.error(
          res?.message ||
            intl.formatMessage({ id: 'pages.skill.visibility.saveFailed', defaultMessage: 'Failed to update visibility' }),
        );
      }
    } catch (error: any) {
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({ id: 'pages.skill.visibility.saveFailed', defaultMessage: 'Failed to update visibility' }),
      );
    } finally {
      setSaving(false);
    }
  };

  const modeOption = (value: string, labelId: string, defaultMessage: string) => (
    <Radio value={value}>{intl.formatMessage({ id: labelId, defaultMessage })}</Radio>
  );

  const modeHint = {
    [MODE_ALL]: intl.formatMessage({
      id: 'pages.skill.visibility.hint.all',
      defaultMessage: 'Every user of every session loads this skill',
    }),
    [MODE_CANARY]: intl.formatMessage({
      id: 'pages.skill.visibility.hint.canary',
      defaultMessage: 'A fixed slice of users loads it. The same user keeps the same answer: the slice is chosen per user and skill, not per call.',
    }),
    [MODE_ALLOW_LIST]: intl.formatMessage({
      id: 'pages.skill.visibility.hint.allowList',
      defaultMessage: 'Only the listed users load it. Users of a channel conversation (Feishu, WeCom, WeChat) have no harnax account and are never in it.',
    }),
    [MODE_ENV]: intl.formatMessage({
      id: 'pages.skill.visibility.hint.env',
      defaultMessage: 'Only the runtime environments named here load it, matched without regard to case.',
    }),
  } as Record<string, string>;

  return (
    <FormModal
      open={open}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.skill.visibility.title', defaultMessage: 'Skill visibility' }),
        subtitle: intl.formatMessage(
          { id: 'pages.skill.visibility.subtitle', defaultMessage: 'Who may load {name} at runtime' },
          { name: skillName },
        ),
        icon: <FilterOutlined />,
      }}
      footer={
        editable
          ? [
              <Button key="cancel" onClick={onCancel}>
                {intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
              </Button>,
              <Button
                key="save"
                type="primary"
                loading={saving}
                disabled={missingParameter || loading}
                onClick={handleSave}
              >
                {intl.formatMessage({ id: 'pages.common.save', defaultMessage: 'Save' })}
              </Button>,
            ]
          : null
      }
    >
      <Spin spinning={loading}>
        {!editable && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={intl.formatMessage({
              id: 'pages.skill.visibility.readonly',
              defaultMessage: 'This skill belongs to another tenant, so its rollout is read-only here. Only the tenant that owns it can change it.',
            })}
          />
        )}
        <div style={{ marginBottom: 8 }}>
          <Typography.Text strong>{intl.formatMessage({ id: 'pages.skill.visibility.mode', defaultMessage: 'Who may load it' })}</Typography.Text>
        </div>
        <Radio.Group value={mode} disabled={!editable} onChange={(e) => handleModeChange(e.target.value)}>
          {modeOption(MODE_ALL, 'pages.skill.visibility.mode.all', 'Everyone')}
          {modeOption(MODE_CANARY, 'pages.skill.visibility.mode.canary', 'Rollout by user')}
          {modeOption(MODE_ALLOW_LIST, 'pages.skill.visibility.mode.allowList', 'Named users')}
          {modeOption(MODE_ENV, 'pages.skill.visibility.mode.env', 'By environment')}
        </Radio.Group>
        <div style={{ margin: '8px 0 16px', color: 'var(--vip-text-secondary)', fontSize: 12 }}>{modeHint[mode]}</div>

        {mode === MODE_CANARY && editable && (
          <div>
            <Typography.Text strong>{intl.formatMessage({ id: 'pages.skill.visibility.pct', defaultMessage: 'Rollout percentage' })}</Typography.Text>
            <div style={{ marginTop: 8 }}>
              <InputNumber min={0} max={100} value={canaryPct} onChange={(value) => setCanaryPct(value)} addonAfter="%" style={{ width: 160 }} />
            </div>
          </div>
        )}

        {mode === MODE_ALLOW_LIST && editable && (
          <div>
            <Typography.Text strong>{intl.formatMessage({ id: 'pages.skill.visibility.users', defaultMessage: 'Allowed users' })}</Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Select
                mode="multiple"
                style={{ width: '100%' }}
                value={userIds}
                onChange={(values) => setUserIds(values as number[])}
                options={userOptions}
                filterOption={false}
                onSearch={(keyword) => searchTenantUsers(keyword)}
                loading={searching}
                notFoundContent={searching ? intl.formatMessage({ id: 'pages.common.loading', defaultMessage: 'Loading...' }) : null}
                placeholder={intl.formatMessage({
                  id: 'pages.skill.visibility.userSearch',
                  defaultMessage: 'Search users of this tenant',
                })}
              />
            </div>
          </div>
        )}

        {mode === MODE_ENV && editable && (
          <div>
            <Typography.Text strong>{intl.formatMessage({ id: 'pages.skill.visibility.environments', defaultMessage: 'Environment labels' })}</Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Select
                mode="tags"
                style={{ width: '100%' }}
                value={environments}
                onChange={(values) => setEnvironments(values as string[])}
                tokenSeparators={[',', ' ']}
                placeholder={intl.formatMessage({
                  id: 'pages.skill.visibility.envPlaceholder',
                  defaultMessage: 'Type a label and press Enter, e.g. staging',
                })}
              />
            </div>
          </div>
        )}

        {!editable && (
          <Descriptions column={1} size="small" style={{ marginTop: 16 }}>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.visibility.mode', defaultMessage: 'Who may load it' })}>
              <Tag>{mode}</Tag>
            </Descriptions.Item>
            {mode === MODE_CANARY && (
              <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.visibility.pct', defaultMessage: 'Rollout percentage' })}>
                {canaryPct === null || canaryPct === undefined ? '-' : `${canaryPct}%`}
              </Descriptions.Item>
            )}
            {mode === MODE_ALLOW_LIST && (
              <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.visibility.users', defaultMessage: 'Allowed users' })}>
                {storedUsers.length === 0 ? '-' : storedUsers.map((option) => option.label).join('、')}
              </Descriptions.Item>
            )}
            {mode === MODE_ENV && (
              <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.visibility.environments', defaultMessage: 'Environment labels' })}>
                {environments.length === 0 ? '-' : environments.join('、')}
              </Descriptions.Item>
            )}
            {updateTime && (
              <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.visibility.lastChange', defaultMessage: 'Last changed' })}>
                {updateTime}
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Spin>
    </FormModal>
  );
};

export default SkillVisibilityModal;
