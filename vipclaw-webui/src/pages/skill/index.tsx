import React, { useState, useEffect } from 'react';
import { Row, Col, Card, Button, message, Spin, Empty, Tag, Input, Select } from 'antd';
import { GithubOutlined, SearchOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import RepositoryList from './components/RepositoryList';
import RepositoryForm from './components/RepositoryForm';
import SkillList from './components/SkillList';
import { getSkillRepositoryPage } from '@/services/ant-design-pro/skillRepository';

const SkillManagement: React.FC = () => {
  // 仓库相关状态
  const [repositories, setRepositories] = useState<API.SkillRepositoryItem[]>([]);
  const [selectedRepository, setSelectedRepository] = useState<API.SkillRepositoryItem | null>(null);
  const [repositoryLoading, setRepositoryLoading] = useState(false);
  const [repositoryFormVisible, setRepositoryFormVisible] = useState(false);
  const [editingRepository, setEditingRepository] = useState<API.SkillRepositoryItem | null>(null);

  // 技能相关状态
  const [skillListKey, setSkillListKey] = useState(0);

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
      message.error('加载仓库列表失败');
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
    message.info('同步功能开发中，敬请期待');
    // TODO: 调用后端同步接口
    // try {
    //   await syncSkillRepository(repository.id);
    //   message.success('同步成功');
    //   loadRepositories();
    // } catch (error) {
    //   message.error('同步失败');
    // }
  };

  return (
    <div style={{ padding: '24px', background: '#f0f2f5', minHeight: 'calc(100vh - 48px)' }}>
      <Row gutter={16}>
        {/* 左侧：仓库列表 */}
        <Col span={7}>
          <Card
            title={
              <span>
                <GithubOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                技能仓库
              </span>
            }
            extra={
              <Button type="primary" icon={<GithubOutlined />} onClick={handleCreateRepository}>
                新增
              </Button>
            }
            styles={{
              body: { padding: '12px', maxHeight: 'calc(100vh - 200px)', overflow: 'auto' },
            }}
          >
            <Spin spinning={repositoryLoading}>
              {repositories.length === 0 ? (
                <Empty description="暂无仓库" />
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
        <Col span={17}>
          <Card
            title={
              selectedRepository ? (
                <span>
                  <ThunderboltOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                  技能列表
                  <Tag color="blue" style={{ marginLeft: 8 }}>
                    {selectedRepository.name}
                  </Tag>
                </span>
              ) : (
                <span>
                  <ThunderboltOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                  技能列表
                </span>
              )
            }
            extra={
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                {/* 关键词搜索 */}
                <Input
                  placeholder="搜索技能名称"
                  prefix={<SearchOutlined />}
                  value={filters.name}
                  onChange={(e) => setFilters({ ...filters, name: e.target.value })}
                  allowClear
                  style={{ width: 180 }}
                />
                {/* 状态 */}
                <Select
                  placeholder="状态"
                  style={{ width: 100 }}
                  value={filters.status}
                  onChange={(value) => setFilters({ ...filters, status: value })}
                  allowClear
                  options={[
                    { label: '启用', value: 1 },
                    { label: '禁用', value: 0 },
                  ]}
                />
                {/* 重置按钮 */}
                <Button icon={<ReloadOutlined />} onClick={handleResetFilters}>
                  重置
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
              <Empty description="请选择一个仓库" />
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
    </div>
  );
};

export default SkillManagement;
