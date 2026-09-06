/**
 * 技能安装结果提示工具。
 *
 * 后端安装是「部分成功」语义：接口返回 200 也可能有技能没落库（源里已删除、内容为空、
 * 写库异常），或被内容安全扫描拦下置为禁用。只看请求成功与否会重现「界面提示成功、
 * 列表却是空的」这个老问题，所以提示级别必须由 install 结果本身决定。
 */
import { message } from 'antd';

type FormatMessage = (
  descriptor: { id: string; defaultMessage?: string },
  values?: Record<string, any>,
) => string;

export interface SkillInstallFeedback {
  /** 提示级别，直接对应 antd message 的方法名 */
  level: 'success' | 'warning' | 'error';
  /** 已渲染好的提示文案 */
  text: string;
}

/** 失败明细最多展示几条，超出部分只显示数量，避免提示框被撑爆 */
const MAX_DETAIL_ITEMS = 3;

/**
 * 把 `SkillInstallResponse` 翻译成一条提示。
 *
 * @param install 后端返回的安装结果，缺省时按「没有任何结果」处理
 * @param formatMessage `intl.formatMessage`，由调用方传入以保持组件内的国际化上下文
 * @param successText 全部成功时的文案，各入口的叫法不同（创建 / 同步 / 重装）
 */
export function describeSkillInstall(
  install: API.SkillInstallResult | undefined | null,
  formatMessage: FormatMessage,
  successText?: string,
): SkillInstallFeedback {
  // 后端总会带上 install，缺失说明响应契约变了；
  // 此时退化成一句通用提示，好过拿 0 去谎报保存数量
  if (!install) {
    return {
      level: 'success',
      text:
        successText ??
        formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }),
    };
  }

  const saved = install.savedCount ?? 0;
  const failed = install.failed ?? [];
  const flagged = install.flagged ?? [];

  if (failed.length > 0 && saved === 0) {
    return {
      level: 'error',
      text: formatMessage(
        { id: 'pages.skill.install.allFailed', defaultMessage: 'No skill was saved: {detail}' },
        { detail: formatFailed(failed, formatMessage) },
      ),
    };
  }

  if (failed.length > 0) {
    return {
      level: 'warning',
      text: formatMessage(
        { id: 'pages.skill.install.partial', defaultMessage: '{saved} saved, {detail}' },
        { saved, detail: formatFailed(failed, formatMessage) },
      ),
    };
  }

  if (flagged.length > 0) {
    return {
      level: 'warning',
      text: formatMessage(
        {
          id: 'pages.skill.install.flagged',
          defaultMessage: '{saved} saved, {count} disabled pending review because the content scan flagged them',
        },
        { saved, count: flagged.length },
      ),
    };
  }

  // 一个都没失败也一个都没存：源里就没有可装的技能（目录结构不对、SKILL.md 缺失等）。
  // 这里必须给警告而不是绿色的「已保存 0 个」，否则又会变成「提示成功、列表是空」。
  if (saved === 0) {
    return {
      level: 'warning',
      text: formatMessage({
        id: 'pages.skill.install.noneFound',
        defaultMessage: 'The source contains no installable skill',
      }),
    };
  }

  return {
    level: 'success',
    text:
      successText ??
      formatMessage(
        { id: 'pages.skill.install.success', defaultMessage: '{saved} skills saved' },
        { saved },
      ),
  };
}

/** 失败明细可能较长，停留时间放宽到 6 秒 */
const FEEDBACK_DURATION = 6;

/**
 * 弹出安装结果提示。
 *
 * 各入口共用，避免在每个组件里重复写 level 到 message 方法的分支。
 */
export function showSkillInstallFeedback(feedback: SkillInstallFeedback): void {
  if (feedback.level === 'error') {
    message.error(feedback.text, FEEDBACK_DURATION);
  } else if (feedback.level === 'warning') {
    message.warning(feedback.text, FEEDBACK_DURATION);
  } else {
    message.success(feedback.text, FEEDBACK_DURATION);
  }
}

/** 失败技能拼成「名称（原因）」，条数超限时补一句「等 N 个」 */
function formatFailed(failed: { name: string; reason?: string }[], formatMessage: FormatMessage): string {
  const shown = failed
    .slice(0, MAX_DETAIL_ITEMS)
    .map((item) => (item.reason ? `${item.name}（${item.reason}）` : item.name))
    .join('、');
  if (failed.length <= MAX_DETAIL_ITEMS) return shown;
  return formatMessage(
    { id: 'pages.skill.install.failedMore', defaultMessage: '{shown} and {rest} more' },
    { shown, rest: failed.length - MAX_DETAIL_ITEMS },
  );
}
