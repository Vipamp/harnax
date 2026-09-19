// @ts-ignore
/* eslint-disable */

declare namespace API {
  type CurrentUser = {
    name?: string;
    avatar?: string;
    userid?: string;
    email?: string;
    signature?: string;
    title?: string;
    group?: string;
    tags?: { key?: string; label?: string }[];
    notifyCount?: number;
    unreadCount?: number;
    country?: string;
    access?: string;
    geographic?: {
      province?: { label?: string; key?: string };
      city?: { label?: string; key?: string };
    };
    address?: string;
    phone?: string;
  };

  type LoginResult = {
    status?: string;
    type?: string;
    currentAuthority?: string;
  };

  type PageParams = {
    current?: number;
    pageSize?: number;
  };

  type RuleListItem = {
    key?: number;
    disabled?: boolean;
    href?: string;
    avatar?: string;
    name?: string;
    owner?: string;
    desc?: string;
    callNo?: number;
    status?: number;
    updatedAt?: string;
    createdAt?: string;
    progress?: number;
  };

  type RuleList = {
    data?: RuleListItem[];
    /** 列表的内容总数 */
    total?: number;
    success?: boolean;
  };

  type FakeCaptcha = {
    code?: number;
    status?: string;
  };

  type LoginParams = {
    username?: string;
    password?: string;
    autoLogin?: boolean;
    type?: string;
  };

  type ErrorResponse = {
    /** 业务约定的错误码 */
    errorCode: string;
    /** 业务上的错误信息 */
    errorMessage?: string;
    /** 业务上的请求是否成功 */
    success?: boolean;
  };

  type NoticeIconList = {
    data?: NoticeIconItem[];
    /** 列表的内容总数 */
    total?: number;
    success?: boolean;
  };

  type NoticeIconItemType = 'notification' | 'message' | 'event';

  type NoticeIconItem = {
    id?: string;
    extra?: string;
    key?: string;
    read?: boolean;
    avatar?: string;
    title?: string;
    status?: string;
    datetime?: string;
    description?: string;
    type?: NoticeIconItemType;
  };

  type Result<T> = {
    code: number;
    message?: string;
    data?: T;
  };

  type PageResult<T> = {
    records: T[];
    total: number;
    size: number;
    current: number;
    pages: number;
  };

  // 模型服务商相关类型
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

  type ModelProviderUpdateRequest = {
    id?: number;
    type?: string;
    name?: string;
    description?: string;
    apiKey?: string;
    baseUrl?: string;
    isPublic?: number;
  };

  // 模型统计信息
  type ModelStatsInfo = {
    totalModels: number;
    enabledModels: number;
    disabledModels: number;
  };

