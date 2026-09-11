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
   * @en-US Login response
   * @zh-CN 登录响应
   */
 export type LoginResult = {
   code?: number;
   message?: string;
   data?: {
      accessToken?: string;
      tokenType?: string;
      expiresIn?: number;
     userInfo?: {
       userId?: number;
       username?: string;
        nickname?: string;
        avatar?: string;
        email?: string;
       phone?: string;
        gender?: number;
        isAdmin?: number;
      };
    };
    timestamp?: number;
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

 export type FakeCaptcha = {
  code?: number;
  status?: string;
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
   * @zh-CN 工具/MCP 环境参数条目
   */
  export type ToolEnvParamEntry = {
    id?: number;
    envParamName: string;
    required?: boolean;
    secret?: boolean;
    defaultValue?: string;
  };

  /**
   * @deprecated Use ToolEnvParamEntry instead
   */
  export type ToolEnvEntry = ToolEnvParamEntry;

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
    isPublic?: number;
    creator?: string;
    createTime?: string;
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
    id?: number;
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
   * @zh-CN 技能安装结果（对应后端 SkillInstallResponse）
   *
   * 安装是「部分成功」语义：接口返回 200 也可能有技能没落库（源里已删除、内容为空、
   * 写库异常），或被内容安全扫描拦下置为禁用。前端必须按 failed / flagged 决定提示级别。
   */
  export type SkillInstallResult = {
    installed?: string[];
    updated?: string[];
    failed?: { name: string; reason?: string }[];
    flagged?: { name: string; reasons?: string[] }[];
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
    toolType?: string;
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
    skillList?: AgentSkillConfig[];
  };

  /**
   * @zh-CN CLI 工具对象
   */
  export type CliItem = {
    id?: number;
    name: string;
    description?: string;
    version?: string;
    installScript?: string;
    checkCommand?: string;
    envParams?: ToolEnvParamEntry[];
    skillList?: { skillId?: number; skillName?: string; skillDescription?: string }[];
    status?: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
  };

  /**
   * @zh-CN CLI 创建请求
   */
  export type CliCreateRequest = {
    name: string;
    description?: string;
    version?: string;
    installScript: string;
    checkCommand?: string;
    envParams?: ToolEnvParamEntry[];
    skillIds?: number[];
    status?: number;
    isPublic?: number;
  };

  /**
   * @zh-CN CLI 更新请求
   */
  export type CliUpdateRequest = {
    name?: string;
    description?: string;
    version?: string;
    installScript?: string;
    checkCommand?: string;
    envParams?: ToolEnvParamEntry[];
    skillIds?: number[];
    isPublic?: number;
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
    cliList?: { id?: number }[];
    owner?: string;
    status?: number;
    isPublic?: number;
  };

  /**
   * @zh-CN 智能体更新请求
   */
  export type AgentUpdateRequest = {
    id?: number;
    name?: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    mcpList?: AgentMcpConfig[];
    toolList?: { id?: number; needConfirm?: boolean; envBindings?: EnvBinding[] }[];
    skillList?: string; // 逗号分隔的字符串 "1,2,3"
    cliList?: { id?: number }[];
    owner?: string;
    status?: number;
    isPublic?: number;
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
    supportTool?: number;
    supportMcp?: number;
    supportVision?: number;
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
