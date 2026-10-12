import { act, renderHook } from '@testing-library/react';
import {
  TYPE_CARET_FADE_MS,
  TYPE_CARET_HOLD_MS,
  TYPE_START_DELAY_MS,
  TYPE_STEP_CJK_MS,
  TYPE_STEP_LATIN_MS,
  typewriterStepMs,
  useTypewriter,
} from './useTypewriter';

describe('typewriterStepMs', () => {
  it('types CJK characters at the slower cadence', () => {
    expect(typewriterStepMs('今')).toBe(TYPE_STEP_CJK_MS);
  });

  it('treats fullwidth punctuation like CJK', () => {
    expect(typewriterStepMs('？')).toBe(TYPE_STEP_CJK_MS);
  });

  it('types latin characters at the faster cadence', () => {
    expect(typewriterStepMs('a')).toBe(TYPE_STEP_LATIN_MS);
  });

  it('treats ascii punctuation like latin', () => {
    expect(typewriterStepMs('?')).toBe(TYPE_STEP_LATIN_MS);
  });
});

describe('useTypewriter', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('types char by char, holds the caret, then fades it out to gone', () => {
    const { result } = renderHook(() => useTypewriter('今天'));
    expect(result.current).toMatchObject({ typed: '', done: false, caret: 'typing' });

    act(() => {
      jest.advanceTimersByTime(TYPE_START_DELAY_MS);
    });
    expect(result.current.typed).toBe('今');
    expect(result.current.caret).toBe('typing');

    act(() => {
      jest.advanceTimersByTime(TYPE_STEP_CJK_MS);
    });
    expect(result.current.typed).toBe('今天');
    expect(result.current.done).toBe(true);
    expect(result.current.caret).toBe('holding');

    act(() => {
      jest.advanceTimersByTime(TYPE_CARET_HOLD_MS);
    });
    expect(result.current.caret).toBe('fading');

    act(() => {
      jest.advanceTimersByTime(TYPE_CARET_FADE_MS);
    });
    expect(result.current.caret).toBe('gone');
  });

  it('renders the full text immediately without a caret under reduced motion', () => {
    const media = window.matchMedia as unknown as jest.Mock;
    media.mockImplementationOnce(() => ({
      matches: true,
      addListener: jest.fn(),
      removeListener: jest.fn(),
    }));
    const { result } = renderHook(() => useTypewriter('今天'));
    expect(result.current).toMatchObject({ typed: '今天', done: true, caret: 'gone' });
  });
});
