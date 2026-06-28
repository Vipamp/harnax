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

  type ModelUpdateRequest = {
    id?: number;
    name?: string;
    modelName?: string;
    providerId?: number;
    description?: string;
    modelType?: string;
    supportInternet?: number;
    supportReasoning?: number;
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
    description?: string;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  type SkillRepositoryCreateRequest = {
    name: string;
    url?: string;
    branch?: string;
    description?: string;
    status?: number;
  };

  type SkillRepositoryUpdateRequest = {
    id?: number;
    name?: string;
    url?: string;
    branch?: string;
    description?: string;
    status?: number;
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

  // 定时任务相关类型
  type JobItem = {
    id?: number;
    jobName?: string;
    jobGroup?: string;
    jobClass?: string;
    cronExpression?: string;
    jobStatus?: number;
    concurrent?: number;
    description?: string;
    createTime?: string;
    updateTime?: string;
    isPublic?: number;
    creator?: string;
  };

  type SysJobCreateRequest = {
    jobName: string;
    jobGroup?: string;
    jobClass: string;
    cronExpression: string;
    concurrent?: number;
    description?: string;
  };

  type SysJobUpdateRequest = {
    id?: number;
    jobName: string;
    jobGroup?: string;
    jobClass: string;
    cronExpression: string;
    concurrent?: number;
    description?: string;
  };

  // 定时任务日志相关类型
  type JobLogItem = {
    id?: number;
    jobId?: number;
    jobName?: string;
    jobGroup?: string;
    invokeTarget?: string;
    jobMessage?: string;
    status?: number;
    exceptionInfo?: string;
    startTime?: string;
    endTime?: string;
    duration?: number;
    createTime?: string;
  };

  // 会话相关类型
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
  };

  type SessionCreateRequest = {
    title: string;
    sessionDescription?: string;
    agentId: number;
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
}
