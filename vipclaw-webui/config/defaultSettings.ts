import type { ProLayoutProps } from '@ant-design/pro-components';

/**
 * @name
 */
const Settings: ProLayoutProps & {
  pwa?: boolean;
  logo?: string;
} = {
  navTheme: 'light',
  // 科技蓝紫渐变主色
  colorPrimary: '#4f6ef7',
  layout: 'side',  // 使用侧边菜单布局
  contentWidth: 'Fluid',
  fixedHeader: true,
  fixSiderbar: true,
  colorWeak: false,
  title: 'VipClaw',
  pwa: true,
  logo: 'https://gw.alipayobjects.com/zos/rmsportal/KDpgvguMpGfqaHPjicRK.svg',
  iconfontUrl: '',
  token: {
    // 通过 token 统一调整布局样式
    // https://procomponents.ant.design/components/layout#%E9%80%9A%E8%BF%87-token-%E4%BF%AE%E6%94%B9%E6%A0%B7%E5%BC%8F
    header: {
      colorBgHeader: '#fff',
      colorHeaderTitle: '#1a1a2e',
      colorTextMenu: '#4a4a6a',
      colorTextMenuSelected: '#4f6ef7',
      colorBgMenuItemSelected: '#eef1fe',
      heightLayoutHeader: 56,
    },
    sider: {
      colorMenuBackground: '#fff',
      colorTextMenu: '#4a4a6a',
      colorTextMenuSelected: '#4f6ef7',
      colorBgMenuItemSelected: '#eef1fe',
      colorTextMenuItemHover: '#4f6ef7',
    },
    pageContainer: {
      paddingInlinePageContainerContent: 24,
      paddingBlockPageContainerContent: 20,
    },
  },
};

export default Settings;
