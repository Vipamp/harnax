import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { Row, Col, Card, Button, message, Spin, Empty, Tag, Input, Select } from 'antd';
import { GithubOutlined, SearchOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useIsMobile } from '@/utils/responsive';
import RepositoryList from './components/RepositoryList';
import RepositoryForm from './components/RepositoryForm';
import SkillList from './components/SkillList';
import { BUILTIN_CLI_SKILL_REPO } from '@/constants/builtinRepository';
import SyncSkillModal from './components/SyncSkillModal';
import { getSkillSourcePage, fetchSkillSourceSkills } from '@/services/ant-design-pro/skillSource';

const SkillManagement: React.FC = () => {
  const intl = useIntl();
  const isMobile = useIsMobile();
  // 仓库相关状态
  const [repositories, setRepositories] = useState<API.SkillRepositoryItem[]>([]);
  const [selectedRepository, setSelectedRepository] = useState<API.SkillRepositoryItem | null>(null);
  const [repositoryLoading, setRepositoryLoading] = useState(false);
  const [repositoryFormVisible, setRepositoryFormVisible] = useState(false);
  const [editingRepository, setEditingRepository] = useState<API.SkillRepositoryItem | null>(null);

  // 技能相关状态
  const [skillListKey, setSkillListKey] = useState(0);

  // 同步弹窗相关状态
  const [syncModalVisible, setSyncModalVisible] = useState(false);
  const [syncLoading, setSyncLoading] = useState(false);
  const [remoteSkills, setRemoteSkills] = useState<API.SkillSyncItem[]>([]);
  const [syncingRepository, setSyncingRepository] = useState<API.SkillRepositoryItem | null>(null);

  // 筛选状态
  const [filters, setFilters] = useState({
    name: '',
    status: undefined as number | undefined,
  });

  // 加载仓库列表（分页拉取全部）
  const loadRepositories = async () => {
    setRepositoryLoading(true);
    try {
      const pageSize = 50;
      const maxPages = 50;
      let pageNum = 1;
      const all: API.SkillRepositoryItem[] = [];
      let total = 0;
      do {
        const response = await getSkillSourcePage({ pageNum, pageSize });
        const records = response.data?.records || [];
        total = response.data?.total || 0;
        all.push(...records);
        if (records.length === 0) break;
        pageNum += 1;
      } while (all.length < total && pageNum <= maxPages);

      setRepositories(all);
      setSelectedRepository((prev) => {
        // Drop a stale selection (e.g. the repository was just deleted)
        if (prev && all.some((r) => r.id === prev.id)) return prev;
        return all.length > 0 ? all[0] : null;
      });
    } catch {
      // 请求失败时全局 errorHandler 已经弹出后端的具体原因，这里再弹一次会重复提示
    } finally {
      setRepositoryLoading(false);
    }
  };

  useEffect(() => {
    loadRepositories();
  }, []);

  // 重置筛选
  const handleResetFilters = () => {
    setFilters({
      name: '',
      status: undefined,
    });
  };

  // 打开创建仓库表单
  const handleCreateRepository = () => {
    setEditingRepository(null);
    setRepositoryFormVisible(true);
  };

  // 打开编辑仓库表单
  const handleEditRepository = (repository: API.SkillRepositoryItem) => {
    setEditingRepository(repository);
    setRepositoryFormVisible(true);
  };

  // 关闭仓库表单
  const handleRepositoryFormClose = () => {
    setRepositoryFormVisible(false);
    setEditingRepository(null);
  };

  // 仓库表单提交成功
  const handleRepositoryFormSuccess = () => {
    handleRepositoryFormClose();
    loadRepositories();
  };

  // 仓库状态切换：局部更新，不刷新列表
  const handleRepositoryToggle = (id: number, status: number) => {
    setRepositories((prev) =>
      prev.map((r) => (r.id === id ? { ...r, status } : r))
    );
  };

  // 仓库删除后刷新
  const handleRepositoryChange = () => {
    loadRepositories();
  };

  // 同步仓库：先拉远端技能清单，再由弹窗勾选落库
  const handleSyncRepository = async (repository: API.SkillRepositoryItem) => {
    setSyncingRepository(repository);
    setSyncLoading(true);
    try {
      const response = await fetchSkillSourceSkills(repository.id!);
      const skills: API.SkillSyncItem[] = response.data || [];
      if (skills.length === 0) {
        // 接口成功但源里没有可同步的技能，弹窗打开也是空的，直接提示更有意义
        message.warning(
          intl.formatMessage({ id: 'pages.skill.sync.empty', defaultMessage: 'No skill found in this source' }),
        );
        // 弹窗不会打开，得把仓库清掉：它既是弹窗的渲染开关，也会残留上一次拉到的技能列表
        setSyncingRepository(null);
        setRemoteSkills([]);
        return;
      }
      setRemoteSkills(skills);
      setSyncModalVisible(true);
    } catch {
      // 交给全局 errorHandler；拉取失败同样不该把隐藏的弹窗留在树上
      setSyncingRepository(null);
      setRemoteSkills([]);
    } finally {
      setSyncLoading(false);
    }
  };

  // 同步成功后的回调
  const handleSyncSuccess = () => {
    setSyncModalVisible(false);
    setRemoteSkills([]);
    setSyncingRepository(null);
    // 刷新技能列表
    setSkillListKey((prev) => prev + 1);
  };

  // 关闭同步弹窗
  const handleSyncModalCancel = () => {
    setSyncModalVisible(false);
    setRemoteSkills([]);
    setSyncingRepository(null);
  };

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ThunderboltOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.skill.title', defaultMessage: 'Skill Management' })}
          </span>
        ),
      }}
    >
      <div style={{ padding: '0', minHeight: 'calc(100vh - 140px)' }}>
      <Row gutter={[16, 16]}>
        {/* 左侧：仓库列表 */}
        <Col xs={24} md={6}>
          <Card
            title={
              <span style={{ fontSize: '14px', fontWeight: 600 }}>
                <GithubOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                {intl.formatMessage({ id: 'pages.skill.repository', defaultMessage: 'Skill Repository' })}
              </span>
            }
            extra={
              <Button type="primary" icon={<GithubOutlined />} onClick={handleCreateRepository}>
                {intl.formatMessage({ id: 'pages.common.create', defaultMessage: 'Create' })}
              </Button>
            }
            styles={{
              body: { padding: '12px', maxHeight: 'calc(100vh - 200px)', overflow: 'auto' },
            }}
          >
            <Spin spinning={repositoryLoading}>
              {repositories.length === 0 ? (
                <Empty description={intl.formatMessage({ id: 'pages.skill.noRepository', defaultMessage: 'No repository' })} />
              ) : (
                <RepositoryList
                  repositories={repositories}
                  selectedRepository={selectedRepository}
                  onSelect={setSelectedRepository}
                  onEdit={handleEditRepository}
                  onToggle={handleRepositoryToggle}
                  onDelete={handleRepositoryChange}
                  onSync={handleSyncRepository}
                  onInstalled={() => setSkillListKey((prev) => prev + 1)}
                />
              )}
            </Spin>
          </Card>
        </Col>

        {/* 右侧：技能列表 */}
        <Col xs={24} md={18}>
          <Card
            title={
              selectedRepository ? (
                <span style={{ fontSize: '14px', fontWeight: 600 }}>
                  <ThunderboltOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                  {intl.formatMessage({ id: 'pages.skill.list', defaultMessage: 'Skill List' })}
                  <Tag color="blue" style={{ marginLeft: 8 }}>
                    {selectedRepository.name}
                  </Tag>
                </span>
              ) : (
                <span style={{ fontSize: '14px', fontWeight: 600 }}>
                  <ThunderboltOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                  {intl.formatMessage({ id: 'pages.skill.list', defaultMessage: 'Skill List' })}
                </span>
              )
            }
            extra={
              <div style={{ display: 'flex', alignItems: 'center', gap: isMobile ? 8 : 12, flexWrap: 'wrap' }}>
                {/* 关键词搜索 */}
                <Input
                  placeholder={intl.formatMessage({ id: 'pages.skill.searchPlaceholder', defaultMessage: 'Search skill name' })}
                  prefix={<SearchOutlined style={{ color: '#8c8c9a' }} />}
                  value={filters.name}
                  onChange={(e) => setFilters({ ...filters, name: e.target.value })}
                  allowClear
                  style={{ width: isMobile ? '100%' : 240, flex: isMobile ? 1 : undefined }}
                  size={isMobile ? 'small' : 'middle'}
                />
                {/* 状态 */}
                <Select
                  placeholder={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
                  style={{ width: isMobile ? '100%' : 120, flex: isMobile ? undefined : undefined }}
                  value={filters.status}
                  onChange={(value) => setFilters({ ...filters, status: value })}
                  allowClear
                  size={isMobile ? 'small' : 'middle'}
                  options={[
                    { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
                    { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
                  ]}
                />
                {/* 重置按钮 */}
                <Button icon={<ReloadOutlined />} onClick={handleResetFilters} size={isMobile ? 'small' : 'middle'} style={{ color: 'var(--vip-text-primary)', borderColor: 'var(--vip-border)', background: 'var(--vip-bg-container)' }}>
                  {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
                </Button>
              </div>
            }
            styles={{
              body: { padding: '12px' },
            }}
          >
            {selectedRepository ? (
              <SkillList
                key={skillListKey}
                repositoryId={selectedRepository.id}
                filters={filters}
                onRefresh={() => setSkillListKey((prev) => prev + 1)}
                readOnly={selectedRepository.name === BUILTIN_CLI_SKILL_REPO}
              />
            ) : (
              <Empty description={intl.formatMessage({ id: 'pages.skill.selectRepository', defaultMessage: "Please select a repository" })} />
            )}
          </Card>
        </Col>
      </Row>

      {/* 仓库表单 */}
      <RepositoryForm
        visible={repositoryFormVisible}
        values={editingRepository}
        onCancel={handleRepositoryFormClose}
        onSuccess={handleRepositoryFormSuccess}
      />

      {/* 同步技能弹窗 */}
      {syncingRepository && (
        <SyncSkillModal
          visible={syncModalVisible}
          repositoryId={syncingRepository.id!}
          repositoryName={syncingRepository.name}
          skills={remoteSkills}
          loading={syncLoading}
          onCancel={handleSyncModalCancel}
          onSuccess={handleSyncSuccess}
        />
      )}
      </div>
    </PageContainer>
  );
};

export default SkillManagement;
