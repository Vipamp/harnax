import React, { useState } from 'react';
import { Modal, Table, Tag, Button, message, Checkbox } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { batchSaveSkills } from '@/services/ant-design-pro/skill';
import { useIntl } from '@umijs/max';

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
  const intl = useIntl();
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
      message.warning(intl.formatMessage({ id: 'pages.skill.sync.selectAtLeastOne', defaultMessage: "Please select at least one skill" }));
      return;
    }
  
    setSaving(true);
    try {
      // 只传递技能名称列表
      const skillNames = selectedSkillsList.map((skill) => skill.name!);
      await batchSaveSkills(repositoryId, skillNames);
      message.success(intl.formatMessage({ id: 'pages.skill.sync.success', defaultMessage: 'Sync successful, saved {count} skills' }, { count: selectedSkillsList.length }));
      onSuccess();
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.skill.sync.error', defaultMessage: 'Sync failed, please try again' }));
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<SkillWithSelected> = [
    {
      title: (
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <Checkbox onChange={handleSelectAll} style={{ marginRight: 8 }} />
          {intl.formatMessage({ id: "pages.skill.sync.selectAll", defaultMessage: "Select All" })}
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
      title: intl.formatMessage({ id: 'pages.skill.sync.name', defaultMessage: 'Skill Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 200,
      render: (text: string) => <span style={{ fontWeight: 500 }}>{text}</span>,
    },
    {
      title: intl.formatMessage({ id: "pages.skill.sync.description", defaultMessage: "Description" }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || intl.formatMessage({ id: "pages.common.noDescription", defaultMessage: "No description" }),
    },
    {
      title: intl.formatMessage({ id: "pages.skill.sync.exists", defaultMessage: "Already Exists" }),
      dataIndex: 'exists',
      key: 'exists',
      width: 100,
      align: 'center',
      render: (exists: boolean) => (
        <Tag color={exists ? 'orange' : 'green'}>
          {intl.formatMessage({ id: "pages.skill.sync.existsYes", defaultMessage: "Exists" })}
        </Tag>
      ),
    },
  ];

  return (
    <Modal
      title={
        <span>
          {intl.formatMessage({ id: "pages.skill.sync.title", defaultMessage: "Sync Skills to Repository" })}:<Tag color="blue">{repositoryName}</Tag>
        </span>
      }
      open={visible}
      onCancel={onCancel}
      width={900}
      footer={[
        <div key="select-info" style={{ flex: 1 }}>
          <span style={{ color: '#666' }}>
            {intl.formatMessage({ id: 'pages.skill.sync.selectedCount', defaultMessage: 'Selected' })}{' '}
            <span style={{ fontWeight: 'bold', color: '#1890ff' }}>
              {getSelectedSkills().length}
            </span>{' '}
            {intl.formatMessage({ id: 'pages.skill.sync.skills', defaultMessage: 'skills' })}, {intl.formatMessage({ id: 'pages.skill.sync.total', defaultMessage: 'total' })}{' '}
            <span style={{ fontWeight: 'bold' }}>{skills.length}</span> {intl.formatMessage({ id: 'pages.skill.sync.skills', defaultMessage: 'skills' })}
          </span>
        </div>,
        <Button key="cancel" onClick={onCancel}>
          {intl.formatMessage({ id: "pages.common.cancel", defaultMessage: "Cancel" })}
        </Button>,
        <Button
          key="sync"
          type="primary"
          loading={saving}
          onClick={handleSync}
          disabled={getSelectedSkills().length === 0}
        >
          {intl.formatMessage({ id: "pages.skill.sync.confirm", defaultMessage: "Confirm Sync" })} ({getSelectedSkills().length} {intl.formatMessage({ id: "pages.skill.sync.skills", defaultMessage: "skills" })})
        </Button>,
      ]}
    >
      <div style={{ marginBottom: 16 }}>
        <span style={{ color: '#666' }}>
          {intl.formatMessage({ id: "pages.skill.sync.description", defaultMessage: "The following skills will be synchronized to the repository. Duplicate skills will be overwritten. Please select the skills you want to sync." })}
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
