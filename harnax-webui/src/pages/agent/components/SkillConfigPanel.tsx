import { useIntl } from '@umijs/max';
import { Button, Select, Space } from 'antd';
import React from 'react';
import { PlusOutlined, MinusOutlined } from '@ant-design/icons';

export type SkillConfigState = {
  repositoryId?: number;
  repositoryName?: string;
  skillId?: number;
  skillName?: string;
  value?: number;
  label?: string;
};

interface SkillConfigPanelProps {
  skillConfigs: SkillConfigState[];
  setSkillConfigs: (configs: SkillConfigState[]) => void;
  repositories: API.SkillRepositoryItem[];
  skills: API.SkillItem[];
  onLoadSkills: (repositoryId: number) => void;
}

const SkillConfigPanel: React.FC<SkillConfigPanelProps> = ({
  skillConfigs,
  setSkillConfigs,
  repositories,
  skills,
  onLoadSkills,
}) => {
  const intl = useIntl();

  const handleSkillConfigChange = (index: number, field: string, value: any) => {
    const newConfigs = [...skillConfigs];
    newConfigs[index] = { ...newConfigs[index], [field]: value };

    if (field === 'repositoryId' && value) {
      newConfigs[index].skillId = undefined;
      newConfigs[index].skillName = undefined;
      newConfigs[index].value = undefined;
      newConfigs[index].label = undefined;
      onLoadSkills(value);

      const selectedRepo = repositories.find(repo => repo.id === value);
      if (selectedRepo) {
        newConfigs[index].repositoryName = selectedRepo.name;
      }
    }

    if (field === 'skillId' && value) {
      const selectedSkill = skills.find(skill => skill.id === value);
      if (selectedSkill) {
        newConfigs[index].skillName = selectedSkill.name;
        newConfigs[index].value = selectedSkill.id;
        newConfigs[index].label = selectedSkill.name;
      }
    }

    setSkillConfigs(newConfigs);
  };

  const addSkillConfig = () => setSkillConfigs([...skillConfigs, {}]);

  const removeSkillConfig = (index: number) => {
    if (skillConfigs.length === 1) return;
    setSkillConfigs(skillConfigs.filter((_, i) => i !== index));
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ color: 'var(--vip-text-secondary)', fontSize: '14px' }}>
          {intl.formatMessage({ id: 'pages.agent.skillConfigOptional', defaultMessage: 'Configure skills (optional, can be skipped)' })}
        </span>
        <Button type="dashed" icon={<PlusOutlined />} onClick={addSkillConfig}>
          {intl.formatMessage({ id: 'pages.agent.addSkill', defaultMessage: 'Add Skill' })}
        </Button>
      </div>

      {skillConfigs.map((config, index) => (
        <Space key={index} style={{ width: '100%', marginBottom: 16, padding: 16, border: '1px solid var(--vip-border)', borderRadius: '8px', background: 'var(--vip-bg-layout)' }} direction="vertical">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <span style={{ fontWeight: 500, color: 'var(--vip-text-primary)', fontSize: '14px' }}>
              {intl.formatMessage({ id: 'pages.agent.skill', defaultMessage: 'Skill' })} #{index + 1}
            </span>
            {skillConfigs.length > 1 && (
              <Button type="link" danger icon={<MinusOutlined />} onClick={() => removeSkillConfig(index)} size="small">
                {intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}
              </Button>
            )}
          </div>
          <div style={{ display: 'flex', gap: 16, width: '100%' }}>
            <Select
              placeholder={intl.formatMessage({ id: 'pages.agent.repositoryPlaceholder', defaultMessage: 'Select skill repository' })}
              value={config.repositoryId}
              onChange={(value) => handleSkillConfigChange(index, 'repositoryId', value)}
              style={{ flex: 1 }}
              allowClear
              options={repositories.map(repo => ({ label: repo.name, value: repo.id }))}
            />
            <Select
              placeholder={intl.formatMessage({ id: 'pages.agent.skillPlaceholder', defaultMessage: 'Select skill' })}
              value={config.value ? { value: config.value, label: config.label } : undefined}
              onChange={(value) => {
                if (value && typeof value === 'object') {
                  handleSkillConfigChange(index, 'skillId', value.value);
                } else if (value) {
                  handleSkillConfigChange(index, 'skillId', value);
                }
              }}
              style={{ flex: 1 }}
              disabled={!config.repositoryId}
              allowClear
              labelInValue
              optionRender={(option) => {
                const skill = option.data as API.SkillItem;
                return (
                  <div style={{ padding: '4px 0' }}>
                    <div style={{ fontWeight: 500, fontSize: '14px', color: 'var(--vip-text-primary)' }}>
                      {skill.name}
                    </div>
                    {skill.repositoryName && (
                      <div style={{ fontSize: '12px', color: 'var(--vip-text-tertiary)', marginTop: '2px' }}>
                        {intl.formatMessage({ id: 'pages.agent.skill.repository', defaultMessage: 'Repository' })}: {skill.repositoryName}
                      </div>
                    )}
                    {skill.description && (
                      <div style={{ fontSize: '12px', color: 'var(--vip-text-secondary)', marginTop: '2px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {skill.description}
                      </div>
                    )}
                  </div>
                );
              }}
              options={skills.map(skill => ({ label: skill.name, value: skill.id, ...skill }))}
            />
          </div>
        </Space>
      ))}
    </div>
  );
};

export default SkillConfigPanel;
