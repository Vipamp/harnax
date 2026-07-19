// services/tool.ts
// 工具管理：内置工具列表（含环境参数 / 确认策略）

import { request } from './request';

export interface ToolEnvParam {
  envParamName: string;
  description?: string;
  required?: boolean;
  secret?: boolean;
  defaultValue?: string;
}

export interface BuiltinTool {
  id?: number | string;
  name: string;
  displayName?: string;
  displayNameZh?: string;
  description?: string;
  envParams?: ToolEnvParam[];
  requiredEnvParamKeys?: string[];
  needConfirm?: number;
  isRequired?: number;
}

/** 获取内置工具列表 */
export function getBuiltinTools() {
  return request<BuiltinTool[]>({
    url: '/api/admin/tools/builtin',
    method: 'GET',
    silent: true,
  });
}
