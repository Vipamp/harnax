declare module'slash2';
declare module '*.css';
declare module '*.less';
declare module '*.scss';
declare module '*.sass';
declare module '*.svg';
declare module '*.png';
declare module '*.jpg';
declare module '*.jpeg';
declare module '*.gif';
declare module '*.bmp';
declare module '*.tiff';
declare module 'omit.js';
declare module 'numeral';
declare module'mockjs';
declare module 'react-fittext';

declare const REACT_APP_ENV: 'test' | 'dev' | 'pre' | false;

declare namespace API {
  /**
   * @en-US User object
   * @zh-CN 用户对象
   */
 export type UserItem = {
   id?: number;
   username: string;
    nickname?: string;
    email?: string;
   phone?: string;
    gender?: number;
    avatar?: string;
   status?: number;
    isAdmin?: number;
    lastLoginTime?: string;
    createTime?: string;
    updateTime?: string;
  };

  /**
   * @en-US User create request
   * @zh-CN 用户创建请求
   */
 export type SysUserCreateRequest = {
   username: string;
   password: string;
    nickname?: string;
    email?: string;
   phone?: string;
    gender?: number;
    avatar?: string;
  };

  /**
   * @en-US User update request
   * @zh-CN 用户更新请求
   */
 export type SysUserUpdateRequest = {
    id: number;
   username?: string;
    nickname?: string;
    email?: string;
   phone?: string;
    gender?: number;
    avatar?: string;
    isAdmin?: number;
  };

 export type UserListResponse = {
   code: number;
   message: string;
   data: UserItem[];
    timestamp: number;
  };

 export type UserPageResponse = {
   code: number;
   message: string;
   data: {
     records: UserItem[];
      total: number;
      size: number;
      current: number;
    };
    timestamp: number;
  };

 export type UserResponse = {
   code: number;
   message: string;
   data: UserItem;
    timestamp: number;
  };

  /**
   * @en-US Login parameters
   * @zh-CN 登录参数
   */
export type LoginParams = {
 username?: string;
 password?: string;
 mobile?: string;
 captcha?: string;
 captchaKey?: string;
 autoLogin?: boolean;
 type?: string;
};

  /**
   * @en-US Current user info
   * @zh-CN 当前用户信息
   */
 export type CurrentUser = {
   userId?: number;
   username?: string;
    nickname?: string;
    avatar?: string;
    email?: string;
   phone?: string;
    gender?: number;
    isAdmin?: number;
  };

 /**
 * @en-US Captcha response
 * @zh-CN 验证码响应
 */
export type CaptchaResult = {
  code?: number;
message?: string;
  data?: {
   imageBase64?: string;
   captchaKey?: string;
   expiresIn?: number;
  };
  timestamp?: number;
 };

  /**
   * @zh-CN MCP 配置条目（headers 使用）
   */
  export type McpConfigEntry = {
    key: string;
    value: string;
    secret?: boolean;
  };

  /**
   * @zh-CN 工具 / MCP / CLI 的环境参数声明
   */
  export type ToolEnvParamEntry = {
    id?: number;
    envParamName: string;
    /** 声明方写的填写说明；CLI 包来自 plugin.yaml 的 envParams[].description */
    description?: string;
    required?: boolean;
    secret?: boolean;
    defaultValue?: string;
  };

  /**
   * @zh-CN MCP 服务对象
   */
  export type McpServerItem = {
    id?: number;
    name: string;
    description?: string;
    type: 'stdio' | 'sse' | 'streamablehttp';
    command?: string;
    url?: string;
    status?: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
    headers?: McpConfigEntry[];
    envParams?: ToolEnvParamEntry[];
    authType?: string;
    oauthConfig?: McpOAuthConfig;
  };

  /**
   * @zh-CN MCP 服务创建请求
   */
  export type McpServerCreateRequest = {
    name: string;
    description?: string;
    type: 'stdio' | 'sse' | 'streamablehttp';
    command?: string;
    url?: string;
    status?: number;
    isPublic?: number;
    headers?: McpConfigEntry[];
    envParams?: ToolEnvParamEntry[];
    authType?: string;
    oauthConfig?: McpOAuthConfig;
  };

