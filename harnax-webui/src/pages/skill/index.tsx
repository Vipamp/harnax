import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { Row, Col, Card, Button, message, Spin, Empty, Tag, Input, Select } from 'antd';
import { GithubOutlined, SearchOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import RepositoryList from './components/RepositoryList';
import RepositoryForm from './components/RepositoryForm';
import SkillList from './components/SkillList';
import SyncSkillModal from './components/SyncSkillModal';
import { getSkillRepositoryPage } from '@/services/ant-design-pro/skillRepository';
import { fetchSkillSourceSkills } from '@/services/ant-design-pro/skillSource';

const SkillManagement: React.FC = () => {
  const intl = useIntl();
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

  // 加载仓库列表
  const loadRepositories = async () => {
    setRepositoryLoading(true);
    try {
      const response = await getSkillRepositoryPage({ pageNum: 1, pageSize: 100 });
      if (response.data) {
        setRepositories(response.data.records || []);
        // 默认选中第一个仓库
        if (response.data.records && response.data.records.length > 0 && !selectedRepository) {
          setSelectedRepository(response.data.records[0]);
        }
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
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

  // 同步仓库
  const handleSyncRepository = async (repository: API.SkillRepositoryItem) => {
    setSyncingRepository(repository);
    setSyncLoading(true);
    try {
      // 调用获取远程技能列表的接口
      const response: any = await fetchSkillSourceSkills(repository.id!);
      if (response.data && Array.isArray(response.data)) {
        setRemoteSkills(response.data);
        setSyncModalVisible(true);
      } else if (response.data && response.data.records) {
        // 兼容分页格式
        setRemoteSkills(response.data.records);
        setSyncModalVisible(true);
      } else {
        message.warning(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
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
      <Row gutter={16}>
        {/* 左侧：仓库列表 */}
        <Col span={6}>
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
                />
              )}
            </Spin>
          </Card>
        </Col>

        {/* 右侧：技能列表 */}
        <Col span={18}>
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
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                {/* 关键词搜索 */}
                <Input
                  placeholder={intl.formatMessage({ id: 'pages.skill.searchPlaceholder', defaultMessage: 'Search skill name' })}
                  prefix={<SearchOutlined style={{ color: '#8c8c9a' }} />}
                  value={filters.name}
                  onChange={(e) => setFilters({ ...filters, name: e.target.value })}
                  allowClear
                  style={{ width: 240 }}
                />
                {/* 状态 */}
                <Select
                  placeholder={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
                  style={{ width: 120 }}
                  value={filters.status}
                  onChange={(value) => setFilters({ ...filters, status: value })}
                  allowClear
                  options={[
                    { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
                    { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
                  ]}
                />
                {/* 重置按钮 */}
                <Button icon={<ReloadOutlined />} onClick={handleResetFilters} style={{ color: 'var(--vip-text-primary)', borderColor: 'var(--vip-border)', background: 'var(--vip-bg-container)' }}>
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
