/**
 * @name umi 的路由配置
 * @description 只支持 path,component,routes,redirect,wrappers,name,icon 的配置
 * @param path  path 只支持两种占位符配置，第一种是动态参数 :id 的形式，第二种是 * 通配符，通配符只能出现路由字符串的最后。
 * @param component 配置 location 和 path 匹配后用于渲染的 React 组件路径。可以是绝对路径，也可以是相对路径，如果是相对路径，会从 src/pages 开始找起。
 * @param routes 配置子路由，通常在需要为多个路径增加 layout 组件时使用。
 * @param redirect 配置路由跳转
 * @param wrappers 配置路由组件的包装组件，通过包装组件可以为当前的路由组件组合进更多的功能。比如，可以用于路由级别的权限校验
 * @param name 配置路由的标题，默认读取国际化文件 menu.ts 中 menu.xxxx 的值，如配置 name 为 login，则读取 menu.ts 中 menu.login 的取值作为标题
 * @param icon 配置路由的图标，取值参考 https://ant.design/components/icon-cn，注意去除风格后缀和大小写，如想要配置图标为 <StepBackwardOutlined /> 则取值应为 stepBackward 或 StepBackward，如想要配置图标为 <UserOutlined /> 则取值应为 user 或者 User
 * @doc https://umijs.org/docs/guides/routes
 */
export default [
  {
   path: '/login',
    layout: false,
    routes: [
      {
       name: 'login',
       path: '/login',
       component: './user/login',
      },
    ],
  },
  {
   path: '/welcome',
   name: 'dashboard',
   icon: 'smile',
   component: './Welcome',
  },
  {
    name: 'agent',
    icon: 'robot',
    path: '/agent',
    routes: [
      {
        name: 'management',
        path: '/agent/manager',
        component: './agent',
      },
      {
        name: 'team',
        icon: 'team',
        path: '/agent/team',
        component: './team',
      },
      {
        name: 'session',
        path: '/agent/session',
        component: './session',
      },
      {
        name: 'task',
        icon: 'schedule',
        path: '/agent/task',
        component: './agent-task',
      },
    ],
  },
  {
    name: 'context',
    icon: 'appstore',
    path: '/context',
    routes: [
      {
        name: 'model',
        icon: 'robot',
        path: '/context/model',
        component: './model',
      },
      {
        name: 'tool',
        icon: 'tool',
        path: '/context/tool',
        component: './tool',
      },
      {
        name: 'mcp',
        icon: 'api',
        path: '/context/mcp',
        component: './mcp',
      },
      {
        path: '/context/mcp/detail/:id',
        component: './mcp/detail',
        hideInMenu: true,
      },
      {
        name: 'skill',
        icon: 'thunderbolt',
        path: '/context/skill',
        component: './skill',
      },
      {
        path: '/context/skill/detail/:id',
        component: './skill/detail',
        hideInMenu: true,
      },
      {
        // Usage stays a monitoring concern under Cluster Monitoring; this old address keeps working
        path: '/context/skill-usage',
        redirect: '/monitor/skill-usage',
        hideInMenu: true,
      },
      {
        name: 'cli',
        icon: 'code',
        path: '/context/cli',
        component: './cli',
      },
      {
        // 菜单已迁至系统管理，旧地址保活
        path: '/context/channel',
        redirect: '/system/channel',
        hideInMenu: true,
      },
    ],
  },
  {
    // Both queues here feed the agents' own improvement loop: what they remember and what
    // they propose as new skills. Neither is a capability to bind, so they don't sit in context.
    name: 'optimization',
    icon: 'bulb',
    path: '/optimization',
    routes: [
      {
        // A conversation's merge only reaches an agent's long-term layer once its owner says so,
        // so the approval queue is listed before the page that shows what those layers hold
        name: 'memory.drafts',
        icon: 'audit',
        path: '/optimization/memory/drafts',
        component: './memory/drafts',
      },
      {
        path: '/optimization/memory/draft/detail/:id',
        component: './memory/draftDetail',
        hideInMenu: true,
      },
      {
        // Self-service compliance page: every signed-in user must be able to see and purge
        // what their own agents remember, so this carries no access rule
        name: 'memory',
        icon: 'book',
        path: '/optimization/memory',
        component: './memory',
      },
      {
        name: 'skill.drafts',
        icon: 'audit',
        path: '/optimization/skill-drafts',
        component: './skill/drafts',
      },
      {
        path: '/optimization/skill-draft/detail/:id',
        component: './skill/draftDetail',
        hideInMenu: true,
      },
    ],
  },
  {
    name: 'monitor',
    icon: 'dashboard',
    path: '/monitor',
    routes: [
      {
        name: 'skill.usage',
        path: '/monitor/skill-usage',
        component: './skill/usage',
      },
      {
        name: 'call.metrics',
        path: '/monitor/call-metrics',
        component: './call-metrics',
      },
      {
        name: 'token.monitor',
        path: '/monitor/token-monitor',
        component: './token-monitor',
      },
    ],
  },
  {
    name: 'system',
    icon: 'setting',
    path: '/system',
    routes: [
      {
        name: 'user.management',
        path: '/system/user',
        component: './user/management',
        access: 'canAccessUserManagement',
      },
      {
        name: 'tenant.management',
        path: '/system/tenant',
        component: './tenant/management',
        access: 'canAccessUserManagement',
      },
      {
        // Token 监控已迁至「集群监控」，旧地址保活
        path: '/system/token-monitor',
        redirect: '/monitor/token-monitor',
        hideInMenu: true,
      },
      {
        name: 'apikey.management',
        path: '/system/api-key',
        component: './api-key',
        access: 'canAccessUserManagement',
      },
      {
        name: 'env.management',
        path: '/system/env-variable',
        component: './env-variable',
      },
      {
        name: 'channel',
        icon: 'api',
        path: '/system/channel',
        component: './channel',
      },
    ],
  },
  {
    path: '/',
   redirect: '/welcome',
  },
  {
    // 授权服务器回跳的落地页：不套布局，页面读走 query 后自己抹掉，再调带登录态的换票接口
    path: '/mcp/oauth/callback',
    layout: false,
    component: './mcp/oauth-callback',
  },
  {
   path: '*',
    layout: false,
   component: './404',
  },
];
