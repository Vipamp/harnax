// pages/agent/form/index.ts
import {
  getAgentById,
  createAgent,
  updateAgent,
  getModelOptions,
  getMcpOptions,
  getToolOptions,
  getSkillRepoOptions,
  getSkillsByRepo,
  getEnvVarOptions,
} from '../../../services/agent';

interface ToolOption {
  id: number;
  name: string;
  label: string;
  type?: string;
  envParams: API.ToolEnvParamEntry[];
}

interface EnvBindingVM {
  envKey: string;
  required: boolean;
  secret: boolean;
  customInput: boolean;
  envVarId?: number;
  envValue: string;
  sourceIndex: number; // envVarPickerRange 中的下标，-1 表示未选
}

interface ToolConfigVM {
  _id: number;
  toolId?: number;
  label: string;
  pickerIndex: number;
  needConfirm: boolean;
  envBindings: EnvBindingVM[];
}

interface McpConfigVM {
  _id: number;
  mcpId?: number;
  label: string;
  pickerIndex: number;
  enableSkip: boolean;
  envBindings: EnvBindingVM[];
}

interface SkillConfigVM {
  _id: number;
  repositoryId?: number;
  repoLabel: string;
  repoPickerIndex: number;
  skillId?: number;
  skillLabel: string;
  skillPickerIndex: number;
  skillRange: string[];
  skills: API.SkillItem[];
}

/** 依据环境参数定义 + 已保存绑定，构造环境变量绑定视图模型 */
function buildEnvBindings(
  entries: API.ToolEnvParamEntry[],
  envVarOptions: API.EnvVarOption[],
  saved?: API.EnvBinding[],
): EnvBindingVM[] {
  const customIndex = envVarOptions.length;
  return (entries || []).map((e) => {
    const base = {
      envKey: e.envParamName,
      required: !!e.required,
      secret: !!e.secret,
    };
    const savedB = (saved || []).find((s) => s.envKey === e.envParamName);
    if (savedB && savedB.customValue != null && savedB.customValue !== '' && !savedB.envVarId) {
      return { ...base, customInput: true, envVarId: undefined, envValue: savedB.customValue || '', sourceIndex: customIndex };
    }
    if (savedB && savedB.envVarId) {
      const idx = envVarOptions.findIndex((o) => o.id === savedB.envVarId);
      return {
        ...base,
        customInput: false,
        envVarId: savedB.envVarId,
        envValue: savedB.envValue || (idx >= 0 ? envVarOptions[idx].displayValue : ''),
        sourceIndex: idx,
      };
    }
    return { ...base, customInput: false, envVarId: undefined, envValue: e.defaultValue || '', sourceIndex: -1 };
  });
}

/** 视图模型 -> 后端 EnvBinding */
function toEnvBindingPayload(b: EnvBindingVM): API.EnvBinding {
  if (b.customInput) return { envKey: b.envKey, customValue: b.envValue };
  const p: API.EnvBinding = { envKey: b.envKey, envValue: b.envValue };
  if (b.envVarId) p.envVarId = b.envVarId;
  return p;
}

let _uid = 0;
const nextId = () => ++_uid;

