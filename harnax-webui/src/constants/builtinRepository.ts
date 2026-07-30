/**
 * 平台内置技能仓库名，需与后端 BuiltinRepository.CLI_SKILLS 保持一致。
 *
 * 该仓库的技能只能通过 CLI 关联下发：
 * - CLI 表单中仅可从该仓库选择技能
 * - agent 表单中该仓库不可选（技能经 CLI 自动加载）
 * - 仓库本身及其技能在管理端只读
 */
export const BUILTIN_CLI_SKILL_REPO = 'builtin-cli-skills';
