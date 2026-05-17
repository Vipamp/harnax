import React, { useState, useEffect, useCallback } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import { ThemeContext, ThemeMode, ActualTheme } from './ThemeContext';

/**
 * localStorage 键名
 */
const THEME_STORAGE_KEY = 'harnax-theme';

/**
 * 获取系统主题偏好
 */
function getSystemThemePreference(): ActualTheme {
  if (typeof window !== 'undefined' && window.matchMedia) {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return 'light';
}

/**
 * 解析主题模式为实际主题
 */
function resolveTheme(theme: ThemeMode): ActualTheme {
  if (theme === 'auto') {
    return getSystemThemePreference();
  }
  return theme;
}

/**
 * 从 localStorage 加载主题偏好
 */
function loadThemePreference(): ThemeMode {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    if (stored && ['light', 'dark', 'auto'].includes(stored)) {
      return stored as ThemeMode;
    }
  } catch (e) {
    console.warn('Failed to load theme preference:', e);
  }
  return 'light'; // 默认浅色
}

/**
 * 保存主题偏好到 localStorage
 */
function saveThemePreference(theme: ThemeMode): void {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch (e) {
    console.warn('Failed to save theme preference:', e);
  }
}

/**
 * ThemeProvider 组件
 * 提供主题状态管理和切换功能
 */
export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [theme, setThemeState] = useState<ThemeMode>(loadThemePreference);
  const [actualTheme, setActualTheme] = useState<ActualTheme>(() => resolveTheme(loadThemePreference()));

  // 监听系统主题变化
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    
    const handleChange = () => {
      if (theme === 'auto') {
        setActualTheme(getSystemThemePreference());
      }
    };

    // 添加监听器
    mediaQuery.addEventListener('change', handleChange);
    
    return () => {
      mediaQuery.removeEventListener('change', handleChange);
    };
  }, [theme]);

  // 当主题模式变化时,更新实际主题
  useEffect(() => {
    const resolved = resolveTheme(theme);
    setActualTheme(resolved);
  }, [theme]);

  // 应用主题到 DOM
  useEffect(() => {
    const root = document.documentElement;
    if (actualTheme === 'dark') {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
  }, [actualTheme]);

  // 切换主题
  const handleSetTheme = useCallback((newTheme: ThemeMode) => {
    setThemeState(newTheme);
    saveThemePreference(newTheme);
  }, []);

  const contextValue = {
    theme,
    actualTheme,
    setTheme: handleSetTheme,
  };

  // Ant Design 主题配置
  const antdThemeConfig = {
    algorithm: actualTheme === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: '#5c7cff',
    },
  };

  return (
    <ThemeContext.Provider value={contextValue}>
      <ConfigProvider theme={antdThemeConfig}>
        {children}
      </ConfigProvider>
    </ThemeContext.Provider>
  );
};