  /**
   * @zh-CN MCP 服务更新请求
   */
  export type McpServerUpdateRequest = {
    id?: number;
    name?: string;
    description?: string;
    type?: 'stdio' | 'sse' | 'streamablehttp';
    command?: string;
    url?: string;
    status?: number;
    isPublic?: number;
    headers?: McpConfigEntry[];
    envParams?: ToolEnvParamEntry[];
    authType?: string;
    oauthConfig?: McpOAuthConfig;
  };

  /**
   * @zh-CN MCP 的 OAuth 配置（非敏感部分，对应后端 McpOAuthConfig）
   */
  export type McpOAuthConfig = {
    /** 授权服务器 issuer；留空表示由发现填入，编辑时留空不代表清除库里已有的值 */
    authorizationServer?: string;
    scopes?: string[];
    audience?: string;
    /** 是否发送 RFC 8707 的 resource 参数，让令牌绑定到本 MCP 服务 */
    resourceIndicator?: boolean;
  };

  /**
   * @zh-CN 登记 OAuth 客户端的请求（对应后端 McpOAuthClientRequest）
   */
  export type McpOAuthClientRequest = {
    clientId: string;
    /** 省略或传掩码值表示保留库里已有的密文；空串表示清除（公共客户端，只靠 PKCE） */
    clientSecret?: string;
    callbackUrl?: string;
  };

  /**
   * @zh-CN 一次发现 / 登记的结果回显（对应后端 McpOAuthDiscoveryResponse，不含任何明文密钥）
   */
  export type McpOAuthDiscoveryResponse = {
    issuer?: string;
    /** CONFIG / PROTECTED_RESOURCE / RESOURCE_METADATA */
    issuerSource?: string;
    authorizationEndpoint?: string;
    tokenEndpoint?: string;
    registrationEndpoint?: string;
    revocationEndpoint?: string;
    scopesSupported?: string[];
    unknownScopes?: string[];
    clientId?: string;
    clientSecretPresent?: boolean;
    callbackUrl?: string;
    /** 本次构建生成的回跳地址；与 callbackUrl 不一致就说明登记里存的是旧值 */
    defaultCallbackUrl?: string;
  };

  /**
   * @zh-CN 发起一次授权拿到的链接（对应后端 McpOAuthAuthorizeResponse）
   * state 就在这个 URL 里，由授权服务器原样带回，不单独出现在响应字段中
   */
  export type McpOAuthAuthorizeResponse = {
    authorizeUrl: string;
    issuer?: string;
    scopes?: string[];
    /** 这个请求在服务器上还能被兑换多久，单位秒 */
    expiresIn?: number;
  };

  /**
   * @zh-CN 落地页把授权服务器带回的参数交给后端换票（对应后端 McpOAuthExchangeRequest）
   */
  export type McpOAuthExchangeRequest = {
    code?: string;
    state?: string;
    error?: string;
    errorDescription?: string;
  };

  /**
   * @zh-CN 换票的结果（对应后端 McpOAuthExchangeResponse，没有任何字段能装令牌）
   */
  export type McpOAuthExchangeResponse = {
    authorized: boolean;
    message?: string;
    scopes?: string[];
    accessExpiresAt?: string;
  };

  /**
   * @zh-CN 本用户在这个 MCP 上的授权状态（对应后端 McpOAuthStatusResponse，不含令牌）
   */
  export type McpOAuthStatusResponse = {
    authorized: boolean;
    /** ACTIVE / NEEDS_CONSENT / REVOKED，没授权过时为空 */
    status?: string;
    scopes?: string[];
    accessExpiresAt?: string;
    lastRefreshedAt?: string;
    lastError?: string;
  };

  /**
   * @zh-CN 撤销授权的结果（对应后端 McpOAuthRevokeResponse）
   */
  export type McpOAuthRevokeResponse = {
    revoked: boolean;
    /** 授权服务器有没有真的收下这次撤销；它不支持撤销时为 false，本地仍然清掉了 */
    upstreamRevoked?: boolean;
    message?: string;
  };

