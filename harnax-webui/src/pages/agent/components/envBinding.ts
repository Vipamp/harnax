/**
 * 环境参数是否算「已填」。
 *
 * 引用型绑定只带 envVarId：值由运行时按 id 现取，所以有 id 就算填了，
 * 而把列表接口给的掩码当成值抄进 envValue 是不行的（见 ToolConfigPanel）。
 */
export function isEnvBindingFilled(binding?: { envValue?: string; envVarId?: number }): boolean {
  if (!binding) return false;
  if (binding.envVarId) return true;
  return !!binding.envValue?.trim();
}

/**
 * 必填环境参数缺值的判定。
 *
 * 工具与服务端自带默认值的去向不同：
 * - 工具：`agent_tool_env_param.default_value` 不下发，运行时只有 ToolEnvContext 里绑定值，
 *   所以默认值不能顶替必填。
 * - MCP：`mcp_server.env_params` 会整份解成 stdio 进程的环境变量，默认值确实能到进程里。
 */
export function findMissingRequiredEnvParam(
  entries: API.ToolEnvParamEntry[] | undefined,
  bindings: { envKey: string; envValue?: string; envVarId?: number }[] | undefined,
  defaultCountsAsFilled: boolean,
): string | undefined {
  return (entries || [])
    .filter((e: any) => e.required)
    .map((e: any) => ({
      name: e.envParamName as string,
      filled:
        isEnvBindingFilled((bindings || []).find((b) => b.envKey === e.envParamName)) ||
        (defaultCountsAsFilled && !!e.defaultValue?.trim()),
    }))
    .find((entry) => !entry.filled)?.name;
}
