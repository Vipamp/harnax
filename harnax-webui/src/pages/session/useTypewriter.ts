import { useEffect, useMemo, useState } from 'react';

/** 打字机节奏：中文（含全角标点）较慢、拉丁较快，避免英文标题拖沓 */
export const TYPE_STEP_CJK_MS = 90;
export const TYPE_STEP_LATIN_MS = 45;
export const TYPE_START_DELAY_MS = 300;
export const TYPE_CARET_HOLD_MS = 1500;
export const TYPE_CARET_FADE_MS = 420;

const CJK_RE = /[\u2e80-\u9fff\uff00-\uffef]/;

export function typewriterStepMs(ch: string): number {
  return CJK_RE.test(ch) ? TYPE_STEP_CJK_MS : TYPE_STEP_LATIN_MS;
}

export type TypeCaretPhase = 'typing' | 'holding' | 'fading' | 'gone';

export interface TypewriterState {
  typed: string;
  done: boolean;
  caret: TypeCaretPhase;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

/**
 * 标题逐字打出（单次；打字中光标实心、完成后闪烁片刻再淡出）。
 * 系统「减少动态效果」偏好下直出全文且不渲染光标。
 */
export function useTypewriter(text: string): TypewriterState {
  const chars = useMemo(() => Array.from(text), [text]);
  const reduce = useMemo(prefersReducedMotion, []);
  const [count, setCount] = useState(() => (reduce ? chars.length : 0));
  const [caret, setCaret] = useState<TypeCaretPhase>(() => (reduce ? 'gone' : 'typing'));

  useEffect(() => {
    if (reduce) {
      setCount(chars.length);
      setCaret('gone');
      return;
    }
    if (chars.length === 0) {
      setCaret('gone');
      return;
    }
    setCount(0);
    setCaret('typing');
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let i = 0;
    const step = () => {
      if (!alive) return;
      i += 1;
      setCount(i);
      if (i >= chars.length) {
        setCaret('holding');
        return;
      }
      timer = setTimeout(step, typewriterStepMs(chars[i]));
    };
    timer = setTimeout(step, TYPE_START_DELAY_MS);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [chars, reduce]);

  useEffect(() => {
    if (caret === 'holding') {
      const holdTimer = setTimeout(() => setCaret('fading'), TYPE_CARET_HOLD_MS);
      return () => clearTimeout(holdTimer);
    }
    if (caret === 'fading') {
      const fadeTimer = setTimeout(() => setCaret('gone'), TYPE_CARET_FADE_MS);
      return () => clearTimeout(fadeTimer);
    }
  }, [caret]);

  return { typed: chars.slice(0, count).join(''), done: count >= chars.length, caret };
}