  /**
   * @zh-CN 技能仓库对象
   */
  export type SkillRepositoryItem = {
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

  /**
   * @zh-CN 上次同步报告（对应后端 last_sync_detail 列）
   */
  export type SkillSyncDetail = {
    saved?: number;
    installed?: string[];
    updated?: string[];
    failed?: { name: string; reason: string }[];
    flagged?: { name: string; reasons?: string[] }[];
    stale?: string[];
    /** 整源失败原因与「源里没有技能」的原因共用一个字段 */
    error?: string;
  };

  /**
   * @zh-CN 技能源创建请求
   */
  export type SkillSourceCreateRequest = {
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

  /**
   * @zh-CN 技能源更新请求
   */
  export type SkillSourceUpdateRequest = {
    name?: string;
    sourceConfig?: Record<string, any>;
    version?: string;
    description?: string;
    status?: number;
    isPublic?: number;
    url?: string;
    branch?: string;
  };

  /**
   * @zh-CN 技能仓库创建请求
   */
  export type SkillRepositoryCreateRequest = {
    name: string;
    url?: string;
    branch?: string;
    description?: string;
    status?: number;
    isPublic?: number;
  };

  /**
   * @zh-CN 技能仓库更新请求
   */
  export type SkillRepositoryUpdateRequest = {
    id?: number;
    name?: string;
    url?: string;
    branch?: string;
    description?: string;
    status?: number;
    isPublic?: number;
  };

  /**
   * @zh-CN 技能对象
   */
  export type SkillItem = {
    id: number;
    name: string;
    repositoryId: number;
    repositoryName?: string;
    repositoryUrl?: string;
    repositoryBranch?: string;
    description?: string;
    skillmd?: string;
    resources?: string;
    status: number;
    /** 绑定该技能的 agent 数。被 agent 或团队主管绑定的技能都不能停用/删除（后端同一条规则） */
    boundAgentCount?: number;
    /** 主管技能直接挂在 team 上，只被团队绑定时 agent 数为零但开关仍必须锁住 */
    boundTeamCount?: number;
    isPublic?: number;
    creator?: string;
    /** Provenance: human (configured here) or agent_promoted (an agent wrote it) */
    origin?: string;
    /** The session an agent proposed the skill in; null for a human skill */
    originRef?: string;
    createTime?: string;
    updateTime?: string;
    /** Compact rollout state of this skill; absent means everybody may load it (same as mode ALL) */
    visibility?: SkillVisibilitySummary;
  };

  /**
   * @zh-CN 技能运行时可见性的列表侧摘要
   */
  export type SkillVisibilitySummary = {
    /** ALL / CANARY / ALLOW_LIST / ENV */
    mode: string;
    /** Rollout percentage, when mode is CANARY */
    canaryPct?: number;
    /** How many users are allowed, when mode is ALLOW_LIST; the ids themselves come from the detail read */
    userCount?: number;
    /** Environment labels, when mode is ENV */
    environments?: string[];
  };

  /**
   * @zh-CN 技能可见性整条规则（PUT 提交全部字段，所选模式用不到的字段由服务端清空）
   */
  export type SkillVisibilityUpdateRequest = {
    mode: string;
    canaryPct?: number;
    userIds?: number[];
    environments?: string[];
  };

  /**
   * @zh-CN 技能可见性策略详情（GET 返回，含可编辑标记与白名单用户姓名）
   */
  export type SkillVisibilityInfo = {
    skillId?: number;
    skillName?: string;
    mode?: string;
    canaryPct?: number;
    userIds?: number[];
    /** The allowed users with their usernames; a missing username is an account that no longer exists */
    users?: { id: number; username?: string }[];
    environments?: string[];
    /** Whether this caller's tenant may write this policy */
    editable?: boolean;
    /** Tenant owning the skill, which is the tenant the allow-list has to pick members of */
    tenantId?: number;
    updateTime?: string;
  };

  /**
   * @zh-CN 技能创建请求
   */
  export type SkillCreateRequest = {
    name: string;
    repositoryId: number;
    description?: string;
    skillmd?: string;
    resources?: string;
    status?: number;
    isPublic?: number;
  };

  /**
   * @zh-CN 技能更新请求
   */
  export type SkillUpdateRequest = {
    name?: string;
    repositoryId?: number;
    description?: string;
    skillmd?: string;
    resources?: string;
    status?: number;
    isPublic?: number;
  };

  /**
   * @zh-CN 远程技能响应（同步弹窗用）
   */
  export type SkillSyncItem = {
    name: string;
    description?: string;
    skillmd?: string;
    /** 后端 SyncSkillResponse 里是「相对路径 -> 内容」的映射，不是字符串 */
    resources?: Record<string, string>;
    exists?: boolean;
  };

  /**
   * @zh-CN 远程技能列表响应
   */
  export type SkillResponse = {
    records: SkillSyncItem[];
  };

  /**
   * @zh-CN 单条技能在统计窗口内的用量（对应后端 SkillUsageSummaryResponse.Row）
   */
  export type SkillUsageRow = {
    skillId: number;
    name: string;
    repositoryId: number;
    repositoryName?: string;
    status: number;
    origin?: string;
    viewCount: number;
    useCount: number;
    lastUsedAt?: string;
  };

  /**
   * @zh-CN 技能用量汇总：零使用的技能也在 rows 里，这张页要回答的正是哪条从没被装载过
   */
  export type SkillUsageSummary = {
    days: number;
    since?: string;
    totalSkills: number;
    zeroUseCount: number;
    totalViews: number;
    totalUses: number;
    rows: SkillUsageRow[];
  };

  /**
   * @zh-CN 草稿队列可按的状态过滤。EXPIRED 是后端列上写着而没有任何代码写入的值，接口按它过滤直接拒。
   */
  export type SkillDraftStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

  /**
   * @zh-CN 队列的一行：不带正文，正文只在详情里
   */
  export type SkillDraftRow = {
    id: number;
    name: string;
    description?: string;
    status: string;
    scanVerdict?: string;
    upstreamFindingCount: number;
    sourceSessionId: string;
    agentId?: number;
    createTime?: string;
    updateTime?: string;
    reviewedBy?: string;
    reviewedAt?: string;
    rejectReason?: string;
  };

  /**
   * @zh-CN admin 的 Page 序列化出的就是这四个字段，与 PageResult 的 size/current 形状不同
   */
  export type SkillDraftPage = {
    pageNum: number;
    pageSize: number;
    total: number;
    records: SkillDraftRow[];
  };

  /**
   * @zh-CN 脚本预览：头几行、行数与 sha256，全部由服务端对落库字节现算
   */
  export type SkillDraftScript = {
    relPath: string;
    headPreview: string;
    totalLines: number;
    sha256: string;
  };

  /**
   * @zh-CN 草稿状态变化的一条痕迹
   */
  export type SkillDraftHistoryItem = {
    action: string;
    actor: string;
    detail?: string;
    createTime?: string;
  };

  /**
   * @zh-CN 单条草稿的全文。localFindings 是决定禁用与否的那一份，scanFindings 只用于展示
   */
  export type SkillDraftDetail = {
    id: number;
    name: string;
    description?: string;
    status: string;
    skillmd: string;
    resources?: Record<string, string>;
    scripts?: SkillDraftScript[];
    scanVerdict?: string;
    scanFindings?: string[];
    localFindings?: string[];
    contentDigest: string;
    sourceSessionId: string;
    agentId?: number;
    createTime?: string;
    updateTime?: string;
    reviewedBy?: string;
    reviewedAt?: string;
    rejectReason?: string;
    history?: SkillDraftHistoryItem[];
  };

  export type SkillDraftApproveRequest = {
    expectedDigest: string;
    conflictResolution?: 'replace' | 'rename';
    newName?: string;
  };

  export type SkillDraftRejectRequest = {
    reason: string;
  };

  /**
   * @zh-CN 审核动作的结果。审核人能据以再动作的拒绝随 200 信封回来，判据是 outcome 而不是状态码。
   */
  export type SkillDraftDecision = {
    outcome: 'PROMOTED' | 'REJECTED' | 'DRAFT_CHANGED' | 'ALREADY_REVIEWED' | 'NAME_TAKEN';
    skillId?: number;
    skillStatus?: number;
    promotedName?: string;
    findings?: string[];
    reason?: string;
    currentDigest?: string;
    reviewedBy?: string;
    reviewedAt?: string;
    rejectReason?: string;
  };

  /**
   * @zh-CN 技能安装结果（对应后端 SkillInstallResponse）
   *
   * 安装是「部分成功」语义：接口返回 200 也可能有技能没落库（源里已删除、内容为空、
   * 写库异常），或被内容安全扫描拦下置为禁用。前端必须按 failed / flagged 决定提示级别。
   */
  export type SkillInstallResult = {
    installed?: string[];
    updated?: string[];
    /** 后端保证原因恒有值，界面直接渲染即可 */
    failed?: { name: string; reason: string }[];
    flagged?: { name: string; reasons?: string[] }[];
    /** 整源没跑通（拉取失败等），此时一条技能都没存 */
    sourceError?: string;
    /** 源本身读到了，但里面没有可安装的技能 */
    emptyReason?: string;
    /** 库里还留着、源里已经消失的技能。只报告，不会自动删除 */
    stale?: string[];
    savedCount?: number;
    failedCount?: number;
    complete?: boolean;
    summary?: string;
  };

  /**
   * @zh-CN 技能源及本次安装结果（对应后端 SkillSourceInstallResponse）
   */
  export type SkillSourceInstallResult = {
    source: SkillRepositoryItem;
    install: SkillInstallResult;
  };

  /**
   * @zh-CN 智能体对象
   */
  export type AgentItem = {
    id?: number;
    name: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    modelName?: string;  // 模型名称
    modelPrice?: number;  // 模型价格
    mcpList?: AgentMcpConfig[];
    skillList?: AgentSkillConfig[];
    toolList?: AgentToolConfig[];
    cliList?: AgentCliConfig[];
    sessionList?: SessionItem[];  // 会话列表
    sessionCount?: number;  // 关联会话数量
    owner?: string;
    status: number;
    isPublic?: number;
    /** 0/1 — the agent may author skills in its own workspace; a human still has to approve them */
    skillSelfWrite?: number;
    /** 0/1 — long-term memory; absent or 1 means on, only an explicit 0 turns it off */
    memoryEnabled?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  /**
   * @zh-CN 智能体工具配置
   */
  export type AgentToolConfig = {
    toolId?: number;
    toolName?: string;
    toolDisplayName?: string;
    toolDisplayNameZh?: string;
    toolDescription?: string;
    needConfirm?: boolean;
    envBindings?: EnvBinding[];
  };

  /**
   * @zh-CN 环境参数绑定（快照结构）
   */
  export type EnvBinding = {
    envKey: string;
    envValue?: string;
    envVarId?: number;
    envVarName?: string;
    customValue?: string;
  };

  /**
   * @zh-CN MCP 配置对象
   */
  export type AgentMcpConfig = {
    id?: number;
    mcpId?: number;
    mcpName?: string;
    mcpDescription?: string;
    envBindings?: EnvBinding[];
  };

  /**
   * @zh-CN 技能配置对象
   */
  export type AgentSkillConfig = {
    repositoryId?: number;
    repositoryName?: string;
    skillId?: number;
    skillName?: string;
    skillDescription?: string;
  };

  /**
   * @zh-CN 智能体 CLI 配置对象
   */
  export type AgentCliConfig = {
    cliId?: number;
    cliName?: string;
    cliDescription?: string;
    version?: string;
    /** 本智能体为该 CLI 显式填的参数值，未填的键由包声明的默认值兜底 */
    envBindings?: EnvBinding[];
    skillList?: AgentSkillConfig[];
  };

  /**
   * @zh-CN CLI 插件包登记对象（admin 启动时登记，页面只读）
   */
  export type CliItem = {
    id?: number;
    name: string;
    description?: string;
    version?: string;
    checkCommand?: string;
    /** sha256 of the registered package archive — the package identity */
    packageDigest?: string;
    envParams?: ToolEnvParamEntry[];
    /** The skill shipped inside the package; a CLI has no other skills */
    skill?: { skillId?: number; skillName?: string; skillDescription?: string };
    status?: number;
    createTime?: string;
    updateTime?: string;
  };

  /**
   * The rest of what plugin.yaml declared. A page row carries none of these three keys, so the detail
   * drawer is the only reader of them and has to ask `GET /api/admin/clis/{id}` for them.
   */
  export type CliDetail = CliItem & {
    /** Canonical sha256 of payload plus deps — the sandbox image fingerprint */
    payloadDigest?: string;
    /** apt packages the platform installs alongside the payload */
    depsApt?: string[];
    /** Env slots the platform fills at container creation */
    runtimeEnv?: Record<string, string>;
  };

  /**
   * @zh-CN 绑定了某 CLI 的智能体（启停前展示影响范围）
   */
  export type CliRelatedAgent = {
    agentId: number;
    agentName: string;
    status: number;
  };

  /**
   * @zh-CN 绑定了某 MCP 服务的智能体（删除前展示影响范围）
   */
  export type McpRelatedAgent = {
    agentId: number;
    agentName: string;
    status: number;
  };

  /**
   * @zh-CN 我的智能体记忆列表行（GET /api/admin/memory，只含当前登录用户的智能体）
   */
  export type MemoryAgentItem = {
    /** The agent's name — memory is keyed by name, not by numeric id */
    agentId: string;
    /** Curated MEMORY.md text; admin sends "" when nothing has been curated yet */
    content: string;
    /** Last write of the curated layer; absent when admin had no timestamp to report */
    lastModified?: string;
    /** Days that have a daily note, e.g. `2026-10-05`, oldest first */
    dates: string[];
  };

  /**
   * @zh-CN 某一天的记忆原文（只有详情接口返回）
   */
  export type MemoryDailyEntry = {
    date: string;
    content: string;
    lastModified?: string;
  };

  /**
   * @zh-CN 单个智能体的记忆详情（GET /api/admin/memory/{agentId}）
   */
  export type MemoryDetail = {
    agentId: string;
    content: string;
    lastModified?: string;
    entries: MemoryDailyEntry[];
  };

  /**
   * @zh-CN 删除记忆的应答（objects removed from the shared bucket, both routes together）
   */
  export type MemoryDeleteResponse = {
    agentId: string;
    deletedObjects: number;
  };

  /**
   * @zh-CN 智能体创建请求
   */
  export type AgentCreateRequest = {
    name: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    mcpList?: AgentMcpConfig[];
    toolList?: { id?: number; needConfirm?: boolean; envBindings?: EnvBinding[] }[];
    skillList?: string;
    cliList?: { id?: number; envBindings?: EnvBinding[] }[];
    owner?: string;
    status?: number;
    isPublic?: number;
    skillSelfWrite?: number;
    memoryEnabled?: number;
  };

  /**
   * @zh-CN 智能体更新请求
   */
  export type AgentUpdateRequest = {
    name?: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    mcpList?: AgentMcpConfig[];
    toolList?: { id?: number; needConfirm?: boolean; envBindings?: EnvBinding[] }[];
    skillList?: string; // 逗号分隔的字符串 "1,2,3"
    cliList?: { id?: number; envBindings?: EnvBinding[] }[];
    status?: number;
    isPublic?: number;
    skillSelfWrite?: number;
    memoryEnabled?: number;
  };

  /**
   * @zh-CN 模型供应商对象
   */
  export type ModelProviderItem = {
    id?: number;
    type?: string;
    name: string;
    description?: string;
    displayName?: string;
    apiKey?: string;
    apiUrl?: string;
    baseUrl?: string;
    status?: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  /**
   * @zh-CN 模型统计信息
   */
  export type ModelStatsInfo = {
    totalModels: number;
    enabledModels: number;
    disabledModels: number;
  };

  /**
   * @zh-CN 模型对象
   */
  export type ModelItem = {
    id?: number;
    name: string;
    modelName?: string;
    providerId?: number;
    providerName?: string;
    description?: string;
    modelType?: string;
    supportInternet?: number;
    supportReasoning?: number;
    /** 0: no thinking, 1: optional, 2: required — always present on ModelResponse */
    thinkingMode?: number;
    supportTool?: number;
    supportMcp?: number;
    supportVision?: number;
    /** Operator-declared token budget; absent means the runtime infers it from the model name */
    contextWindow?: number;
    price?: number;
    status?: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  /**
   * @zh-CN MCP 工具参数
   */
  export type McpToolParameter = {
    name: string;
    type: string;
    description: string;
  };

  /**
   * @zh-CN MCP 工具
   */
  export type McpTool = {
    name: string;
    parameters: McpToolParameter[];
  };

  /**
   * @zh-CN MCP 工具列表响应
   */
  export type McpToolsResponse = {
    code: number;
    message: string;
    data: McpTool[];
    timestamp: number;
  };
}
