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

/**
 * 版本功能配置
 * 根据后端配置文件同步,控制前端功能显示
 */
export const FEATURES = {
  // 用户管理 (企业版+公有云版)
  userManagement: EDITION !== EDITION_PERSONAL,
  
  // Token 监控 (企业版+公有云版)
  tokenMonitor: EDITION !== EDITION_PERSONAL,
  
  // Agent 共享 (企业版+公有云版)
  agentSharing: EDITION !== EDITION_PERSONAL,
  
  // 手机登录 (企业版+公有云版)
  phoneLogin: EDITION !== EDITION_PERSONAL,
  
  // 邮箱登录 (企业版+公有云版)
  emailLogin: EDITION !== EDITION_PERSONAL,
  
  // 多租户 (仅公有云版)
  multiTenant: EDITION === EDITION_PUBLIC,
  
  // 计费管理 (仅公有云版)
  billing: EDITION === EDITION_PUBLIC,
  
  // 模型市场 (仅公有云版)
  modelMarket: EDITION === EDITION_PUBLIC,
  
  // 技能市场 (仅公有云版)
  skillMarket: EDITION === EDITION_PUBLIC,
};

/**
 * 根据版本过滤路由
 * 从路由配置中移除当前版本不支持的路由
 * 
 * @param routes 路由配置数组
 * @returns 过滤后的路由配置数组
 */
export function filterRoutesByEdition(routes: any[]): any[] {
  return routes
    .map(route => {
      // 如果路由有 edition 属性,检查当前版本是否支持
      if (route.edition && Array.isArray(route.edition)) {
        if (!route.edition.includes(EDITION)) {
          // 当前版本不支持,过滤掉
          return null;
        }
      }
      
      // 递归处理子路由
      if (route.routes && route.routes.length > 0) {
        const filteredRoutes = filterRoutesByEdition(route.routes);
        return {
          ...route,
          routes: filteredRoutes,
        };
      }
      
      // 递归处理 children
      if (route.children && route.children.length > 0) {
        const filteredChildren = filterRoutesByEdition(route.children);
        return {
          ...route,
          children: filteredChildren,
        };
      }
      
      return route;
    })
    .filter(route => route !== null); // 移除被过滤掉的路由
}
