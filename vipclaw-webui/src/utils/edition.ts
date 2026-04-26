/**
 * 版本工具类
 * 用于在代码中判断当前打包的版本
 */

// 从环境变量获取版本(打包时确定)
export const EDITION = process.env.REACT_APP_EDITION || 'personal';

export const EDITION_PERSONAL = 'personal';
export const EDITION_ENTERPRISE = 'enterprise';
export const EDITION_PUBLIC = 'public';

/**
 * 判断是否是个人版
 */
export const isPersonal = (): boolean => EDITION === EDITION_PERSONAL;

/**
 * 判断是否是企业版
 */
export const isEnterprise = (): boolean => EDITION === EDITION_ENTERPRISE;

/**
 * 判断是否是公网版
 */
export const isPublic = (): boolean => EDITION === EDITION_PUBLIC;
