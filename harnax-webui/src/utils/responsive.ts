import { useCallback, useEffect, useState } from 'react';

/**
 * 响应式断点定义
 */
export const BREAKPOINTS = {
  xs: 480,  // 小屏手机
  sm: 768,  // 手机/平板分界
  md: 1024, // 平板/桌面分界
  lg: 1280, // 大屏桌面
} as const;

export type BreakpointKey = keyof typeof BREAKPOINTS;

/**
 * 获取当前断点名称
 */
export function getBreakpoint(width: number): BreakpointKey {
  if (width < BREAKPOINTS.xs) return 'xs';
  if (width < BREAKPOINTS.sm) return 'sm';
  if (width < BREAKPOINTS.md) return 'md';
  return 'lg';
}

/**
 * 判断是否为移动端（< 768px）
 */
export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(() => {
    if (typeof window === 'undefined') return false;
    return window.innerWidth < BREAKPOINTS.sm;
  });

  useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${BREAKPOINTS.sm - 1}px)`);
    const handler = (e: MediaQueryListEvent) => setIsMobile(e.matches);
    mql.addEventListener('change', handler);
    // 同步初始值
    setIsMobile(mql.matches);
    return () => mql.removeEventListener('change', handler);
  }, []);

  return isMobile;
}

/**
 * 返回当前断点以及各断点布尔值
 */
export function useBreakpoint() {
  const [breakpoint, setBreakpoint] = useState<BreakpointKey>(() => {
    if (typeof window === 'undefined') return 'lg';
    return getBreakpoint(window.innerWidth);
  });

  useEffect(() => {
    const handleResize = () => {
      setBreakpoint(getBreakpoint(window.innerWidth));
    };
    window.addEventListener('resize', handleResize);
    handleResize();
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  return {
    breakpoint,
    isXs: breakpoint === 'xs',
    isSm: breakpoint === 'sm',
    isMd: breakpoint === 'md',
    isLg: breakpoint === 'lg',
    isMobile: breakpoint === 'xs' || breakpoint === 'sm',
    isTablet: breakpoint === 'md',
    isDesktop: breakpoint === 'lg',
  };
}

/**
 * 判断当前宽度是否小于指定断点
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    const mql = window.matchMedia(query);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mql.addEventListener('change', handler);
    setMatches(mql.matches);
    return () => mql.removeEventListener('change', handler);
  }, [query]);

  return matches;
}
