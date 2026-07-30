import { useIntl, useModel } from '@umijs/max';
import { Steps, Form, Input, Button, message, Select, Switch, Alert } from 'antd';
import React, { useState, useEffect } from 'react';
// @ts-ignore
import { getMcpServerList, getSkillRepositoryList, getSkillListByRepository, getModelList } from '@/services/ant-design-pro/agent';
import { getAvailableTools } from '@/services/ant-design-pro/tool';
import { getEnvVariableList } from '@/services/ant-design-pro/envVariable';
import { getCliPage } from '@/services/ant-design-pro/cli';
import { RocketOutlined } from '@ant-design/icons';
import { getCurrentUserInfo } from '@/utils/permissionUtil';
import { FormModal } from '@/components/FormModal';
import { BUILTIN_CLI_SKILL_REPO } from '@/constants/builtinRepository';
import ToolConfigPanel, { ToolConfigState, EnvVarOption } from './ToolConfigPanel';
import McpConfigPanel, { McpConfigState } from './McpConfigPanel';
import SkillConfigPanel, { SkillConfigState } from './SkillConfigPanel';
import CliConfigPanel from './CliConfigPanel';

const { TextArea } = Input;
const { Step } = Steps;

interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: API.AgentCreateRequest) => Promise<void>;
}

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit }) => {
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
  const [isPublic, setIsPublic] = useState(false);
  const { isAdmin } = getCurrentUserInfo();

  const [mcpConfigs, setMcpConfigs] = useState<McpConfigState[]>([{}]);
  const [skillConfigs, setSkillConfigs] = useState<SkillConfigState[]>([{}]);
  const [tools, setTools] = useState<any[]>([]);
  const [toolConfigs, setToolConfigs] = useState<ToolConfigState[]>([{}]);
  const [clis, setClis] = useState<API.CliItem[]>([]);
  const [selectedCliIds, setSelectedCliIds] = useState<number[]>([]);
  const [envVarOptions, setEnvVarOptions] = useState<EnvVarOption[]>([]);
  const [toolEnvCollapsed, setToolEnvCollapsed] = useState<Set<number>>(new Set());
  const [mcpEnvCollapsed, setMcpEnvCollapsed] = useState<Set<number>>(new Set());

  // Track selected model's capabilities (persistent across step navigation)
  const [selectedModelId, setSelectedModelId] = useState<number | undefined>();
  const selectedModel = models.find(m => m.id === selectedModelId);
  const modelSupportsTool = selectedModel?.supportTool === 1;
  const modelSupportsMcp = selectedModel?.supportMcp === 1;

  useEffect(() => {
    if (visible && currentUser) {
      form.setFieldsValue({ owner: currentUser.username || currentUser.nickname });
    }
  }, [visible, currentUser]);

  useEffect(() => {
    if (visible) {
      loadMcpServers();
      loadRepositories();
      loadModels();
      loadTools();
      loadEnvVarOptions();
      loadClis();
    }
  }, [visible]);

  const loadClis = async () => {
    try {
      const res = await getCliPage({ pageNum: 1, pageSize: 100, status: 1 });
      setClis(res.data?.records || []);
    } catch (error) { console.error('加载 CLI 列表失败', error); }
  };

  const loadMcpServers = async () => {
    try {
      const res = await getMcpServerList({ pageNum: 1, pageSize: 100, status: 1 });
      setMcpServers(res.data?.records || []);
    } catch (error) { console.error('加载 MCP 服务器列表失败', error); }
  };

  const loadRepositories = async () => {
    try {
      const res = await getSkillRepositoryList({ pageNum: 1, pageSize: 100, status: 1 });
      // 内置 CLI 仓库的技能只能通过 CLI 关联，不允许 agent 直接绑定
      setRepositories((res.data?.records || []).filter((repo: any) => repo.name !== BUILTIN_CLI_SKILL_REPO));
    } catch (error) { console.error('加载技能仓库列表失败', error); }
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

  const loadSkills = async (repositoryId: number) => {
    try {
      const res = await getSkillListByRepository(repositoryId, { pageNum: 1, pageSize: 100, status: 1 });
      setSkills(res.data?.records || []);
    } catch (error) { console.error('加载技能列表失败', error); setSkills([]); }
  };


  const handleNext = async () => {
    try {
      if (currentStep === 0) {
        await form.validateFields(['name', 'description', 'systemPrompt']);
      } else if (currentStep === 4) {
        const { name, description, systemPrompt, modelId, owner } = await form.validateFields([
          'name', 'description', 'systemPrompt', 'modelId', 'owner',
        ]);

        const submitData: API.AgentCreateRequest = {
          name, description, systemPrompt, modelId, owner,
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
          skillList: skillConfigs.filter(c => c.skillId).map(c => c.skillId).join(','),
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
          cliList: selectedCliIds.map(id => ({ id })),
        };
        await onSubmit(submitData);
        form.resetFields();
        setCurrentStep(0);
        setMcpConfigs([{}]);
        setSkillConfigs([{}]);
        setToolConfigs([{}]);
        setSelectedCliIds([]);
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
    setSelectedCliIds([]);
    onCancel();
  };

  return (
    <FormModal
      open={visible}
      onCancel={handleClose}
      size="xl"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.agent.create', defaultMessage: 'Create Agent' }),
        subtitle: intl.formatMessage({ id: 'pages.agent.create.subtitle', defaultMessage: 'Configure agent basic info, MCP services and skills' }),
        icon: <RocketOutlined />,
      }}
    >
      <Steps current={currentStep} size="small" style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid var(--vip-border)' }}>
        <Step title={intl.formatMessage({ id: 'pages.agent.basicInfo', defaultMessage: 'Basic Info' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.toolConfig', defaultMessage: 'Tool Config' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.mcpConfig', defaultMessage: 'MCP Config' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.skillConfig', defaultMessage: 'Skill Config' })} />
        <Step title={intl.formatMessage({ id: 'pages.agent.cliConfig', defaultMessage: 'CLI Config' })} />
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
            <Form.Item label={intl.formatMessage({ id: 'pages.agent.isPublic', defaultMessage: 'Is Public' })}>
              <Switch checked={isPublic} onChange={setIsPublic} checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })} unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })} />
              <div style={{ marginTop: 4, color: 'var(--vip-text-secondary)', fontSize: '14px' }}>
                {intl.formatMessage({ id: 'pages.agent.publicHint', defaultMessage: 'Other users can view this agent after making it public' })}
              </div>
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

        {currentStep === 4 && (
          <CliConfigPanel
            selectedCliIds={selectedCliIds}
            setSelectedCliIds={setSelectedCliIds}
            clis={clis}
          />
        )}
      </Form>
      </div>

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--vip-border)' }}>
        <Button disabled={currentStep === 0} onClick={handlePrev}>
          {intl.formatMessage({ id: 'pages.common.previous', defaultMessage: 'Previous' })}
        </Button>
        <Button type="primary" onClick={handleNext}>
          {currentStep === 4 ? intl.formatMessage({ id: 'pages.agent.finish', defaultMessage: 'Finish' }) : intl.formatMessage({ id: 'pages.agent.nextStep', defaultMessage: 'Next' })}
        </Button>
      </div>
    </FormModal>
  );
};

export default CreateForm;
