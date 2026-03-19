import { Modal, Steps, Form, Input, Button, message, Select, Space, Switch } from 'antd';
import React, { useState, useEffect } from 'react';
// @ts-ignore
import { getMcpServerList, getSkillRepositoryList, getSkillListByRepository, getModelList } from '@/services/ant-design-pro/agent';
import { PlusOutlined, MinusOutlined } from '@ant-design/icons';
// @ts-ignore
import { useModel } from '@umijs/max';
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
      message.warning('至少保留一个 MCP 配置');
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
      message.warning('至少保留一个技能配置');
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
    <Modal
      title="编辑智能体"
      open={visible}
      onCancel={handleClose}
      footer={null}
      width={800}
      destroyOnClose
    >
      <Steps current={currentStep} style={{ marginBottom: 24 }}>
        <Step title="基本信息" />
        <Step title="MCP 配置" />
        <Step title="技能配置" />
      </Steps>

      <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
        {currentStep === 0 && (
          <>
            <Form.Item
              label="智能体名称"
              name="name"
              rules={[{ required: true, message: '请输入智能体名称' }]}
            >
              <Input placeholder="请输入智能体名称" />
            </Form.Item>

            <Form.Item
              label="智能体描述"
              name="description"
              rules={[{ required: true, message: '请输入智能体描述' }]}
            >
              <TextArea rows={3} placeholder="请输入智能体描述" />
            </Form.Item>

            <Form.Item
              label="系统提示词"
              name="systemPrompt"
              rules={[{ required: true, message: '请输入系统提示词' }]}
            >
              <TextArea 
                rows={8} 
                placeholder="请输入系统提示词，支持 Markdown 语法"
                style={{ fontFamily: 'monospace' }}
              />
            </Form.Item>

            <Form.Item
              label="对话模型"
              name="modelId"
              rules={[{ required: true, message: '请选择对话模型' }]}
            >
              <Select
                placeholder="请选择对话模型"
                allowClear
                options={models.map(model => ({
                  label: `${model.modelName} - ${model.providerName || '未知供应商'} ¥${model.price || 0}/M`,
                  value: model.id,
                }))}
              />
            </Form.Item>

            <Form.Item
              label="所有者"
              name="owner"
            >
              <Input 
                placeholder="自动填充当前登录用户"
                disabled
              />
            </Form.Item>

            {/* 是否公开 */}
            <Form.Item
              label="是否公开"
              extra={
                isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false)
                  ? '您没有权限修改此设置（已公开的实体不能改为非公开）'
                  : '公开后其他用户也可以查看此智能体'
              }
            >
              <Switch
                checked={isPublic}
                onChange={setIsPublic}
                checkedChildren="公开"
                unCheckedChildren="私有"
                disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false)}
              />
            </Form.Item>
          </>
        )}

        {currentStep === 1 && (
          <div>
            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ color: '#666', fontSize: '14px' }}>
                配置 MCP 服务（可选，可跳过）
              </span>
              <Button type="dashed" icon={<PlusOutlined />} onClick={addMcpConfig}>
                添加 MCP
              </Button>
            </div>

            {mcpConfigs.map((config, index) => (
              <Space key={index} style={{ width: '100%', marginBottom: 16, padding: 16, border: '1px solid #d9d9d9', borderRadius: '8px', background: '#fafafa' }} direction="vertical">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                  <span style={{ fontWeight: 500 }}>MCP #{index + 1}</span>
                  {mcpConfigs.length > 1 && (
                    <Button 
                      type="link" 
                      danger 
                      icon={<MinusOutlined />} 
                      onClick={() => removeMcpConfig(index)}
                      size="small"
                    >
                      删除
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
                  <span>允许跳过</span>
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
              <span style={{ color: '#666', fontSize: '14px' }}>
                配置技能（可选，可跳过）
              </span>
              <Button type="dashed" icon={<PlusOutlined />} onClick={addSkillConfig}>
                添加技能
              </Button>
            </div>

            {skillConfigs.map((config, index) => (
              <Space key={index} style={{ width: '100%', marginBottom: 16, padding: 16, border: '1px solid #d9d9d9', borderRadius: '8px', background: '#fafafa' }} direction="vertical">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                  <span style={{ fontWeight: 500 }}>技能 #{index + 1}</span>
                  {skillConfigs.length > 1 && (
                    <Button 
                      type="link" 
                      danger 
                      icon={<MinusOutlined />} 
                      onClick={() => removeSkillConfig(index)}
                      size="small"
                    >
                      删除
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

      <div style={{ marginTop: 24, display: 'flex', justifyContent: 'space-between' }}>
        <Button 
          disabled={currentStep === 0} 
          onClick={handlePrev}
        >
          上一步
        </Button>
        <Button 
          type="primary" 
          onClick={handleNext}
        >
          {currentStep === 2 ? '保存' : '下一步'}
        </Button>
      </div>
    </Modal>
  );
};

export default UpdateForm;
