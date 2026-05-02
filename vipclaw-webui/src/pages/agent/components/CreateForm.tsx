import { useIntl, useModel } from '@umijs/max';
import { Modal, Steps, Form, Input, Button, message, Select, Space, Switch } from 'antd';
import React, { useState, useEffect } from 'react';
// @ts-ignore
import { getMcpServerList, getSkillRepositoryList, getSkillListByRepository, getModelList } from '@/services/ant-design-pro/agent';
import { PlusOutlined, MinusOutlined } from '@ant-design/icons';
import { getCurrentUserInfo } from '@/utils/permissionUtil';

const { TextArea } = Input;
const { Step } = Steps;

interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: API.AgentCreateRequest) => Promise<void>;
}

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit }) => {
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
  const [isPublic, setIsPublic] = useState(false);
  const { isAdmin } = getCurrentUserInfo();

  // 初始化表单数据
  useEffect(() => {
    if (visible && currentUser) {
      form.setFieldsValue({
        owner: currentUser.username || currentUser.nickname,
      });
    }
  }, [visible, currentUser]);
  const [mcpConfigs, setMcpConfigs] = useState<Array<{ mcpId?: number; mcpName?: string; enableSkip?: boolean }>>([{}]);

  // Skill 配置列表（支持动态添加）
  const [skillConfigs, setSkillConfigs] = useState<Array<{ 
    repositoryId?: number; 
    repositoryName?: string;
    skillId?: number; 
    skillName?: string;
  }>>([{}]);

  // 加载 MCP 服务器列表
  useEffect(() => {
    if (visible) {
      loadMcpServers();
      loadRepositories();
      loadModels();
    }
  }, [visible]);

  const loadMcpServers = async () => {
    try {
      const res = await getMcpServerList({ pageNum: 1, pageSize: 100 });
      // 只保留启用的 MCP
      const enabledMcps = (res.data?.records || []).filter((item: any) => item.status === 1);
      setMcpServers(enabledMcps);
    } catch (error) {
      console.error('加载 MCP 服务器列表失败', error);
    }
  };

  const loadRepositories = async () => {
    try {
      const res = await getSkillRepositoryList({ pageNum: 1, pageSize: 100 });
      // 只保留启用的仓库
      const enabledRepos = (res.data?.records || []).filter((item: any) => item.status === 1);
      setRepositories(enabledRepos);
    } catch (error) {
      console.error('加载技能仓库列表失败', error);
    }
  };

  const loadModels = async () => {
    try {
      const res = await getModelList({ pageNum: 1, pageSize: 100 });
      // 只保留启用的对话模型
      const enabledModels = (res.data?.records || []).filter((item: any) => 
        item.status === 1 && item.modelType === 'chat'
      );
      setModels(enabledModels);
    } catch (error) {
      console.error('加载模型列表失败', error);
    }
  };

  const loadSkills = async (repositoryId: number) => {
    try {
      const res = await getSkillListByRepository(repositoryId, { pageNum: 1, pageSize: 100 });
      // 只保留启用的技能
      const enabledSkills = (res.data?.records || []).filter((item: any) => item.status === 1);
      setSkills(enabledSkills);
    } catch (error) {
      console.error('加载技能列表失败', error);
      setSkills([]);
    }
  };

  // 处理 MCP 配置变化
  const handleMcpConfigChange = (index: number, field: string, value: any) => {
    const newConfigs = [...mcpConfigs];
    newConfigs[index] = { ...newConfigs[index], [field]: value };
    setMcpConfigs(newConfigs);
    
    // 如果修改了 mcpId，自动填充 mcpName
    if (field === 'mcpId' && value) {
      const selectedMcp = mcpServers.find(mcp => mcp.id === value);
      if (selectedMcp) {
        newConfigs[index].mcpName = selectedMcp.name;
        setMcpConfigs(newConfigs);
      }
    }
  };

  // 添加 MCP 配置
  const addMcpConfig = () => {
    setMcpConfigs([...mcpConfigs, {}]);
  };

  // 删除 MCP 配置
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
    
    // 如果修改了仓库 ID，清空技能选择并加载新仓库的技能
    if (field === 'repositoryId' && value) {
      newConfigs[index].skillId = undefined;
      newConfigs[index].skillName = undefined;
      setSelectedRepoId(value);
      loadSkills(value);
      
      // 设置仓库名称
      const selectedRepo = repositories.find(repo => repo.id === value);
      if (selectedRepo) {
        newConfigs[index].repositoryName = selectedRepo.name;
      }
    }
    
    // 如果修改了 skillId，自动填充 skillName
    if (field === 'skillId' && value) {
      const selectedSkill = skills.find(skill => skill.id === value);
      if (selectedSkill) {
        newConfigs[index].skillName = selectedSkill.name;
      }
    }
    
    setSkillConfigs(newConfigs);
  };

  // 添加技能配置
  const addSkillConfig = () => {
    setSkillConfigs([...skillConfigs, {}]);
  };

  // 删除技能配置
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
        // 验证第一步
        await form.validateFields(['name', 'description', 'systemPrompt']);
      } else if (currentStep === 1) {
        // 验证第二步（MCP 配置）- 可选，允许跳过
        // 不需要验证，可以直接跳过
      } else if (currentStep === 2) {
        // 验证第三步（Skill 配置）- 可选，允许跳过
        // 提交表单
        // 使用 getFieldValue 单独获取每个字段，确保都能获取到
        const name = form.getFieldValue('name');
        const description = form.getFieldValue('description');
        const systemPrompt = form.getFieldValue('systemPrompt');
        const modelId = form.getFieldValue('modelId');
        const owner = form.getFieldValue('owner');
        
        console.log('提交表单数据:', { name, description, systemPrompt, modelId, owner });
        console.log('MCP配置:', mcpConfigs);
        console.log('Skill配置:', skillConfigs);
        
        const submitData: API.AgentCreateRequest = {
          name,
          description,
          systemPrompt,
          modelId,
          owner,
          status: 1,
          isPublic: isPublic ? 1 : 0,
          // mcpList: [{"id":1, "enableSkip":"true"},{"id":2, "enableSkip":"false"}]
          mcpList: mcpConfigs.filter(config => config.mcpId).map(config => ({
            id: config.mcpId,
            enableSkip: config.enableSkip ? 'true' : 'false',
          })),
          // skillList: "1,2,3,4" 只需要 skillId 数组
          skillList: skillConfigs
            .filter(config => config.skillId)
            .map(config => config.skillId)
            .join(','),
        };
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

  // 上一步
  const handlePrev = () => {
    setCurrentStep(currentStep - 1);
  };

  // 关闭弹窗
  const handleClose = () => {
    form.resetFields();
    setCurrentStep(0);
    setMcpConfigs([{}]);
    setSkillConfigs([{}]);
    onCancel();
  };

  return (
    <Modal
      title={intl.formatMessage({ id: 'pages.agent.create', defaultMessage: 'Create Agent' })}
      open={visible}
      onCancel={handleClose}
      footer={null}
      width={800}
      destroyOnClose
    >
      <Steps current={currentStep} style={{ marginBottom: 24 }}>
        <Step title={intl.formatMessage({ id: 'pages.agent.basicInfo', defaultMessage: 'Basic Info' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.mcpConfig', defaultMessage: 'MCP Config' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.skillConfig', defaultMessage: 'Skill Config' })} />
      </Steps>

      <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
        {/* 第一步：基本信息 */}
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
            >
              <Switch
                checked={isPublic}
                onChange={setIsPublic}
                checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
                unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
              />
              <div style={{ marginTop: 4, color: '#999', fontSize: 12 }}>
                {intl.formatMessage({ id: 'pages.agent.publicHint', defaultMessage: 'Other users can view this agent after making it public' })}
              </div>
            </Form.Item>
          </>
        )}

        {/* 第二步：MCP 配置 */}
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
                  placeholder="选择 MCP 服务（可跳过）"
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

        {/* 第三步：技能配置 */}
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
                    placeholder="选择技能仓库"
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
                    placeholder="选择技能"
                    value={config.skillId}
                    onChange={(value) => handleSkillConfigChange(index, 'skillId', value)}
                    style={{ flex: 1 }}
                    disabled={!config.repositoryId}
                    allowClear
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

      <div style={{ marginTop: 24, display: 'flex', justifyContent: 'space-between' }}>
        <Button 
          disabled={currentStep === 0} 
          onClick={handlePrev}
          style={{
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
        >
          {currentStep === 2 ? intl.formatMessage({ id: 'pages.agent.finish', defaultMessage: 'Finish' }) : intl.formatMessage({ id: 'pages.agent.nextStep', defaultMessage: 'Next' })}
        </Button>
      </div>
    </Modal>
  );
};

export default CreateForm;
