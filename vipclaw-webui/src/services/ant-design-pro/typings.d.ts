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
    name: string;
    displayName: string;
    apiKey?: string;
    baseUrl?: string;
    status: number;
    createTime?: string;
    updateTime?: string;
  };

  type ModelProviderCreateRequest = {
    name: string;
    displayName: string;
    apiKey?: string;
    baseUrl?: string;
    status?: number;
  };

  type ModelProviderUpdateRequest = {
    id?: number;
    name?: string;
    displayName?: string;
    apiKey?: string;
    baseUrl?: string;
    status?: number;
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
}
