import type { ProLayoutProps } from '@ant-design/pro-components';

/**
 * Harnax 全局配置
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
  title: 'Harnax',
  pwa: true,
  logo: '/logo.svg',
  iconfontUrl: '',
  token: {
    // 头部配置
    header: {
      colorBgHeader: 'rgba(255, 255, 255, 0.72)',
      colorHeaderTitle: '#1a1a2e',
      colorTextMenu: '#4a4a6a',
      colorTextMenuSelected: '#4f6ef7',
      colorBgMenuItemSelected: 'rgba(238, 241, 254, 0.6)',
      colorBgMenuItemHover: 'rgba(245, 247, 255, 0.6)',
      heightLayoutHeader: 64,
    },
    
    // 侧边栏配置
    sider: {
      colorMenuBackground: 'transparent',
      colorTextMenu: '#4a4a6a',
      colorTextMenuSelected: '#4f6ef7',
      colorBgMenuItemSelected: 'rgba(238, 241, 254, 0.6)',
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
