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
  };

  /**
   * @zh-CN 技能仓库对象
   */
  export type SkillRepositoryItem = {
    id: number;
    name: string;
    url?: string;
    branch?: string;
    description?: string;
    status: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
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
    resources?: string;
    exists?: boolean;
  };

  /**
   * @zh-CN 远程技能列表响应
   */
  export type SkillResponse = {
    records: SkillSyncItem[];
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
   * @zh-CN MCP 配置对象
   */
  export type AgentMcpConfig = {
    id?: number;
    enable_skip?: string;
    enableSkip?: string;
    mcpId?: number;
    mcpName?: string;
    mcpDescription?: string;
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
   * @zh-CN 智能体创建请求
   */
  export type AgentCreateRequest = {
    name: string;
    description?: string;
    systemPrompt?: string;
    modelId?: number;
    mcpList?: AgentMcpConfig[];
    skillList?: string;
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
    skillList?: string; // 逗号分隔的字符串 "1,2,3"
    owner?: string;
    status?: number;
    isPublic?: number;
  };

  /**
   * @zh-CN 模型供应商对象
   */
  export type ModelProviderItem = {
    id?: number;
    name: string;
    displayName?: string;
    apiKey?: string;
    baseUrl?: string;
    status?: number;
    isPublic?: number;
    creator?: string;
    createTime?: string;
    updateTime?: string;
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
   * @zh-CN 定时任务对象
   */
  export type SysJobItem = {
    id?: number;
    jobName: string;
    jobGroup?: string;
    jobClass: string;
    cronExpression: string;
    jobStatus?: number;
    concurrent?: number;
    description?: string;
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
