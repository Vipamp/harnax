// utils/skillInstall.ts
/**
 * 技能安装结果提示工具。
 *
 * 后端的安装是「部分成功」语义：接口返回成功也可能有技能没落库（源里已删除、内容为空、
 * 写库异常），或被内容安全扫描拦下后置为禁用。只看请求成功与否会重现「提示成功、列表却是
 * 空的」这个老问题，所以文案必须由 install 结果本身决定。同步技能与创建来源两个入口共用。
 */

export interface SkillInstallFeedback {
  /** 对应 wx.showToast 的 icon：只有全部落库才算 success */
  icon: 'success' | 'none';
  /** 已渲染好的提示文案 */
  title: string;
}

/** 非成功文案信息量更大，停留时间放宽到 6 秒 */
const FEEDBACK_DURATION = 6000;

/**
 * 把后端的 `SkillInstallResponse` 翻译成一条提示。
 *
 * @param install 安装结果
 * @param savedVerb 全部落库时的动词，各入口叫法不同（已导入 / 已保存）
 */
export function describeSkillInstall(
  install: API.SkillInstallResult | undefined | null,
  savedVerb = '已保存',
): SkillInstallFeedback {
  // 后端总会带上 install，缺失说明响应契约变了；此时退化成一句通用提示，
  // 好过拿 0 去谎报保存数量
  if (!install) {
    return { icon: 'success', title: '操作成功' };
  }

  const saved = install.savedCount ?? 0;
  const failed = install.failed?.length ?? 0;
  const flagged = install.flagged?.length ?? 0;

  if (failed > 0 && saved === 0) {
    return { icon: 'none', title: `没有技能保存成功，${failed} 个失败` };
  }
  if (failed > 0) {
    return { icon: 'none', title: `${savedVerb} ${saved} 个，${failed} 个失败` };
  }
  if (flagged > 0) {
    return { icon: 'none', title: `${savedVerb} ${saved} 个，${flagged} 个命中内容扫描被置为待审核` };
  }
  // 一个都没失败也一个都没存：源里就没有可装的技能（目录结构不对、SKILL.md 缺失等）。
  // 这里必须给警告而不是绿色的「已保存 0 个」，否则又变成「提示成功、列表是空的」
  if (saved === 0) {
    return { icon: 'none', title: '来源里没有可安装的技能' };
  }
  return { icon: 'success', title: `${savedVerb} ${saved} 个技能` };
}

/** 弹出安装结果提示，避免每个页面各写一遍 icon 与停留时间的分支 */
export function showSkillInstallToast(feedback: SkillInstallFeedback): void {
  wx.showToast({
    title: feedback.title,
    icon: feedback.icon,
    duration: feedback.icon === 'success' ? 1500 : FEEDBACK_DURATION,
  });
}