  // 模型相关类型
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
    thinkingMode?: number;
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
    thinkingMode?: number;
    supportTool?: number;
    supportMcp?: number;
    supportVision?: number;
    price?: number;
    status?: number;
  };

  type ModelUpdateRequest = {
    id?: number;
    name?: string;
    modelName?: string;
    providerId?: number;
    description?: string;
    modelType?: string;
    supportInternet?: number;
    supportReasoning?: number;
    thinkingMode?: number;
    supportTool?: number;
    supportMcp?: number;
    supportVision?: number;
    price?: number;
    status?: number;
  };

  // 技能仓库相关类型
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
    /** 上次同步结论，从未同步过为空。对应后端 SkillSyncRecorder 的四种状态 */
    lastSyncStatus?: 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'EMPTY';
    lastSyncTime?: string;
    /** 上次同步报告原文：saved / installed / updated / failed / flagged / stale / error */
    lastSyncDetail?: SkillSyncDetail;
    /** 仓库下仍处于启用态的技能数，删源要求它为 0。/skill-sources/active 不带这个数 */
    enabledSkillCount?: number;
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

  type SkillRepositoryUpdateRequest = {
    id?: number;
    name?: string;
    url?: string;
    branch?: string;
    sourceConfig?: Record<string, any>;
    version?: string;
    description?: string;
    status?: number;
    isPublic?: number;
  };

  // 技能来源相关类型
  type SkillSourceCreateRequest = {
    name: string;
    sourceType: string;
    sourceConfig?: Record<string, any>;
    version?: string;
    description?: string;
    status?: number;
    isPublic?: number;
    url?: string;
    branch?: string;
  };

  type SkillSourceItem = {
    id: number;
    name: string;
    sourceType: string;
    sourceConfig?: Record<string, any>;
    version?: string;
    url?: string;
    branch?: string;
    description?: string;
    status: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
    lastSyncStatus?: 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'EMPTY';
    lastSyncTime?: string;
    lastSyncDetail?: SkillSyncDetail;
    enabledSkillCount?: number;
  };

  // 技能相关类型
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
    /** 绑定该技能的 agent 数。被绑定的技能既不能停用也不能删除（后端同一条规则） */
    boundAgentCount?: number;
    createTime?: string;
    updateTime?: string;
  };

  type SkillCreateRequest = {
    name: string;
    repositoryId: number;
    skillmd?: string;
    resources?: string;
    status?: number;
  };

  type SkillUpdateRequest = {
    id?: number;
    name?: string;
    repositoryId?: number;
    skillmd?: string;
    resources?: string;
    status?: number;
  };

  // 会话相关类型
  type SessionItem = {
    id: number;
    title: string;
    sessionDescription?: string;
    sessionId?: string;
    agentId?: number;
    /** 非空表示这是一个团队会话，产物面板据此出现 */
    teamId?: number;
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
  };

  type SessionSkillItem = {
    repositoryId?: number;
    repositoryName?: string;
    skillId: number;
    skillName: string;
    skillDescription?: string;
  };

  // Agent tool config (from AgentResponse.ToolItem)
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
    needConfirm?: boolean;
    envBindings?: EnvBinding[];
  };

  // Agent MCP config (from AgentResponse.McpItem)
  type AgentMcpConfig = {
    mcpId?: number;
    mcpName?: string;
    mcpDescription?: string;
    envBindings?: EnvBinding[];
  };

  // Agent skill item (from AgentResponse.SkillItem)
  type AgentSkillItem = {
    repositoryId?: number;
    repositoryName?: string;
    skillId?: number;
    skillName?: string;
    skillDescription?: string;
  };

  type SessionCreateRequest = {
    title: string;
    sessionDescription?: string;
    agentId: number;
    /** 团队会话：主管由团队决定，agentId 仍要带上主管 agent 以通过后端校验 */
    teamId?: number;
  };

  // 团队相关类型（对应 admin 的 TeamResponse / TeamCreateRequest）
  type TeamMemberItem = {
    agentId: number;
    agentName?: string;
    agentDescription?: string;
    delegationDescription?: string;
    agentStatus?: number;
    /** 引用已失效（agent 被删或停用）时为 false */
    agentAvailable?: boolean;
  };

  type TeamItem = {
    id: number;
    name: string;
    description?: string;
    leadAgentId?: number;
    leadAgentName?: string;
    leadAgentDescription?: string;
    instructions?: string;
    memberList?: TeamMemberItem[];
    status?: number;
    isPublic?: number;
    tenantId?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  type TeamMemberRequest = {
    agentId: number;
    delegationDescription?: string;
  };

  type TeamCreateRequest = {
    name: string;
    description?: string;
    leadAgentId: number;
    instructions?: string;
    members: TeamMemberRequest[];
    status?: number;
    isPublic?: number;
  };

  type TeamUpdateRequest = {
    name?: string;
    description?: string;
    leadAgentId?: number;
    instructions?: string;
    /** 非空即整体替换现有成员 */
    members?: TeamMemberRequest[];
    isPublic?: number;
  };

  type TeamArtifactItem = {
    fileId: string;
    fileName: string;
    mimeType: string;
    sizeBytes: number;
    memberAgentId: number;
    createTime: string;
  };

  // Channel 相关类型
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

  type ChannelUpdateRequest = {
    id?: number;
    name?: string;
    type?: string;
    agentId?: number;
    communicationMode?: string;
    enabled?: number;
    configJson?: string;
    description?: string;
    status?: number;
  };

  // API Key 相关类型
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

  // Workspace file browser types
  type WorkspaceFile = {
    type: 'file' | 'directory' | 'symlink' | 'unknown';
    name: string;
    size: number;
    modified: string;
  };

  type WorkspaceFileContent = {
    content: string;
    truncated: boolean;
    size: number;
  };

  type WorkspaceStatus = {
    active: boolean;
    sessionId: string;
    containerId?: string;
    containerName?: string;
    image?: string;
    workspaceRoot?: string;
  };

  // Agent Task 相关类型
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

  type AgentTaskUpdateRequest = {
    name?: string;
    agentId?: number;
    prompt?: string;
    cronExpression?: string;
    concurrent?: number;
    timeoutSeconds?: number;
    description?: string;
    isPublic?: number;
  };

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

  type AgentOption = {
    id: number;
    name: string;
  };
}
