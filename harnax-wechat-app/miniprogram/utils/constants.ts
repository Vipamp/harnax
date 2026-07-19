// utils/constants.ts
// 全局常量定义

/** 本地存储 Key */
export const STORAGE_KEYS = {
  BASE_URL: 'serverBaseUrl',
  ACCESS_TOKEN: 'accessToken',
  TOKEN_INFO: 'tokenInfo',
  USER_INFO: 'userInfo',
};

/** 默认服务器地址 */
export const DEFAULT_BASE_URL = 'https://harnax.example.com';

/** 分页默认每页条数 */
export const PAGE_SIZE = 20;

/** 通用状态 */
export const STATUS = {
  ENABLED: 1,
  DISABLED: 0,
};

/** 状态文本映射 */
export const STATUS_TEXT: Record<number, string> = {
  1: '启用',
  0: '禁用',
};

/** Agent 任务状态 */
export const TASK_STATUS_TEXT: Record<number, string> = {
  1: '运行中',
  0: '已停止',
};

/** 任务执行结果状态 */
export const RUN_STATUS_TEXT: Record<number, string> = {
  0: '待执行',
  1: '成功',
  2: '失败',
  3: '执行中',
};

/** 模型类型选项 */
export const MODEL_TYPE_OPTIONS = [
  { label: '对话模型', value: 'chat' },
  { label: '推理模型', value: 'reasoning' },
  { label: '视觉模型', value: 'vision' },
  { label: '嵌入模型', value: 'embedding' },
];

/** 服务商类型选项 */
export const PROVIDER_TYPE_OPTIONS = [
  { label: 'OpenAI', value: 'openai' },
  { label: 'DeepSeek', value: 'deepseek' },
  { label: '通义千问', value: 'qwen' },
  { label: '智谱 GLM', value: 'zhipu' },
  { label: 'Anthropic', value: 'anthropic' },
  { label: '其他', value: 'other' },
];

/** 渠道类型选项 */
export const CHANNEL_TYPE_OPTIONS = [
  { label: '微信公众号', value: 'wechat' },
  { label: '飞书', value: 'feishu' },
];
