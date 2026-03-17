import React, { useState } from 'react';
import { Modal, Table, Tag, Button, message, Checkbox } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { batchSaveSkills } from '@/services/ant-design-pro/skill';

interface SyncSkillModalProps {
  visible: boolean;
  repositoryId: number;
  repositoryName: string;
  skills: API.SkillSyncItem[];
  loading: boolean;
  onCancel: () => void;
  onSuccess: () => void;
}

interface SkillWithSelected extends API.SkillSyncItem {
  selected: boolean;
}

const SyncSkillModal: React.FC<SyncSkillModalProps> = ({
  visible,
  repositoryId,
  repositoryName,
  skills,
  loading,
  onCancel,
  onSuccess,
}) => {
  const [saving, setSaving] = React.useState(false);
  const [selectedSkills, setSelectedSkills] = useState<SkillWithSelected[]>([]);

  // 初始化选中状态
  React.useEffect(() => {
    if (skills && skills.length > 0) {
      setSelectedSkills(
        skills.map((skill) => ({ ...skill, selected: true }))
      );
    } else {
      setSelectedSkills([]);
    }
  }, [skills]);

  // 全选/取消全选
  const handleSelectAll = (e: any) => {
    const checked = e.target.checked;
    setSelectedSkills((prev) => prev.map((skill) => ({ ...skill, selected: checked })));
  };

  // 单个技能选中状态变化
  const handleSkillSelect = (index: number, checked: boolean) => {
    setSelectedSkills((prev) => {
      const updated = [...prev];
      updated[index].selected = checked;
      return updated;
    });
  };

  // 获取选中的技能
  const getSelectedSkills = () => {
    return selectedSkills.filter((skill) => skill.selected);
  };

  const handleSync = async () => {
    const selectedSkillsList = getSelectedSkills();
    if (selectedSkillsList.length === 0) {
      message.warning('请至少选择一个技能');
      return;
    }

    setSaving(true);
    try {
      await batchSaveSkills(repositoryId, selectedSkillsList);
      message.success(`同步成功，共保存 ${selectedSkillsList.length} 个技能`);
      onSuccess();
    } catch (error) {
      message.error('同步失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<SkillWithSelected> = [
    {
      title: (
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <Checkbox onChange={handleSelectAll} style={{ marginRight: 8 }} />
          全选
        </div>
      ),
      dataIndex: 'selected',
      key: 'selected',
      width: 60,
      align: 'center',
      render: (_: any, __: SkillWithSelected, index: number) => (
        <Checkbox
          checked={selectedSkills[index]?.selected}
          onChange={(e) => handleSkillSelect(index, e.target.checked)}
        />
      ),
    },
    {
      title: '技能名称',
      dataIndex: 'name',
      key: 'name',
      width: 200,
      render: (text: string) => <span style={{ fontWeight: 500 }}>{text}</span>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || '暂无描述',
    },
    {
      title: '是否已存在',
      dataIndex: 'exists',
      key: 'exists',
      width: 100,
      align: 'center',
      render: (exists: boolean) => (
        <Tag color={exists ? 'orange' : 'green'}>
          {exists ? '已存在' : '不存在'}
        </Tag>
      ),
    },
  ];

  return (
    <Modal
      title={
        <span>
          同步技能到仓库：<Tag color="blue">{repositoryName}</Tag>
        </span>
      }
      open={visible}
      onCancel={onCancel}
      width={900}
      footer={[
        <div key="select-info" style={{ flex: 1 }}>
          <span style={{ color: '#666' }}>
            已选择{' '}
            <span style={{ fontWeight: 'bold', color: '#1890ff' }}>
              {getSelectedSkills().length}
            </span>{' '}
            个技能，共{' '}
            <span style={{ fontWeight: 'bold' }}>{skills.length}</span> 个技能
          </span>
        </div>,
        <Button key="cancel" onClick={onCancel}>
          取消
        </Button>,
        <Button
          key="sync"
          type="primary"
          loading={saving}
          onClick={handleSync}
          disabled={getSelectedSkills().length === 0}
        >
          确认同步 ({getSelectedSkills().length} 个技能)
        </Button>,
      ]}
    >
      <div style={{ marginBottom: 16 }}>
        <span style={{ color: '#666' }}>
          以下技能将同步到仓库中，重名技能将会被覆盖。请勾选需要同步的技能。
        </span>
      </div>
      <Table
        columns={columns}
        dataSource={selectedSkills}
        loading={loading}
        rowKey={(record, index) => `${record.name}-${index}`}
        pagination={false}
        size="small"
        scroll={{ y: 400 }}
      />
    </Modal>
  );
};

export default SyncSkillModal;
