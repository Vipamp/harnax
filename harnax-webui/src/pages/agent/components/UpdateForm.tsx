import { useIntl, useModel } from '@umijs/max';
import { Steps, Form, Input, Button, Select, Switch, Alert } from 'antd';
import React, { useState, useEffect } from 'react';
// @ts-ignore
import { getMcpServerList, getSkillRepositoryList, getSkillListByRepository, getModelList } from '@/services/ant-design-pro/agent';
import { getAvailableTools } from '@/services/ant-design-pro/tool';
import { getEnvVariableList } from '@/services/ant-design-pro/envVariable';
import { RocketOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import ToolConfigPanel, { ToolConfigState, EnvVarOption } from './ToolConfigPanel';
import McpConfigPanel, { McpConfigState } from './McpConfigPanel';
import SkillConfigPanel, { SkillConfigState } from './SkillConfigPanel';

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
  const locale = intl.locale;
  const [currentStep, setCurrentStep] = useState(0);
  const [form] = Form.useForm();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const [mcpServers, setMcpServers] = useState<API.McpServerItem[]>([]);
  const [repositories, setRepositories] = useState<API.SkillRepositoryItem[]>([]);
  const [skills, setSkills] = useState<API.SkillItem[]>([]);
  const [models, setModels] = useState<API.ModelItem[]>([]);
  const [isPublic, setIsPublic] = useState(values?.isPublic === 1);
  const { username, isAdmin } = getCurrentUserInfo();

  const [mcpConfigs, setMcpConfigs] = useState<McpConfigState[]>([{}]);
  const [skillConfigs, setSkillConfigs] = useState<SkillConfigState[]>([{}]);
  const [tools, setTools] = useState<any[]>([]);
  const [toolConfigs, setToolConfigs] = useState<ToolConfigState[]>([{}]);
  const [envVarOptions, setEnvVarOptions] = useState<EnvVarOption[]>([]);
  const [toolEnvCollapsed, setToolEnvCollapsed] = useState<Set<number>>(new Set());
  const [mcpEnvCollapsed, setMcpEnvCollapsed] = useState<Set<number>>(new Set());

  // Track selected model's capabilities (persistent across step navigation)
  const [selectedModelId, setSelectedModelId] = useState<number | undefined>();
  const selectedModel = models.find(m => m.id === selectedModelId);
  const modelSupportsTool = selectedModel?.supportTool === 1;
  const modelSupportsMcp = selectedModel?.supportMcp === 1;

  // Initialize form data from values
  useEffect(() => {
    if (visible && values) {
      form.setFieldsValue({
        name: values.name,
        description: values.description,
        systemPrompt: values.systemPrompt,
        owner: currentUser?.username || currentUser?.nickname || values.owner,
        modelId: values.modelId,
      });
      setSelectedModelId(values.modelId);
      setIsPublic(values.isPublic === 1);

      // Initialize MCP configs
      if (values.mcpList && values.mcpList.length > 0) {
        setMcpConfigs(values.mcpList.map(item => ({
          mcpId: item.mcpId,
          mcpName: item.mcpName,
          enableSkip: item.enableSkip === 'true',
          envBindings: (item.envBindings || []).map((b: any) => ({
            envKey: b.envKey,
            envValue: b.customValue || b.envValue || '',
            envVarId: b.envVarId,
            customInput: !!b.customValue || (!b.envVarId && !!b.envValue),
          })),
        })));
      } else {
        setMcpConfigs([{}]);
      }

      // Initialize skill configs
      if (values.skillList && values.skillList.length > 0) {
        const initialConfigs = values.skillList.map(item => ({
          repositoryId: item.repositoryId,
          repositoryName: item.repositoryName,
          skillId: item.skillId,
          skillName: item.skillName,
          value: item.skillId,
          label: item.skillName || `技能-${item.skillId}`,
        }));
        setSkillConfigs(initialConfigs);

        const uniqueRepoIds = Array.from(new Set(values.skillList.map(item => item.repositoryId).filter(Boolean)));
        uniqueRepoIds.forEach(repoId => { loadSkills(repoId!); });
      } else {
        setSkillConfigs([{}]);
      }

      // Initialize tool configs
      if (values.toolList && values.toolList.length > 0) {
        setToolConfigs(values.toolList.map((item: any) => ({
          toolId: item.toolId,
          toolName: item.toolName,
          enableSkip: item.enableSkip === 'true',
          needConfirm: item.needConfirm || false,
          entityNeedConfirm: item.needConfirm ? 1 : 0,
          envBindings: (item.envBindings || []).map((b: any) => ({
            envKey: b.envKey,
            envValue: b.customValue || b.envValue || '',
            envVarId: b.envVarId,
            customInput: !!b.customValue || (!b.envVarId && !!b.envValue),
          })),
        })));
      } else {
        setToolConfigs([{}]);
      }

      loadMcpServers();
      loadRepositories();
      loadModels();
      loadTools();
      loadEnvVarOptions();
    }
  }, [visible, values]);

  // Re-initialize envEntries from tool entity once tools are loaded
  useEffect(() => {
    if (tools.length === 0 || toolConfigs.every(c => !c.toolId)) return;
    const updated = toolConfigs.map(config => {
      if (!config.toolId) return config;
      const tool = tools.find((t: any) => t.id === config.toolId);
      if (!tool) return config;
      let changed = false;
      const newConfig = { ...config };
      if (tool.needConfirm !== config.entityNeedConfirm) {
        newConfig.entityNeedConfirm = tool.needConfirm;
        changed = true;
      }
      if (!config.envEntries && tool.envParams && tool.envParams.length > 0) {
        newConfig.envEntries = tool.envParams;
        changed = true;
      }
      return changed ? newConfig : config;
    });
    const changed = updated.some((c, i) =>
      c.entityNeedConfirm !== toolConfigs[i].entityNeedConfirm ||
      c.envEntries !== toolConfigs[i].envEntries
    );
    if (changed) { setToolConfigs(updated); }
  }, [tools]);

  // Enrich MCP configs with envEntries from mcpServers once loaded
  useEffect(() => {
    if (mcpServers.length === 0 || mcpConfigs.every(c => !c.mcpId)) return;
    const updated = mcpConfigs.map(config => {
      if (!config.mcpId || config.envEntries) return config;
      const mcp = mcpServers.find(m => m.id === config.mcpId);
      if (mcp && mcp.envParams && mcp.envParams.length > 0) {
        return { ...config, envEntries: mcp.envParams };
      }
      return config;
    });
    const changed = updated.some((c, i) => c.envEntries !== mcpConfigs[i].envEntries);
    if (changed) { setMcpConfigs(updated); }
  }, [mcpServers]);

  const loadMcpServers = async () => {
    try {
      const res = await getMcpServerList({ pageNum: 1, pageSize: 100, status: 1 });
      setMcpServers(res.data?.records || []);
    } catch (error) { console.error('加载 MCP 服务器列表失败', error); }
  };

  const loadRepositories = async () => {
    try {
      const res = await getSkillRepositoryList({ pageNum: 1, pageSize: 100, status: 1 });
      setRepositories(res.data?.records || []);
    } catch (error) { console.error('加载技能仓库列表失败', error); }
  };

  const loadSkills = async (repositoryId: number) => {
    try {
      const res = await getSkillListByRepository(repositoryId, { pageNum: 1, pageSize: 100, status: 1 });
      setSkills(res.data?.records || []);
    } catch (error) { console.error('加载技能列表失败', error); setSkills([]); }
  };

  const loadModels = async () => {
    try {
      const res = await getModelList({ pageNum: 1, pageSize: 100 });
      const enabledModels = (res.data?.records || []).filter((item: any) => item.status === 1 && item.modelType === 'chat');
      setModels(enabledModels);
    } catch (error) { console.error('加载模型列表失败', error); }
  };

  const loadTools = async () => {
    try {
      const response = await getAvailableTools();
      if (response?.code === 200 && response?.data) { setTools(response.data); }
    } catch (error) { console.error('Failed to load tools', error); }
  };

  const loadEnvVarOptions = async () => {
    try {
      const res = await getEnvVariableList();
      if (res?.code === 200 && res?.data) {
        setEnvVarOptions(res.data.map((item: any) => ({ id: item.id, envKey: item.envKey || '', displayValue: item.displayValue || '', sensitive: item.sensitive || false })));
      }
    } catch (error) { console.error('Failed to load env parameters', error); }
  };


  const handleNext = async () => {
    try {
      if (currentStep === 0) {
        await form.validateFields(['name', 'description', 'systemPrompt']);
      } else if (currentStep === 3) {
        const formValues = await form.validateFields(['name', 'description', 'systemPrompt', 'modelId', 'owner']);

        const submitData: API.AgentUpdateRequest = {
          name: formValues.name,
          description: formValues.description,
          systemPrompt: formValues.systemPrompt,
          modelId: formValues.modelId,
          owner: formValues.owner,
          status: 1,
          isPublic: isPublic ? 1 : 0,
          mcpList: mcpConfigs.filter(c => c.mcpId).map(c => ({
            id: c.mcpId,
            enableSkip: c.enableSkip ? 'true' : 'false',
            envBindings: (c.envBindings || []).map(({ customInput, ...b }) => ({
              envKey: b.envKey,
              ...(customInput ? { customValue: b.envValue } : { envValue: b.envValue }),
              ...(b.envVarId && !customInput ? { envVarId: b.envVarId } : {}),
            })),
          })),
          skillList: skillConfigs.filter(c => c.skillId).map(c => c.skillId!.toString()).join(','),
          toolList: toolConfigs.filter(c => c.toolId).map(c => ({
            id: c.toolId,
            enableSkip: c.enableSkip ? 'true' : 'false',
            needConfirm: c.needConfirm || false,
            envBindings: (c.envBindings || []).map(({ customInput, ...b }) => ({
              envKey: b.envKey,
              ...(customInput ? { customValue: b.envValue } : { envValue: b.envValue }),
              ...(b.envVarId && !customInput ? { envVarId: b.envVarId } : {}),
            })),
          })),
        };
        await onSubmit(submitData);
        form.resetFields();
        setCurrentStep(0);
        setMcpConfigs([{}]);
        setSkillConfigs([{}]);
        setToolConfigs([{}]);
        return;
      }
      setCurrentStep(currentStep + 1);
    } catch (error) { console.error('验证失败', error); }
  };

  const handlePrev = () => setCurrentStep(currentStep - 1);

  const handleClose = () => {
    form.resetFields();
    setCurrentStep(0);
    setSelectedModelId(undefined);
    setMcpConfigs([{}]);
    setSkillConfigs([{}]);
    setToolConfigs([{}]);
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
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(79, 110, 247, 0.25)',
      }}
    >
      <Steps current={currentStep} size="small" style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid var(--vip-border)' }}>
        <Step title={intl.formatMessage({ id: 'pages.agent.basicInfo', defaultMessage: 'Basic Info' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.toolConfig', defaultMessage: 'Tool Config' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.mcpConfig', defaultMessage: 'MCP Config' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.skillConfig', defaultMessage: 'Skill Config' })} />
      </Steps>

      <div style={{ maxHeight: 'calc(100vh - 320px)', overflowY: 'auto', paddingRight: 4 }}>
      <Form form={form} layout="horizontal" labelCol={{ span: 6 }} wrapperCol={{ span: 18 }} style={{ marginTop: 16 }}>
        {currentStep === 0 && (
          <>
            <Form.Item label={intl.formatMessage({ id: 'pages.agent.name', defaultMessage: 'Agent Name' })} name="name" rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.nameRequired', defaultMessage: 'Please enter agent name' }) }]}>
              <Input placeholder={intl.formatMessage({ id: 'pages.agent.namePlaceholder', defaultMessage: 'Please enter agent name' })} />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'pages.agent.description', defaultMessage: 'Description' })} name="description" rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.descriptionRequired', defaultMessage: 'Please enter description' }) }]}>
              <TextArea rows={3} placeholder={intl.formatMessage({ id: 'pages.agent.descriptionPlaceholder', defaultMessage: 'Please enter description' })} />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'pages.agent.systemPrompt', defaultMessage: 'System Prompt' })} name="systemPrompt" rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.systemPromptRequired', defaultMessage: 'Please enter system prompt' }) }]}>
              <TextArea rows={8} placeholder={intl.formatMessage({ id: 'pages.agent.systemPromptPlaceholder', defaultMessage: 'Please enter system prompt, supports Markdown syntax' })} />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'pages.agent.model', defaultMessage: 'Model' })} name="modelId" rules={[{ required: true, message: intl.formatMessage({ id: 'pages.agent.modelRequired', defaultMessage: 'Please select model' }) }]}>
              <Select
                placeholder={intl.formatMessage({ id: 'pages.agent.modelPlaceholder', defaultMessage: 'Please select model' })}
                allowClear
                onChange={(val) => setSelectedModelId(val)}
                options={models.map(model => ({ label: `${model.modelName} - ${model.providerName || intl.formatMessage({ id: 'pages.common.unknownProvider', defaultMessage: 'Unknown provider' })} ¥${model.price || 0}/M`, value: model.id }))}
              />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'pages.agent.owner', defaultMessage: 'Owner' })} name="owner">
              <Input placeholder={intl.formatMessage({ id: 'pages.agent.ownerPlaceholder', defaultMessage: 'Auto-filled with current user' })} disabled />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'pages.agent.isPublic', defaultMessage: 'Is Public' })}
              extra={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false) ? intl.formatMessage({ id: 'pages.agent.noPermission', defaultMessage: 'You do not have permission to modify this setting' }) : intl.formatMessage({ id: 'pages.agent.publicHint', defaultMessage: 'Other users can view this agent after making it public' })}>
              <Switch checked={isPublic} onChange={setIsPublic} checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })} unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })} disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false)} />
            </Form.Item>
          </>
        )}

        {currentStep === 1 && (
          <>
            {selectedModel && !modelSupportsTool && (
              <Alert
                type="warning"
                showIcon
                message={intl.formatMessage({ id: 'pages.agent.modelNotSupportTool', defaultMessage: 'Current model does not support tool calling. Switch to a supported model or skip this step' })}
                style={{ marginBottom: 16 }}
              />
            )}
            <div style={{ opacity: modelSupportsTool || !selectedModel ? 1 : 0.4, pointerEvents: modelSupportsTool || !selectedModel ? 'auto' : 'none' }}>
              <ToolConfigPanel
                toolConfigs={toolConfigs}
                setToolConfigs={setToolConfigs}
                tools={tools}
                envVarOptions={envVarOptions}
                collapsed={toolEnvCollapsed}
                setCollapsed={setToolEnvCollapsed}
                locale={locale}
              />
            </div>
          </>
        )}

        {currentStep === 2 && (
          <>
            {selectedModel && !modelSupportsMcp && (
              <Alert
                type="warning"
                showIcon
                message={intl.formatMessage({ id: 'pages.agent.modelNotSupportMcp', defaultMessage: 'Current model does not support MCP services. Switch to a supported model or skip this step' })}
                style={{ marginBottom: 16 }}
              />
            )}
            <div style={{ opacity: modelSupportsMcp || !selectedModel ? 1 : 0.4, pointerEvents: modelSupportsMcp || !selectedModel ? 'auto' : 'none' }}>
              <McpConfigPanel
                mcpConfigs={mcpConfigs}
                setMcpConfigs={setMcpConfigs}
                mcpServers={mcpServers}
                envVarOptions={envVarOptions}
                collapsed={mcpEnvCollapsed}
                setCollapsed={setMcpEnvCollapsed}
              />
            </div>
          </>
        )}

        {currentStep === 3 && (
          <SkillConfigPanel
            skillConfigs={skillConfigs}
            setSkillConfigs={setSkillConfigs}
            repositories={repositories}
            skills={skills}
            onLoadSkills={loadSkills}
          />
        )}
      </Form>
      </div>

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--vip-border)' }}>
        <Button disabled={currentStep === 0} onClick={handlePrev}>
          {intl.formatMessage({ id: 'pages.common.previous', defaultMessage: 'Previous' })}
        </Button>
        <Button type="primary" onClick={handleNext}>
          {currentStep === 3 ? intl.formatMessage({ id: 'pages.common.save', defaultMessage: 'Save' }) : intl.formatMessage({ id: 'pages.agent.nextStep', defaultMessage: 'Next' })}
        </Button>
      </div>
    </FormModal>
  );
};

export default UpdateForm;
