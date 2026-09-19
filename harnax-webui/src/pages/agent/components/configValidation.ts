/**
 * 向导第三步（工具 / MCP / 技能）三类配置的共用校验。
 *
 * 原先 CreateForm 与 UpdateForm 各抄了一份逐字相同的实现，只在校验「下一步」，
 * 点「完成」时不再过一遍。三类规则抽成纯函数后，两个表单都只剩取问题、弹提示两步。
 */
import { findMissingRequiredEnvParam } from './envBinding';
import type { McpConfigState } from './McpConfigPanel';
import type { SkillConfigState } from './SkillConfigPanel';
import type { ToolConfigState } from './ToolConfigPanel';

type FormatMessage = (
  descriptor: { id: string; defaultMessage?: string },
  values?: Record<string, any>,
) => string;

/** 失败项只说「哪一种、带哪个参数」，句式留在 locale 文件里 */
export type ConfigIssue =
  | { kind: 'tool_not_selected' }
  | { kind: 'mcp_not_selected' }
  | { kind: 'skill_not_selected' }
  | { kind: 'skill_duplicate'; name: string }
  | { kind: 'tool_env_missing'; param: string }
  | { kind: 'mcp_env_missing'; param: string };

export interface ConfigRows {
  toolConfigs: ToolConfigState[];
  mcpConfigs: McpConfigState[];
  skillConfigs: SkillConfigState[];
}

const ISSUE_MESSAGES: Record<ConfigIssue['kind'], { id: string; defaultMessage: string }> = {
  tool_not_selected: {
    id: 'pages.agent.tool.notSelected',
    defaultMessage: 'Please select a tool or remove the empty row',
  },
  mcp_not_selected: {
    id: 'pages.agent.mcp.notSelected',
    defaultMessage: 'Please select an MCP service or remove the empty row',
  },
  skill_not_selected: {
    id: 'pages.agent.skill.notSelected',
    defaultMessage: 'Please select a skill or remove the empty row',
  },
  skill_duplicate: {
    id: 'pages.agent.skill.duplicate',
    defaultMessage: 'Skill {name} is already added',
  },
  tool_env_missing: {
    id: 'pages.agent.tool.envRequired',
    defaultMessage: 'Required env param is empty: ',
  },
  mcp_env_missing: {
    id: 'pages.agent.mcp.envRequired',
    defaultMessage: 'Required env param is empty: ',
  },
};

/** 向导里工具 / MCP / 技能各占一步，第 0 步和第 4 步不涉及这三类 */
export const TOOL_STEP = 1;
export const MCP_STEP = 2;
export const SKILL_STEP = 3;

/**
 * 返回第一个问题，`null` 表示可以往下走。
 *
 * 传 `'all'` 是点「完成」时的口径：三类配置会一起提交，后端按同一套规则逐类拒绝，
 * 事前只挡住当前步等于把另两类的报错留给一次 500。
 */
export function findConfigIssue(step: number | 'all', rows: ConfigRows): ConfigIssue | null {
  const check = (target: number) => step === 'all' || step === target;
  if (check(TOOL_STEP)) {
    const issue = findToolIssue(rows.toolConfigs);
    if (issue) return issue;
  }
  if (check(MCP_STEP)) {
    const issue = findMcpIssue(rows.mcpConfigs);
    if (issue) return issue;
  }
  if (check(SKILL_STEP)) {
    const issue = findSkillIssue(rows.skillConfigs);
    if (issue) return issue;
  }
  return null;
}

export function describeConfigIssue(issue: ConfigIssue, formatMessage: FormatMessage): string {
  const descriptor = ISSUE_MESSAGES[issue.kind];
  switch (issue.kind) {
    // 这两条的句式是「前缀 + 参数名」，与后端拒绝时的说法对齐
    case 'tool_env_missing':
    case 'mcp_env_missing':
      return formatMessage(descriptor) + issue.param;
    case 'skill_duplicate':
      return formatMessage(descriptor, { name: issue.name });
    default:
      return formatMessage(descriptor);
  }
}

function findToolIssue(configs: ToolConfigState[]): ConfigIssue | null {
  for (const c of configs) {
    // 整行没填就当没这一行，空行是「添加」按钮留下的，不是漏填
    if (!c.toolId && (!c.envBindings || c.envBindings.length === 0)) continue;
    if (!c.toolId) return { kind: 'tool_not_selected' };
    const missing = findMissingRequiredEnvParam(c.envEntries, c.envBindings, false);
    if (missing) return { kind: 'tool_env_missing', param: missing };
  }
  return null;
}

function findMcpIssue(configs: McpConfigState[]): ConfigIssue | null {
  for (const c of configs) {
    if (!c.mcpId && (!c.envBindings || c.envBindings.length === 0)) continue;
    if (!c.mcpId) return { kind: 'mcp_not_selected' };
    const missing = findMissingRequiredEnvParam(c.envEntries, c.envBindings, true);
    if (missing) return { kind: 'mcp_env_missing', param: missing };
  }
  return null;
}

function findSkillIssue(configs: SkillConfigState[]): ConfigIssue | null {
  const seen = new Set<number>();
  for (const c of configs) {
    if (!c.repositoryId && !c.skillId) continue;
    if (!c.skillId) return { kind: 'skill_not_selected' };
    // 同一条技能在别处已经加过。picker 已经不再把已选项列出来，这里挡的是
    // 编辑态从库里读回来的重复绑定，以及用户回退改选时留下的旧值
    if (seen.has(c.skillId)) return { kind: 'skill_duplicate', name: c.skillName || String(c.skillId) };
    seen.add(c.skillId);
  }
  return null;
}