Page({
  data: {
    id: 0,
    isEdit: false,
    loading: false,
    submitting: false,
    form: {
      name: '',
      description: '',
      systemPrompt: '',
      modelId: undefined as number | undefined,
      status: 1,
      isPublic: 0,
    },
    models: [] as API.ModelItem[],
    modelNames: [] as string[],
    modelIndex: -1,
    // 选项源
    toolOptions: [] as ToolOption[],
    toolRange: [] as string[],
    mcpOptions: [] as API.McpItem[],
    mcpRange: [] as string[],
    repoOptions: [] as API.SkillRepositoryItem[],
    repoRange: [] as string[],
    envVarOptions: [] as API.EnvVarOption[],
    envVarRange: [] as string[], // [...环境变量名, '自定义输入']
    // 配置卡片
    toolConfigs: [] as ToolConfigVM[],
    mcpConfigs: [] as McpConfigVM[],
    skillConfigs: [] as SkillConfigVM[],
  },

  onLoad(query: Record<string, string>) {
    const id = Number(query.id) || 0;
    this.setData({ id, isEdit: !!id });
    wx.setNavigationBarTitle({ title: id ? '编辑 Agent' : '创建 Agent' });
    this.init();
  },

  async init() {
    this.setData({ loading: true });
    try {
      const [modelRes, mcpRes, toolRes, repoRes, envVarRes] = await Promise.all([
        getModelOptions(),
        getMcpOptions(),
        getToolOptions().catch(() => [] as any[]),
        getSkillRepoOptions().catch(() => ({ records: [] } as any)),
        getEnvVarOptions().catch(() => [] as any[]),
      ]);
      const models = modelRes.records || [];
      const toolOptions: ToolOption[] = (toolRes || []).map((t: any) => ({
        id: t.id,
        name: t.name,
        label: t.displayNameZh || t.displayName || t.name,
        type: t.type,
        envParams: (t.envParams || []) as API.ToolEnvParamEntry[],
      }));
      const mcpOptions = (mcpRes.records || []) as API.McpItem[];
      const repoOptions = ((repoRes as any).records || []) as API.SkillRepositoryItem[];
      const envVarOptions = (envVarRes || []) as API.EnvVarOption[];
      this.setData({
        models,
        modelNames: models.map((m) => m.name),
        toolOptions,
        toolRange: toolOptions.map((t) => t.label),
        mcpOptions,
        mcpRange: mcpOptions.map((m) => m.name),
        repoOptions,
        repoRange: repoOptions.map((r) => r.name),
        envVarOptions,
        envVarRange: [...envVarOptions.map((o) => o.envKey), '自定义输入'],
      });
      if (this.data.isEdit) {
        await this.loadDetail();
      }
    } catch (e) {
      // ignore
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadDetail() {
    const detail = await getAgentById(this.data.id);
    const { toolOptions, mcpOptions, repoOptions, envVarOptions } = this.data;
    const modelIndex = this.data.models.findIndex((m) => m.id === detail.modelId);

    // 工具配置回填
    const toolConfigs: ToolConfigVM[] = (detail.toolList || []).map((t) => {
      const optIdx = toolOptions.findIndex((o) => o.id === t.toolId);
      const opt = toolOptions[optIdx];
      const entries = opt?.envParams || [];
      return {
        _id: nextId(),
        toolId: t.toolId,
        label: opt?.label || t.toolDisplayNameZh || t.toolDisplayName || t.toolName || '未知工具',
        pickerIndex: optIdx,
        needConfirm: !!t.needConfirm,
        envBindings: buildEnvBindings(entries, envVarOptions, t.envBindings),
      };
    });

    // MCP 配置回填
    const mcpConfigs: McpConfigVM[] = (detail.mcpList || []).map((m) => {
      const optIdx = mcpOptions.findIndex((o) => o.id === m.mcpId);
      const opt = mcpOptions[optIdx];
      const entries = opt?.envParams || [];
      return {
        _id: nextId(),
        mcpId: m.mcpId,
        label: opt?.name || m.mcpName || '未知 MCP',
        pickerIndex: optIdx,
        enableSkip: m.enableSkip === 'true',
        envBindings: buildEnvBindings(entries, envVarOptions, m.envBindings),
      };
    });

    // 技能配置回填（需按仓库加载技能列表）
    const skillConfigs: SkillConfigVM[] = [];
    for (const s of detail.skillList || []) {
      const repoIdx = repoOptions.findIndex((r) => r.id === s.repositoryId);
      let skills: API.SkillItem[] = [];
      if (s.repositoryId) {
        try {
          const res = await getSkillsByRepo(s.repositoryId);
          skills = res.records || [];
        } catch (e) {
          skills = [];
        }
      }
      const skillIdx = skills.findIndex((sk) => sk.id === s.skillId);
      skillConfigs.push({
        _id: nextId(),
        repositoryId: s.repositoryId,
        repoLabel: repoOptions[repoIdx]?.name || s.repositoryName || '',
        repoPickerIndex: repoIdx,
        skillId: s.skillId,
        skillLabel: skills[skillIdx]?.name || s.skillName || '',
        skillPickerIndex: skillIdx,
        skillRange: skills.map((sk) => sk.name),
        skills,
      });
    }

    this.setData({
      form: {
        name: detail.name || '',
        description: detail.description || '',
        systemPrompt: detail.systemPrompt || '',
        modelId: detail.modelId,
        status: detail.status,
        isPublic: (detail as any).isPublic === 1 ? 1 : 0,
      },
      modelIndex,
      toolConfigs,
      mcpConfigs,
      skillConfigs,
    });
  },

  // ---- 基本信息 ----
  onInput(e: any) {
    const field = e.currentTarget.dataset.field as string;
    this.setData({ [`form.${field}`]: e.detail.value });
  },

  onModelChange(e: any) {
    const idx = Number(e.detail.value);
    this.setData({ modelIndex: idx, 'form.modelId': this.data.models[idx]?.id });
  },

  onStatusChange(e: any) {
    this.setData({ 'form.status': e.detail.value ? 1 : 0 });
  },

  onIsPublicChange(e: any) {
    this.setData({ 'form.isPublic': e.detail.value ? 1 : 0 });
  },

  // ---- 工具配置 ----
  onAddTool() {
    const toolConfigs = this.data.toolConfigs.concat([
      { _id: nextId(), label: '', pickerIndex: -1, needConfirm: false, envBindings: [] },
    ]);
    this.setData({ toolConfigs });
  },

  onRemoveTool(e: any) {
    const idx = Number(e.currentTarget.dataset.index);
    this.setData({ toolConfigs: this.data.toolConfigs.filter((_, i) => i !== idx) });
  },

  onToolPick(e: any) {
    const cardIdx = Number(e.currentTarget.dataset.index);
    const optIdx = Number(e.detail.value);
    const tool = this.data.toolOptions[optIdx];
    if (!tool) return;
    const configs = [...this.data.toolConfigs];
    const prev = configs[cardIdx];
    configs[cardIdx] = {
      ...prev,
      toolId: tool.id,
      label: tool.label,
      pickerIndex: optIdx,
      needConfirm: false,
      envBindings: buildEnvBindings(tool.envParams, this.data.envVarOptions),
    };
    this.setData({ toolConfigs: configs });
  },

  onToolNeedConfirm(e: any) {
    const idx = Number(e.currentTarget.dataset.index);
    this.setData({ [`toolConfigs[${idx}].needConfirm`]: !!e.detail.value });
  },

  // ---- MCP 配置 ----
  onAddMcp() {
    const mcpConfigs = this.data.mcpConfigs.concat([
      { _id: nextId(), label: '', pickerIndex: -1, enableSkip: false, envBindings: [] },
    ]);
    this.setData({ mcpConfigs });
  },

  onRemoveMcp(e: any) {
    const idx = Number(e.currentTarget.dataset.index);
    this.setData({ mcpConfigs: this.data.mcpConfigs.filter((_, i) => i !== idx) });
  },

  onMcpPick(e: any) {
    const cardIdx = Number(e.currentTarget.dataset.index);
    const optIdx = Number(e.detail.value);
    const mcp = this.data.mcpOptions[optIdx];
    if (!mcp) return;
    const configs = [...this.data.mcpConfigs];
    configs[cardIdx] = {
      ...configs[cardIdx],
      mcpId: mcp.id,
      label: mcp.name,
      pickerIndex: optIdx,
      envBindings: buildEnvBindings(mcp.envParams || [], this.data.envVarOptions),
    };
    this.setData({ mcpConfigs: configs });
  },

  onMcpEnableSkip(e: any) {
    const idx = Number(e.currentTarget.dataset.index);
    this.setData({ [`mcpConfigs[${idx}].enableSkip`]: !!e.detail.value });
  },

  // ---- 环境变量绑定（工具/MCP 共用，data-type 区分）----
  onEnvSourceChange(e: any) {
    const { type, index, envIndex } = e.currentTarget.dataset as { type: string; index: number; envIndex: number };
    const i = Number(e.detail.value);
    const { envVarOptions } = this.data;
    const listKey = type === 'tool' ? 'toolConfigs' : 'mcpConfigs';
    const configs = [...(this.data as any)[listKey]] as (ToolConfigVM | McpConfigVM)[];
    const bindings = [...configs[index].envBindings];
    if (i === envVarOptions.length) {
      bindings[envIndex] = { ...bindings[envIndex], customInput: true, envVarId: undefined, envValue: '', sourceIndex: i };
    } else {
      const opt = envVarOptions[i];
      bindings[envIndex] = { ...bindings[envIndex], customInput: false, envVarId: opt.id, envValue: opt.displayValue, sourceIndex: i };
    }
    configs[index].envBindings = bindings;
    this.setData({ [listKey]: configs });
  },

  onEnvCustomInput(e: any) {
    const { type, index, envIndex } = e.currentTarget.dataset as { type: string; index: number; envIndex: number };
    const listKey = type === 'tool' ? 'toolConfigs' : 'mcpConfigs';
    this.setData({ [`${listKey}[${index}].envBindings[${envIndex}].envValue`]: e.detail.value });
  },

  // ---- 技能配置 ----
  onAddSkill() {
    const skillConfigs = this.data.skillConfigs.concat([
      { _id: nextId(), repoLabel: '', repoPickerIndex: -1, skillLabel: '', skillPickerIndex: -1, skillRange: [], skills: [] },
    ]);
    this.setData({ skillConfigs });
  },

  onRemoveSkill(e: any) {
    const idx = Number(e.currentTarget.dataset.index);
    this.setData({ skillConfigs: this.data.skillConfigs.filter((_, i) => i !== idx) });
  },

  async onSkillRepoPick(e: any) {
    const cardIdx = Number(e.currentTarget.dataset.index);
    const optIdx = Number(e.detail.value);
    const repo = this.data.repoOptions[optIdx];
    if (!repo) return;
    let skills: API.SkillItem[] = [];
    try {
      const res = await getSkillsByRepo(repo.id);
      skills = res.records || [];
    } catch (err) {
      skills = [];
    }
    const configs = [...this.data.skillConfigs];
    configs[cardIdx] = {
      ...configs[cardIdx],
      repositoryId: repo.id,
      repoLabel: repo.name,
      repoPickerIndex: optIdx,
      skillId: undefined,
      skillLabel: '',
      skillPickerIndex: -1,
      skillRange: skills.map((s) => s.name),
      skills,
    };
    this.setData({ skillConfigs: configs });
  },

  onSkillPick(e: any) {
    const cardIdx = Number(e.currentTarget.dataset.index);
    const optIdx = Number(e.detail.value);
    const config = this.data.skillConfigs[cardIdx];
    const skill = config.skills[optIdx];
    if (!skill) return;
    const configs = [...this.data.skillConfigs];
    configs[cardIdx] = { ...config, skillId: skill.id, skillLabel: skill.name, skillPickerIndex: optIdx };
    this.setData({ skillConfigs: configs });
  },

  // ---- 提交 ----
  buildPayload(): API.AgentCreateRequest {
    const { form, toolConfigs, mcpConfigs, skillConfigs } = this.data;
    return {
      name: form.name.trim(),
      description: form.description,
      systemPrompt: form.systemPrompt,
      modelId: form.modelId,
      status: form.status,
      isPublic: form.isPublic,
      toolList: toolConfigs
        .filter((c) => c.toolId)
        .map((c) => ({
          id: c.toolId as number,
          needConfirm: c.needConfirm || false,
          envBindings: c.envBindings.map(toEnvBindingPayload),
        })),
      mcpList: mcpConfigs
        .filter((c) => c.mcpId)
        .map((c) => ({
          id: c.mcpId as number,
          enableSkip: c.enableSkip ? 'true' : 'false',
          envBindings: c.envBindings.map(toEnvBindingPayload),
        })),
      skillList: skillConfigs
        .filter((c) => c.skillId)
        .map((c) => c.skillId)
        .join(','),
    };
  },

  async onSubmit() {
    const { form } = this.data;
    if (!form.name.trim()) {
      wx.showToast({ title: '请输入名称', icon: 'none' });
      return;
    }
    if (!form.modelId) {
      wx.showToast({ title: '请选择模型', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    const payload = this.buildPayload();
    try {
      if (this.data.isEdit) {
        await updateAgent(this.data.id, { ...payload, id: this.data.id });
      } else {
        await createAgent(payload);
      }
      wx.showToast({ title: '保存成功', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 500);
    } catch (e) {
      // ignore
    } finally {
      this.setData({ submitting: false });
    }
  },
});
