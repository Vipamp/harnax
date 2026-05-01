import React, { createContext, useContext } from 'react';

/**
 * 主题模式类型
 * - light: 浅色模式
 * - dark: 深色模式
 * - auto: 自动模式(跟随系统)
 */
export type ThemeMode = 'light' | 'dark' | 'auto';

/**
 * 实际主题类型(解析后的)
 */
export type ActualTheme = 'light' | 'dark';

/**
 * ThemeContext 接口
 */
export interface ThemeContextType {
  /** 用户选择的主题模式 */
  theme: ThemeMode;
  /** 解析后的实际主题 */
  actualTheme: ActualTheme;
  /** 切换主题模式 */
  setTheme: (theme: ThemeMode) => void;
}

/**
 * 创建 ThemeContext
 */
export const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

/**
 * 使用 ThemeContext 的 Hook
 * 如果不在 ThemeProvider 中,返回默认值
 */
export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  if (!context) {
    // 返回默认值而不是抛出错误
    return {
      theme: 'light' as ThemeMode,
      actualTheme: 'light' as ActualTheme,
      setTheme: () => {},
    };
  }
  return context;
};
