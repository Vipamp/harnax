import { useIntl, useModel } from '@umijs/max';
import { Steps, Form, Input, Button, message, Select, Space, Switch } from 'antd';
import React, { useState, useEffect } from 'react';
// @ts-ignore
import { getMcpServerList, getSkillRepositoryList, getSkillListByRepository, getModelList } from '@/services/ant-design-pro/agent';
import { PlusOutlined, MinusOutlined, RocketOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';

const { TextArea } = Input;
const { Step } = Steps;

interface UpdateFormProps {
  visible: boolean;
  values: API.AgentItem;
  onCancel: () => void;
  onSubmit: (values: API.AgentUpdateRequest) => Promise<void>;
}

const UpdateForm: React.FC<UpdateFormProps> = ({ visible, values, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [currentStep, setCurrentStep] = useState(0);
  const [form] = Form.useForm();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const [mcpServers, setMcpServers] = useState<API.McpServerItem[]>([]);
  const [repositories, setRepositories] = useState<API.SkillRepositoryItem[]>([]);
  const [skills, setSkills] = useState<API.SkillItem[]>([]);
  const [models, setModels] = useState<API.ModelItem[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<number | null>(null);
  const [isPublic, setIsPublic] = useState(values?.isPublic === 1);
  const { username, isAdmin } = getCurrentUserInfo();

  // MCP 配置列表（支持动态添加）
  const [mcpConfigs, setMcpConfigs] = useState<Array<{ mcpId?: number; mcpName?: string; enableSkip?: boolean }>>([{}]);

  // Skill 配置列表（支持动态添加）
  const [skillConfigs, setSkillConfigs] = useState<Array<{ 
    repositoryId?: number; 
    repositoryName?: string;
    skillId?: number;
    skillName?: string;
    value?: number;  // Select 使用的值
    label?: string;  // Select 显示的标签
  }>>([{}]);

  // 初始化表单数据
  useEffect(() => {
    if (visible && values) {
      form.setFieldsValue({
        name: values.name,
        description: values.description,
        systemPrompt: values.systemPrompt,
        owner: currentUser?.username || currentUser?.nickname || values.owner,
        modelId: values.modelId, // 回填对话模型
      });
      setIsPublic(values.isPublic === 1);

      // 初始化 MCP 配置
      if (values.mcpList && values.mcpList.length > 0) {
        setMcpConfigs(values.mcpList.map(item => ({
          mcpId: item.mcpId,
          mcpName: item.mcpName,
          enableSkip: item.enableSkip === 'true', // 转换为布尔值
        })));
      } else {
        setMcpConfigs([{}]);
      }

      // 初始化技能配置
      if (values.skillList && values.skillList.length > 0) {
        const initialConfigs = values.skillList.map(item => ({
          repositoryId: item.repositoryId,
          repositoryName: item.repositoryName,
          skillId: item.skillId,
          skillName: item.skillName,
          value: item.skillId,  // Select 使用的值
          label: item.skillName || `技能-${item.skillId}`,  // Select 显示的标签
        }));
        setSkillConfigs(initialConfigs);
        
        // 如果有 repositoryId，加载对应的技能列表
        const uniqueRepoIds = Array.from(
          new Set(values.skillList.map(item => item.repositoryId).filter(Boolean))
        );
        uniqueRepoIds.forEach(repoId => {
          loadSkills(repoId!);
        });
      } else {
        setSkillConfigs([{}]);
      }

      // 先加载选项数据，确保回填时选项已存在
      loadMcpServers();
      loadRepositories();
      loadModels();
    }
  }, [visible, values]);

  const loadMcpServers = async () => {
    try {
      const res = await getMcpServerList({ pageNum: 1, pageSize: 100 });
      const enabledMcps = (res.data?.records || []).filter((item: any) => item.status === 1);
      setMcpServers(enabledMcps);
    } catch (error) {
      console.error('加载 MCP 服务器列表失败', error);
    }
  };

  const loadRepositories = async () => {
    try {
      const res = await getSkillRepositoryList({ pageNum: 1, pageSize: 100 });
      const enabledRepos = (res.data?.records || []).filter((item: any) => item.status === 1);
      setRepositories(enabledRepos);
    } catch (error) {
      console.error('加载技能仓库列表失败', error);
    }
  };

  const loadSkills = async (repositoryId: number) => {
    try {
      const res = await getSkillListByRepository(repositoryId, { pageNum: 1, pageSize: 100 });
      const enabledSkills = (res.data?.records || []).filter((item: any) => item.status === 1);
      setSkills(enabledSkills);
    } catch (error) {
      console.error('加载技能列表失败', error);
      setSkills([]);
    }
  };

  const loadModels = async () => {
    try {
      const res = await getModelList({ pageNum: 1, pageSize: 100 });
      const enabledModels = (res.data?.records || []).filter((item: any) => 
        item.status === 1 && item.modelType === 'chat'
      );
      setModels(enabledModels);
    } catch (error) {
      console.error('加载模型列表失败', error);
    }
  };

  // 处理 MCP 配置变化
  const handleMcpConfigChange = (index: number, field: string, value: any) => {
    const newConfigs = [...mcpConfigs];
    newConfigs[index] = { ...newConfigs[index], [field]: value };
    setMcpConfigs(newConfigs);
    
    if (field === 'mcpId' && value) {
      const selectedMcp = mcpServers.find(mcp => mcp.id === value);
      if (selectedMcp) {
        newConfigs[index].mcpName = selectedMcp.name;
        setMcpConfigs(newConfigs);
      }
    }
  };

  const addMcpConfig = () => {
    setMcpConfigs([...mcpConfigs, {}]);
  };

  const removeMcpConfig = (index: number) => {
    if (mcpConfigs.length === 1) {
      message.warning(intl.formatMessage({ id: 'pages.agent.keepOneMcp', defaultMessage: 'Keep at least one MCP configuration' }));
      return;
    }
    const newConfigs = mcpConfigs.filter((_, i) => i !== index);
    setMcpConfigs(newConfigs);
  };

  // 处理技能配置变化
  const handleSkillConfigChange = (index: number, field: string, value: any, repoId?: number) => {
    const newConfigs = [...skillConfigs];
    newConfigs[index] = { ...newConfigs[index], [field]: value };
    
    if (field === 'repositoryId' && value) {
      newConfigs[index].skillId = undefined;
      newConfigs[index].skillName = undefined;
      newConfigs[index].value = undefined;
      newConfigs[index].label = undefined;
      setSelectedRepoId(value);
      loadSkills(value);
      
      const selectedRepo = repositories.find(repo => repo.id === value);
      if (selectedRepo) {
        newConfigs[index].repositoryName = selectedRepo.name;
      }
    }
    
    if (field === 'skillId' && value) {
      const selectedSkill = skills.find(skill => skill.id === value);
      if (selectedSkill) {
        newConfigs[index].skillName = selectedSkill.name;
        newConfigs[index].value = selectedSkill.id;  // 设置 Select 的值
        newConfigs[index].label = selectedSkill.name;  // 设置 Select 的显示标签
      }
    }
    
    setSkillConfigs(newConfigs);
  };

  const addSkillConfig = () => {
    setSkillConfigs([...skillConfigs, {}]);
  };

  const removeSkillConfig = (index: number) => {
    if (skillConfigs.length === 1) {
      message.warning(intl.formatMessage({ id: 'pages.agent.keepOneSkill', defaultMessage: 'Keep at least one skill configuration' }));
      return;
    }
    const newConfigs = skillConfigs.filter((_, i) => i !== index);
    setSkillConfigs(newConfigs);
  };

  // 下一步
  const handleNext = async () => {
    try {
      if (currentStep === 0) {
        // 第一步：验证基本信息
        await form.validateFields(['name', 'description', 'systemPrompt']);
      } else if (currentStep === 1) {
        // 第二步：MCP 配置（可选），直接下一步
        // MCP 配置已经在变化时实时保存到 mcpConfigs 状态中
      } else if (currentStep === 2) {
        // 第三步：提交表单
        // 先验证并获取第一步的表单值
        const formValues = await form.validateFields(['name', 'description', 'systemPrompt', 'modelId', 'owner']);
        console.log('表单验证后的值:', formValues);
        console.log('MCP 配置状态:', mcpConfigs);
        console.log('Skill 配置状态:', skillConfigs);
        
        const submitData: API.AgentUpdateRequest = {
          name: formValues.name,
          description: formValues.description,
          systemPrompt: formValues.systemPrompt,
          modelId: formValues.modelId,
          owner: formValues.owner,
          status: 1,
          isPublic: isPublic ? 1 : 0,
          // MCP 配置：使用当前状态中的值
          mcpList: mcpConfigs.filter(config => config.mcpId).map(config => ({
            id: config.mcpId,
            enableSkip: config.enableSkip ? 'true' : 'false',
          })),
          // skillList: "1,2,3,4" 只需要 skillId 数组
          skillList: skillConfigs
            .filter(config => config.skillId)
            .map(config => config.skillId!.toString())
            .join(','),
        };
        console.log('最终提交数据:', submitData);
        await onSubmit(submitData);
        form.resetFields();
        setCurrentStep(0);
        setMcpConfigs([{}]);
        setSkillConfigs([{}]);
        return;
      }
      setCurrentStep(currentStep + 1);
    } catch (error) {
      console.error('验证失败', error);
    }
  };

  const handlePrev = () => {
    setCurrentStep(currentStep - 1);
  };

  const handleClose = () => {
    form.resetFields();
    setCurrentStep(0);
    setMcpConfigs([{}]);
    setSkillConfigs([{}]);
    onCancel();
  };

  return (
    <FormModal
      open={visible}
      onCancel={handleClose}
      size="xl"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.agent.edit', defaultMessage: 'Edit Agent' }),
        subtitle: intl.formatMessage({ id: 'pages.agent.edit.subtitle', defaultMessage: 'Modify agent configuration, MCP services and skills' }),
        icon: <RocketOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-warning) 0%, var(--vip-warning-light) 100%)',
        iconShadowColor: 'rgba(250, 173, 20, 0.25)',
      }}
    >
      <Steps current={currentStep} style={{ marginBottom: 24 }}>
        <Step title={intl.formatMessage({ id: 'pages.agent.basicInfo', defaultMessage: 'Basic Info' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.mcpConfig', defaultMessage: 'MCP Config' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.skillConfig', defaultMessage: 'Skill Config' })} />
      </Steps>

      <Form 
        form={form} 
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        style={{ marginTop: 24 }}
      >
        {currentStep === 0 && (
          <>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.agent.name', defaultMessage: 'Agent Name' })}
              name="name"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.nameRequired', defaultMessage: 'Please enter agent name' }) }]}>
              <Input placeholder={intl.formatMessage({ id: 'pages.agent.namePlaceholder', defaultMessage: 'Please enter agent name' })} />
            </Form.Item>

            <Form.Item
              label={intl.formatMessage({ id: 'pages.agent.description', defaultMessage: 'Description' })}
              name="description"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.descriptionRequired', defaultMessage: 'Please enter description' }) }]}>
              <TextArea rows={3} placeholder={intl.formatMessage({ id: 'pages.agent.descriptionPlaceholder', defaultMessage: 'Please enter description' })} />
            </Form.Item>

            <Form.Item
              label={intl.formatMessage({ id: 'pages.agent.systemPrompt', defaultMessage: 'System Prompt' })}
              name="systemPrompt"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.systemPromptRequired', defaultMessage: 'Please enter system prompt' }) }]}>
              <TextArea 
                rows={8} 
                placeholder={intl.formatMessage({ id: 'pages.agent.systemPromptPlaceholder', defaultMessage: 'Please enter system prompt, supports Markdown syntax' })}
                style={{ fontFamily: 'monospace' }}
              />
            </Form.Item>

            <Form.Item
              label={intl.formatMessage({ id: 'pages.agent.model', defaultMessage: 'Model' })}
              name="modelId"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.modelRequired', defaultMessage: 'Please select model' }) }]}>
              <Select
                placeholder={intl.formatMessage({ id: 'pages.agent.modelPlaceholder', defaultMessage: 'Please select model' })}
                allowClear
                options={models.map(model => ({
                  label: `${model.modelName} - ${model.providerName || intl.formatMessage({ id: 'pages.common.unknownProvider', defaultMessage: 'Unknown provider' })} ¥${model.price || 0}/M`,
                  value: model.id,
                }))}
              />
            </Form.Item>

            <Form.Item
              label={intl.formatMessage({ id: 'pages.agent.owner', defaultMessage: 'Owner' })}
              name="owner"
            >
              <Input 
                placeholder={intl.formatMessage({ id: 'pages.agent.ownerPlaceholder', defaultMessage: 'Auto-filled with current user' })}
                disabled
              />
            </Form.Item>

            {/* 是否公开 */}
            <Form.Item
              label={intl.formatMessage({ id: 'pages.agent.isPublic', defaultMessage: 'Is Public' })}
              extra={
                isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false)
                  ? intl.formatMessage({ id: 'pages.agent.noPermission', defaultMessage: 'You do not have permission to modify this setting' })
                  : intl.formatMessage({ id: 'pages.agent.publicHint', defaultMessage: 'Other users can view this agent after making it public' })
              }
            >
              <Switch
                checked={isPublic}
                onChange={setIsPublic}
                checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
                unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
                disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false)}
              />
            </Form.Item>
          </>
        )}

        {currentStep === 1 && (
          <div>
            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ color: 'var(--vip-text-secondary)', fontSize: '14px' }}>
                {intl.formatMessage({ id: 'pages.agent.mcpOptional', defaultMessage: 'Configure MCP services (optional, can be skipped)' })}
              </span>
              <Button type="dashed" icon={<PlusOutlined />} onClick={addMcpConfig}>
                {intl.formatMessage({ id: 'pages.agent.addMcp', defaultMessage: 'Add MCP' })}
              </Button>
            </div>

            {mcpConfigs.map((config, index) => (
              <Space key={index} style={{ width: '100%', marginBottom: 16, padding: 16, border: '1px solid var(--vip-border)', borderRadius: '8px', background: 'var(--vip-bg-layout)' }} direction="vertical">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                  <span style={{ fontWeight: 500, color: 'var(--vip-text-primary)' }}>{intl.formatMessage({ id: 'pages.agent.mcp', defaultMessage: 'MCP' })} #{index + 1}</span>
                  {mcpConfigs.length > 1 && (
                    <Button 
                      type="link" 
                      danger 
                      icon={<MinusOutlined />} 
                      onClick={() => removeMcpConfig(index)}
                      size="small"
                    >
                      {intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}
                    </Button>
                  )}
                </div>
                <Select
                  placeholder={intl.formatMessage({ id: 'pages.agent.mcpPlaceholder', defaultMessage: 'Select MCP service (optional, can be skipped)' })}
                  value={config.mcpId}
                  onChange={(value) => handleMcpConfigChange(index, 'mcpId', value)}
                  style={{ width: '100%' }}
                  allowClear
                  options={mcpServers.map(mcp => ({
                    label: mcp.name,
                    value: mcp.id,
                  }))}
                />
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ color: 'var(--vip-text-primary)' }}>{intl.formatMessage({ id: 'pages.agent.allowSkip', defaultMessage: 'Allow Skip' })}</span>
                  <Switch
                    size="small"
                    checked={config.enableSkip}
                    onChange={(checked) => handleMcpConfigChange(index, 'enableSkip', checked)}
                  />
                </div>
              </Space>
            ))}
          </div>
        )}

        {currentStep === 2 && (
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
                  <span style={{ fontWeight: 500, color: 'var(--vip-text-primary)' }}>{intl.formatMessage({ id: 'pages.agent.skill', defaultMessage: 'Skill' })} #{index + 1}</span>
                  {skillConfigs.length > 1 && (
                    <Button 
                      type="link" 
                      danger 
                      icon={<MinusOutlined />} 
                      onClick={() => removeSkillConfig(index)}
                      size="small"
                    >
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
                    options={repositories.map(repo => ({
                      label: repo.name,
                      value: repo.id,
                    }))}
                  />
                  <Select
                    placeholder={intl.formatMessage({ id: 'pages.agent.skillPlaceholder', defaultMessage: 'Select skill' })}
                    value={config.value ? { value: config.value, label: config.label } : undefined}
                    onChange={(value) => {
                      // labelInValue 模式下，value 包含 { value, label }
                      if (value && typeof value === 'object') {
                        handleSkillConfigChange(index, 'skillId', value.value, config.repositoryId);
                      } else if (value) {
                        handleSkillConfigChange(index, 'skillId', value, config.repositoryId);
                      }
                    }}
                    style={{ flex: 1 }}
                    disabled={!config.repositoryId}
                    allowClear
                    labelInValue
                    options={skills.map(skill => ({
                      label: skill.name,
                      value: skill.id,
                    }))}
                  />
                </div>
              </Space>
            ))}
          </div>
        )}
      </Form>

      <div style={{ 
        display: 'flex', 
        justifyContent: 'space-between', 
        marginTop: '12px',
        paddingTop: '10px',
        borderTop: '1px solid var(--vip-border)'
      }}>
        <Button 
          disabled={currentStep === 0} 
          onClick={handlePrev}
          style={{
            fontSize: '12px',
            fontWeight: 500,
            height: '32px',
            padding: '4px 20px',
            borderRadius: '6px',
            background: 'var(--vip-bg-container)',
            borderColor: 'var(--vip-border)',
            color: 'var(--vip-text-primary)',
          }}
        >
          {intl.formatMessage({ id: 'pages.common.previousStep', defaultMessage: 'Previous Step' })}
        </Button>
        <Button 
          type="primary" 
          onClick={handleNext}
          style={{
            fontSize: '12px',
            fontWeight: 500,
            height: '32px',
            padding: '4px 20px',
            borderRadius: '6px',
          }}
        >
          {currentStep === 2 ? intl.formatMessage({ id: 'pages.common.save', defaultMessage: 'Save' }) : intl.formatMessage({ id: 'pages.agent.nextStep', defaultMessage: 'Next' })}
        </Button>
      </div>
    </FormModal>
  );
};

export default UpdateForm;
