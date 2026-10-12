/**
 * 手风琴归一：ProLayout 会把「路由匹配到的菜单链」整条塞进 openKeys（父组在前、叶子在后），
 * 换组点击又可能一次带来两个键。规则＝只留本次新出现的第一个键；若它是某组的子键，保留该组。
 */
export const nextOpenKeys = (previous: string[], next: string[]): string[] => {
  const opened = next.find((key) => !previous.includes(key));
  if (!opened) return [];
  const group = next.find((other) => other !== opened && opened.startsWith(`${other}/`));
  return [group ?? opened];
};
