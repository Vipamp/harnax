/* eslint-disable */
// Harnax API 数据模型类型（对齐 harnax-webui typings.d.ts）

declare namespace API {
  // ---- 通用响应 ----
  type Result<T = any> = {
    code: number;
    message?: string;
    data?: T;
  };

  type PageResult<T = any> = {
    records: T[];
    total: number;
    size: number;
    current: number;
    pages: number;
  };

  // ---- 认证 ----
  type LoginParams = {
    username: string;
    password: string;
    autoLogin?: boolean;
    type?: string;
  };

  type TokenInfo = {
    accessToken: string;
    refreshToken?: string;
    routerApiKey?: string;
    routerUrl?: string;
    tokenType?: string;
    expiresIn?: number;
  };

  type CurrentUser = {
    id?: number;
    username?: string;
    nickname?: string;
    avatar?: string;
    email?: string;
    roles?: string[];
  };

  // ---- Agent ----
  type AgentItem = {
    id: number;
    name: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    modelName?: string;
    status: number;
    mcpList?: AgentMcpConfig[];
    toolList?: AgentToolConfig[];
    skillList?: AgentSkillItem[];
    mcpCount?: number;
    toolCount?: number;
    skillCount?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  type AgentCreateRequest = {
    name: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    status?: number;
    isPublic?: number;
    owner?: string;
    mcpList?: { id: number; enableSkip?: string; envBindings?: EnvBinding[] }[];
    toolList?: { id: number; enableSkip?: string; needConfirm?: boolean; envBindings?: EnvBinding[] }[];
    skillList?: string; // comma-separated skill IDs
  };

  type AgentUpdateRequest = AgentCreateRequest & { id?: number };

  /** 工具/MCP 环境参数定义（来源于工具/MCP 实体） */
  type ToolEnvParamEntry = {
    id?: number;
    envParamName: string;
    description?: string;
    required?: boolean;
    secret?: boolean;
    defaultValue?: string;
  };

  /** 环境变量下拉选项（/env-variables/list） */
  type EnvVarOption = {
    id: number;
    envKey: string;
    displayValue: string;
    sensitive: boolean;
  };

  type EnvBinding = {
    envKey?: string;
    envVarId?: number;
    envVarName?: string;
    envValue?: string;
    customValue?: string;
  };

  type AgentToolConfig = {
    toolId?: number;
    toolName?: string;
    toolDisplayName?: string;
    toolDisplayNameZh?: string;
    toolDescription?: string;
    toolType?: string;
    enableSkip?: string;
    needConfirm?: boolean;
    envBindings?: EnvBinding[];
  };

  type AgentMcpConfig = {
    mcpId?: number;
    mcpName?: string;
    mcpDescription?: string;
    enableSkip?: string;
    envBindings?: EnvBinding[];
  };

  type AgentSkillItem = {
    repositoryId?: number;
    repositoryName?: string;
    skillId?: number;
    skillName?: string;
    skillDescription?: string;
  };

  type AgentOption = {
    id: number;
    name: string;
  };

  // ---- Session ----
  type SessionItem = {
    id: number;
    title: string;
    sessionDescription?: string;
    sessionId?: string;
    agentId?: number;
    name?: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    modelName?: string;
    modelPrice?: number;
    mcpList?: SessionMcpItem[];
    skillList?: SessionSkillItem[];
    owner?: string;
    status: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  type SessionMcpItem = {
    mcpId: number;
    mcpName: string;
    mcpDescription?: string;
    enableSkip?: string;
  };

  type SessionSkillItem = {
    repositoryId?: number;
    repositoryName?: string;
    skillId: number;
    skillName: string;
    skillDescription?: string;
  };

  type SessionCreateRequest = {
    title: string;
    sessionDescription?: string;
    agentId: number;
  };

  // ---- Chat（SSE 消息）----
  type ChatMessageRole = 'user' | 'assistant';

  type SegmentType =
    | 'text'
    | 'thinking'
    | 'tool_call'
    | 'tool_result'
    | 'tool_confirm';

  type MessageSegment = {
    type: SegmentType;
    content: string;
    toolName?: string;
    toolId?: string;
    confirmStatus?: 'pending' | 'confirmed' | 'rejected';
    expanded?: boolean;
  };

  type ChatMessage = {
    id: string;
    role: ChatMessageRole;
    segments: MessageSegment[];
    timestamp: number;
  };

  // ---- Agent Task ----
  type AgentTaskItem = {
    id: number;
    tenantId?: number;
    name: string;
    agentId: number;
    agentName?: string;
    prompt: string;
    cronExpression: string;
    taskStatus: number;
    concurrent: number;
    timeoutSeconds: number;
    description?: string;
    isPublic?: number;
    creator?: string;
    active?: number;
    createTime?: string;
    updateTime?: string;
    lastRunStatus?: number;
    lastRunTime?: string;
  };

  type AgentTaskCreateRequest = {
    name: string;
    agentId: number;
    prompt: string;
    cronExpression: string;
    concurrent?: number;
    timeoutSeconds?: number;
    description?: string;
    isPublic?: number;
  };

  type AgentTaskUpdateRequest = Partial<AgentTaskCreateRequest>;

  type AgentTaskLogItem = {
    id: number;
    taskId: number;
    taskName?: string;
    prompt?: string;
    response?: string;
    sessionId?: string;
    status: number;
    errorInfo?: string;
    tokenUsage?: string;
    startTime?: string;
    endTime?: string;
    durationMs?: number;
    creator?: string;
    createTime?: string;
  };

  // ---- 模型服务商 & 模型 ----
  type ModelProviderItem = {
    id: number;
    type: string;
    name: string;
    description?: string;
    apiKey?: string;
    baseUrl?: string;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  type ModelProviderCreateRequest = {
    type: string;
    name: string;
    description?: string;
    apiKey?: string;
    baseUrl?: string;
    isPublic?: number;
  };

  type ModelProviderUpdateRequest = Partial<ModelProviderCreateRequest> & { id?: number };

  type ModelItem = {
    id: number;
    name: string;
    modelName: string;
    providerId: number;
    providerName?: string;
    description?: string;
    modelType: string;
    supportInternet: number;
    supportReasoning: number;
    supportTool: number;
    supportMcp: number;
    supportVision: number;
    price?: number;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  type ModelCreateRequest = {
    name: string;
    modelName: string;
    providerId: number;
    description?: string;
    modelType: string;
    supportInternet?: number;
    supportReasoning?: number;
    supportTool?: number;
    supportMcp?: number;
    supportVision?: number;
    price?: number;
    status?: number;
  };

  type ModelUpdateRequest = Partial<ModelCreateRequest> & { id?: number };

  // ---- MCP ----
  type McpItem = {
    id: number;
    name: string;
    description?: string;
    type?: string;
    transport?: string;
    command?: string;
    args?: string;
    url?: string;
    configJson?: string;
    status: number;
    isPublic?: number;
    envParams?: ToolEnvParamEntry[];
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  type McpCreateRequest = {
    name: string;
    description?: string;
    type?: string;
    transport?: string;
    command?: string;
    args?: string;
    url?: string;
    configJson?: string;
    status?: number;
    isPublic?: number;
  };

  type McpUpdateRequest = Partial<McpCreateRequest> & { id?: number };

  // ---- Skill ----
  type SkillRepositoryItem = {
    id: number;
    name: string;
    url?: string;
    branch?: string;
    sourceType?: string;
    sourceConfig?: Record<string, any>;
    version?: string;
    description?: string;
    status: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  type SkillRepositoryCreateRequest = {
    name: string;
    url?: string;
    branch?: string;
    sourceType?: string;
    sourceConfig?: Record<string, any>;
    version?: string;
    description?: string;
    status?: number;
    isPublic?: number;
  };

  type SkillRepositoryUpdateRequest = Partial<SkillRepositoryCreateRequest> & { id?: number };

  type SkillItem = {
    id: number;
    name: string;
    repositoryId: number;
    repositoryName?: string;
    repositoryUrl?: string;
    repositoryBranch?: string;
    skillmd?: string;
    resources?: string;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  // ---- Channel ----
  type ChannelItem = {
    id: number;
    name: string;
    type: string;
    typeDisplayName?: string;
    agentId: number;
    agentName?: string;
    callbackKey?: string;
    sessionId?: string;
    communicationMode?: string;
    enabled?: number;
    configJson?: string;
    callbackUrl?: string;
    description?: string;
    creator?: string;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  type ChannelCreateRequest = {
    name: string;
    type: string;
    agentId: number;
    communicationMode?: string;
    enabled?: number;
    configJson?: string;
    description?: string;
    status?: number;
  };

  type ChannelUpdateRequest = Partial<ChannelCreateRequest> & { id?: number };

  // ---- 用户 ----
  type UserItem = {
    id: number;
    username: string;
    nickname?: string;
    email?: string;
    phone?: string;
    roles?: string;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  type UserCreateRequest = {
    username: string;
    password: string;
    nickname?: string;
    email?: string;
    phone?: string;
    roles?: string;
    status?: number;
  };

  type UserUpdateRequest = {
    id?: number;
    nickname?: string;
    email?: string;
    phone?: string;
    roles?: string;
    status?: number;
  };

  // ---- 租户 ----
  type TenantItem = {
    id: number;
    name: string;
    code?: string;
    description?: string;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  type TenantCreateRequest = {
    name: string;
    code?: string;
    description?: string;
    status?: number;
  };

  type TenantUpdateRequest = Partial<TenantCreateRequest> & { id?: number };

  // ---- API Key ----
  type ApiKeyItem = {
    id: number;
    name: string;
    keyHash: string;
    keyPrefix: string;
    scopes: string;
    tenantId?: number;
    rateLimit: number;
    enabled: number;
    expiresAt?: string;
    creator: string;
    active: number;
    createTime?: string;
    updateTime?: string;
  };

  type ApiKeyCreateRequest = {
    name: string;
    scopes: string;
    tenantId?: number;
    rateLimit?: number;
    expiresAt?: string;
  };

  type ApiKeyUpdateRequest = {
    scopes?: string;
    tenantId?: number;
    rateLimit?: number;
    enabled?: number;
    expiresAt?: string;
  };

  type ApiKeyCreatedResponse = {
    id: number;
    name: string;
    keyPrefix: string;
    rawKey: string;
    scopes: string;
    tenantId?: number;
    rateLimit: number;
    expiresAt?: string;
    createTime?: string;
  };

  // ---- 环境变量 ----
  type EnvVariableItem = {
    id: number;
    envKey: string;
    envValue?: string;
    description?: string;
    sensitive?: number;
    creator?: string;
    enabled: number;
    createTime?: string;
    updateTime?: string;
  };

  type EnvVariableCreateRequest = {
    envKey: string;
    envValue?: string;
    description?: string;
    sensitive?: number;
    enabled?: number;
  };

  type EnvVariableUpdateRequest = Partial<EnvVariableCreateRequest> & { id?: number };

  // ---- Token 统计 ----
  type TokenStatsOverall = {
    totalInputToken?: number;
    totalOutputToken?: number;
    grandTotalToken?: number;
    totalFee?: number;
    agentCount?: number;
    sessionCount?: number;
    modelCount?: number;
  };

  type TokenStatsTimeSeriesItem = {
    timePoint?: string;
    dimensionId?: string;
    dimensionName?: string;
    totalInputToken?: number;
    totalOutputToken?: number;
    grandTotalToken?: number;
    totalFee?: number;
  };

  type TokenStatsAggregationResponse = {
    overall?: TokenStatsOverall;
    modelStats?: any[];
    sessionStats?: any[];
    agentStats?: any[];
    timeSeriesData?: TokenStatsTimeSeriesItem[];
  };
}
