import type { ProLayoutProps } from '@ant-design/pro-components';

/**
 * VipClaw 全局配置
 * 设计方向：现代科技感 + 精致商务风
 */
const Settings: ProLayoutProps & {
  pwa?: boolean;
  logo?: string;
} = {
  navTheme: 'light',
  // 科技蓝紫渐变主色
  colorPrimary: '#4f6ef7',
  layout: 'side',
  contentWidth: 'Fluid',
  fixedHeader: true,
  fixSiderbar: true,
  colorWeak: false,
  title: 'VipClaw',
  pwa: true,
  logo: '/logo.svg',
  iconfontUrl: '',
  token: {
    // 头部配置
    header: {
      colorBgHeader: '#ffffff',
      colorHeaderTitle: '#1a1a2e',
      colorTextMenu: '#4a4a6a',
      colorTextMenuSelected: '#4f6ef7',
      colorBgMenuItemSelected: '#eef1fe',
      colorBgMenuItemHover: '#f5f7ff',
      heightLayoutHeader: 64,
    },
    
    // 侧边栏配置
    sider: {
      colorMenuBackground: '#ffffff',
      colorTextMenu: '#4a4a6a',
      colorTextMenuSelected: '#4f6ef7',
      colorBgMenuItemSelected: '#eef1fe',
      colorTextMenuItemHover: '#4f6ef7',
    },
    
    // 页面容器配置
    pageContainer: {
      paddingInlinePageContainerContent: 24,
      paddingBlockPageContainerContent: 24,
    },
  },
};

export default Settings;
