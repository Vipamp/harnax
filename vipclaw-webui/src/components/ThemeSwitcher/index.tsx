import React, { useState } from 'react';
import { Dropdown, MenuProps, Tooltip } from 'antd';
import { SunOutlined, MoonOutlined, DesktopOutlined } from '@ant-design/icons';
import { useTheme, ThemeMode } from '@/contexts/ThemeContext';

/**
 * 主题切换组件
 * 在顶部导航栏显示当前主题图标,点击可切换主题
 */
const ThemeSwitcher: React.FC = () => {
  const { theme, actualTheme, setTheme } = useTheme();
  const [isRotating, setIsRotating] = useState(false);

  // 获取当前主题对应的图标
  const getThemeIcon = () => {
    switch (theme) {
      case 'light':
        return <SunOutlined />;
      case 'dark':
        return <MoonOutlined />;
      case 'auto':
        return <DesktopOutlined />;
      default:
        return <SunOutlined />;
    }
  };

  // 获取当前主题的提示文字
  const getTooltipTitle = () => {
    switch (theme) {
      case 'light':
        return '浅色模式';
      case 'dark':
        return '深色模式';
      case 'auto':
        return '自动模式';
      default:
        return '切换主题';
    }
  };

  // 主题切换菜单
  const items: MenuProps['items'] = [
    {
      key: 'light',
      label: '浅色模式',
      icon: <SunOutlined />,
    },
    {
      key: 'dark',
      label: '深色模式',
      icon: <MoonOutlined />,
    },
    {
      key: 'auto',
      label: '自动模式',
      icon: <DesktopOutlined />,
    },
  ];

  // 处理菜单点击
  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    if (key !== theme) {
      // 触发旋转动画
      setIsRotating(true);
      setTimeout(() => setIsRotating(false), 300);
    }
    setTheme(key as ThemeMode);
  };

  return (
    <Tooltip title={getTooltipTitle()}>
      <Dropdown
        menu={{
          items,
          onClick: handleMenuClick,
          selectedKeys: [theme],
        }}
        placement="bottomRight"
        trigger={['click']}
      >
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 32,
            height: 32,
            cursor: 'pointer',
            borderRadius: 6,
            transition: 'background-color 0.2s',
            fontSize: 18,
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.backgroundColor = 'rgba(0, 0, 0, 0.06)';
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.backgroundColor = 'transparent';
          }}
        >
          <span
            style={{
              display: 'inline-flex',
              transition: 'transform 0.3s cubic-bezier(0.68, -0.55, 0.265, 1.55)',
              transform: isRotating ? 'rotate(360deg)' : 'rotate(0deg)',
            }}
          >
            {getThemeIcon()}
          </span>
        </span>
      </Dropdown>
    </Tooltip>
  );
};

export default ThemeSwitcher;
