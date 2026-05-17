/**
 * 权限判断工具函数
 * 用于控制"是否公开"字段的编辑权限
 */

/**
 * 判断当前用户是否可以编辑"是否公开"字段
 * 
 * 权限规则：
 * 1. 管理员：可以设置任何实体的 isPublic
 * 2. 非管理员：
 *    - 创建时：可以设置 isPublic
 *    - 编辑时：
 *      - 如果是自己创建的：可以设置 isPublic
 *      - 如果不是自己创建的：不能修改 isPublic
 * 
 * @param isAdmin 当前用户是否是管理员
 * @param currentUser 当前用户名
 * @param creator 实体的创建人
 * @param isCreate 是否是创建模式
 * @returns 是否可以编辑
 */
export const canEditIsPublic = (
  isAdmin: boolean,
  currentUser: string | undefined,
  creator: string | undefined,
  isCreate: boolean
): boolean => {
  // 管理员可以编辑
  if (isAdmin) {
    return true;
  }
  
  // 创建模式可以编辑
  if (isCreate) {
    return true;
  }
  
  // 编辑模式：只有自己创建的才能编辑
  return currentUser === creator;
};

/**
 * 判断"是否公开"开关是否应该被禁用
 * 
 * 权限规则：
 * 1. 如果不能编辑，则禁用
 * 2. 非管理员编辑已公开的实体时，不能改为非公开（开关禁用）
 * 
 * @param isAdmin 当前用户是否是管理员
 * @param currentUser 当前用户名
 * @param creator 实体的创建人
 * @param currentIsPublic 当前是否公开的值
 * @param isCreate 是否是创建模式
 * @returns 开关是否应该禁用
 */
export const isPublicSwitchDisabled = (
  isAdmin: boolean,
  currentUser: string | undefined,
  creator: string | undefined,
  currentIsPublic: number | undefined,
  isCreate: boolean
): boolean => {
  // 如果不能编辑，则禁用
  if (!canEditIsPublic(isAdmin, currentUser, creator, isCreate)) {
    return true;
  }
  
  // 非管理员编辑已公开的实体时，不能改为非公开
  if (!isAdmin && !isCreate && currentIsPublic === 1) {
    return true;
  }
  
  return false;
};

/**
 * 从 localStorage 获取当前用户信息
 */
export const getCurrentUserInfo = (): { 
  username: string | undefined; 
  isAdmin: boolean; 
} => {
  try {
    const stored = localStorage.getItem('currentUser');
    if (stored) {
      const user = JSON.parse(stored);
      return {
        username: user.username || user.nickname,
        isAdmin: user.isAdmin === 1,
      };
    }
  } catch (e) {
    console.error('获取当前用户信息失败:', e);
  }
  return { username: undefined, isAdmin: false };
};

/**
 * 判断当前用户是否有操作权限（编辑、删除、启停）
 * 
 * 权限规则：
 * 1. 管理员：可以操作任何实体
 * 2. 非管理员：只能操作自己创建的实体
 * 
 * @param isAdmin 当前用户是否是管理员
 * @param currentUser 当前用户名
 * @param creator 实体的创建人
 * @returns 是否有操作权限
 */
export const hasOperationPermission = (
  isAdmin: boolean,
  currentUser: string | undefined,
  creator: string | undefined
): boolean => {
  // 管理员可以操作任何实体
  if (isAdmin) {
    return true;
  }
  
  // 非管理员只能操作自己创建的实体
  return currentUser === creator;
};
