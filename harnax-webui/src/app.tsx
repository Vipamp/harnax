import { LinkOutlined, MenuOutlined } from '@ant-design/icons';
import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import { Button, Space, Typography } from 'antd';
import React, { useState } from 'react';
import { AvatarDropdown, AvatarName, Footer, Question, SelectLang, ThemeSwitcher } from '@/components';
import TenantSwitcher from '@/components/TenantSwitcher';
import { ThemeProvider } from '@/contexts/ThemeProvider';
import { useIsMobile } from '@/utils/responsive';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';
import '@ant-design/v5-patch-for-react-19';

const isDev = process.env.NODE_ENV === 'development';
const loginPath = '/login';

/**
 * @see https://umijs.org/docs/api/runtime-config#getinitialstate
 * */
export async function getInitialState(): Promise<{
 settings?: Partial<LayoutSettings>;
 currentUser?: API.CurrentUser;
 loading?: boolean;
}> {
  // 从 localStorage 读取用户信息（刷新后恢复）
 let currentUser: API.CurrentUser | undefined;
 try {
 const stored = localStorage.getItem('currentUser');
  if (stored) {
   currentUser = JSON.parse(stored);
   
    // 检查 token 是否过期
 const tokenInfoStr = localStorage.getItem('tokenInfo');
  if (tokenInfoStr) {
 const tokenInfo = JSON.parse(tokenInfoStr);
  if (tokenInfo.expiresAt) {
   const now = Date.now();
    if (now >= tokenInfo.expiresAt) {
       // token 已过期，清除登录信息
    console.warn('Token 已过期，自动退出登录');
    localStorage.removeItem('currentUser');
    localStorage.removeItem('tokenInfo');
      currentUser= undefined;
      
       // 如果不在登录页，跳转到登录页
   const { location } = history;
   if (location.pathname !== '/login') {
     history.replace({
      pathname: '/login',
     search: `?redirect=${encodeURIComponent(location.pathname + location.search)}`,
       });
     }
    }
   }
  }
 }
 } catch (e) {
 console.error('读取本地存储失败:', e);
 }
 
  // 如果没有登录且不在登录页，跳转到登录页
 const { location } = history;
 if (!currentUser && location.pathname !== loginPath) {
   history.replace({
     pathname: loginPath,
     search: `?redirect=${encodeURIComponent(location.pathname + location.search)}`,
   });
   // 确保返回空的用户信息
   currentUser = undefined;
 }

 return {
  currentUser,
  settings: defaultSettings as Partial<LayoutSettings>,
 };
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  const isMobile = useIsMobile();
  const [collapsed, setCollapsed] = useState(true);

  return {
    // 移动端使用 breakpoint 控制侧边栏抽屉模式
    breakpoint: 'md',
    collapsed,
    onCollapse: setCollapsed,
    actionsRender: () => {
      if (isMobile) {
        return [
          <ThemeSwitcher key="ThemeSwitcher" />,
        ];
      }
      return [
        <TenantSwitcher key="TenantSwitcher" />,
        <ThemeSwitcher key="ThemeSwitcher" />,
        <Question key="doc" />,
        <SelectLang key="SelectLang" />,
      ];
    },
    // 移动端 header 左侧汉堡菜单按钮
    headerTitleRender: (logo, title) => {
      if (isMobile) {
        return (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Button
              type="text"
              icon={<MenuOutlined />}
              onClick={() => setCollapsed(!collapsed)}
              size="small"
              style={{ color: 'var(--vip-text-primary)' }}
            />
            {logo}
            {title}
          </div>
        );
      }
      return (
        <>
          {logo}
          {title}
        </>
      );
    },
    // 顶部右侧显示用户头像和登录信息
    avatarProps: {
      src: initialState?.currentUser?.avatar,
      title: <AvatarName />,
      render: (_, avatarChildren) => {
        return <AvatarDropdown>{avatarChildren}</AvatarDropdown>;
      },
    },
    waterMarkProps: {
      content: initialState?.currentUser?.name,
    },
    footerRender: false,
    onPageChange: () => {
      const { location } = history;
      // 如果没有登录，重定向到 login
      if (!initialState?.currentUser && location.pathname !== loginPath) {
        history.push(loginPath);
      }
    },
    // 菜单配置
    menu: {
      // 默认展开所有菜单
      autoOpen: true,
    },
    // 移动端背景装饰图隐藏
    bgLayoutImgList: isMobile
      ? []
      : [
          {
            src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/D2LWSqNny4sAAAAAAAAAAAAAFl94AQBr',
            left: 85,
            bottom: 100,
            height: '303px',
          },
          {
            src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/C2TWRpJpiC0AAAAAAAAAAAAAFl94AQBr',
            bottom: -68,
            right: -45,
            height: '303px',
          },
          {
            src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/F6vSTbj8KpYAAAAAAAAAAAAAFl94AQBr',
            bottom: 0,
            left: 0,
            width: '331px',
          },
        ],
    links: isDev
      ? [
          <Link key="openapi" to="/umi/plugin/openapi" target="_blank">
            <LinkOutlined />
            <span>OpenAPI 文档</span>
          </Link>,
        ]
      : [],
    menuHeaderRender: undefined,
    // 自定义 403 页面
    // unAccessible: <div>unAccessible</div>,
    // 增加一个 loading 的状态
    childrenRender: (children) => {
      // if (initialState?.loading) return <PageLoading />;
      return (
        <>
          {children}
          {isDev && (
            <SettingDrawer
              disableUrlParams
              enableDarkTheme
              settings={initialState?.settings}
              onSettingChange={(settings) => {
                setInitialState((preInitialState) => ({
                  ...preInitialState,
                  settings,
                }));
              }}
            />
          )}
        </>
      );
    },
    ...initialState?.settings,
  };
};

/**
 * @name request 配置，可以配置错误处理
 * 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request: RequestConfig = {
  ...errorConfig,
};

/**
 * 根容器配置,包裹整个应用
 * @doc https://umijs.org/docs/api/runtime-config#rootcontainer
 */
export function rootContainer(container: React.ReactNode) {
  return <ThemeProvider>{container}</ThemeProvider>;
}
