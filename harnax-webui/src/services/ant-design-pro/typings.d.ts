// @ts-ignore
/* eslint-disable */

declare namespace API {
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

  // 模型相关类型
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

  type SessionCreateRequest = {
    title: string;
    sessionDescription?: string;
    /** 团队会话留空：主管由团队决定，session 不再挂任何 agent 行 */
    agentId?: number;
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

  type TeamSkillItem = {
    skillId: number;
    skillName?: string;
    skillDescription?: string;
    repositoryId?: number;
    repositoryName?: string;
    /** 引用已失效（技能被删或停用）时为 false，运行侧会跳过它 */
    skillAvailable?: boolean;
  };

  type TeamItem = {
    id: number;
    /** 团队名同时就是主管名，不再有独立的主管智能体 */
    name: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    modelName?: string;
    skillList?: TeamSkillItem[];
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
    description: string;
    systemPrompt: string;
    modelId: number;
    /** 主管只挂技能：工具、MCP、CLI 属于成员的 agent 配置 */
    skillIds?: number[];
    members: TeamMemberRequest[];
    status?: number;
    isPublic?: number;
  };

  type TeamUpdateRequest = {
    name?: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    /** 非空即整体替换主管技能，空数组表示「不给主管挂技能」 */
    skillIds?: number[];
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
