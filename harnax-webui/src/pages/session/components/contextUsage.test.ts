import {
  compactionOutcome,
  contextUsageBasis,
  contextUsageTokenText,
  formatContextPercent,
  isAtAutoTrigger,
  isContextUsageReadable,
} from './contextUsage';

const usage = (extra: Partial<API.ContextUsage> = {}): API.ContextUsage => ({
  messageCount: 9,
  estimatedTokens: 5504,
  lastCallInputTokens: 5598,
  contextWindow: 200_000,
  windowSource: 'MODEL_FIELD',
  ratio: 0.02799,
  triggerTokens: 180_000,
  triggerMessages: 50,
  ...extra,
});

describe('isContextUsageReadable', () => {
  it('takes a payload with a denominator as a reading', () => {
    expect(isContextUsageReadable({ code: 200, data: usage() })).toBe(true);
  });

  it('treats both shapes of "cannot report" as no reading rather than an empty context', () => {
    // Bound to an instance this one is not: HTTP-level success with a business code and no payload.
    expect(isContextUsageReadable({ code: 500, data: null })).toBe(false);
    // Never bound to any instance: success envelope whose data is null.
    expect(isContextUsageReadable({ code: 200, data: null })).toBe(false);
    expect(isContextUsageReadable(undefined)).toBe(false);
    expect(isContextUsageReadable(null)).toBe(false);
  });

  it('refuses a reading with no denominator to divide by', () => {
    expect(isContextUsageReadable({ code: 200, data: usage({ contextWindow: 0 }) })).toBe(false);
  });

  it('refuses a non-finite ratio', () => {
    expect(isContextUsageReadable({ code: 200, data: usage({ ratio: Number.NaN }) })).toBe(false);
  });
});

describe('contextUsageBasis', () => {
  it('names the billed count as the basis whenever the router has one', () => {
    expect(contextUsageBasis(usage())).toBe('billed');
  });

  it('falls back to the estimate only when no call has been billed yet', () => {
    expect(contextUsageBasis(usage({ lastCallInputTokens: null }))).toBe('estimated');
    expect(contextUsageBasis(usage({ lastCallInputTokens: undefined }))).toBe('estimated');
  });
});

describe('isAtAutoTrigger', () => {
  it('fires on the threshold itself, since that is the turn the auto path takes', () => {
    expect(isAtAutoTrigger(usage({ lastCallInputTokens: 180_000, triggerTokens: 180_000 }))).toBe(true);
  });

  it('follows the billed count, not the estimate, whenever a call has been billed', () => {
    // The estimate alone would look over the line; the number the user sees is the billed one.
    expect(isAtAutoTrigger(usage({ lastCallInputTokens: 179_000, estimatedTokens: 181_000 }))).toBe(false);
  });

  it('compares the estimate only when there is no billed row to compare', () => {
    expect(isAtAutoTrigger(usage({ lastCallInputTokens: null, estimatedTokens: 181_000 }))).toBe(true);
  });

  it('never fires without a usable threshold', () => {
    expect(isAtAutoTrigger(usage({ triggerTokens: undefined, estimatedTokens: 900_000 }))).toBe(false);
    expect(isAtAutoTrigger(usage({ triggerTokens: 0 }))).toBe(false);
  });
});

describe('formatContextPercent', () => {
  it('gives one decimal in the single digits and none from ten up', () => {
    expect(formatContextPercent(0.02799)).toBe('2.8%');
    expect(formatContextPercent(0.1256)).toBe('13%');
    expect(formatContextPercent(0.095)).toBe('9.5%');
    expect(formatContextPercent(0.1)).toBe('10%');
  });

  it('keeps a small reading visible instead of rounding it away', () => {
    expect(formatContextPercent(0.00041)).toBe('0.04%');
    expect(formatContextPercent(0.0001)).toBe('0.01%');
  });

  it('reports an over-full window as over one hundred', () => {
    expect(formatContextPercent(1.26)).toBe('126%');
  });

  it('shows zero only for a genuinely empty or unusable ratio', () => {
    expect(formatContextPercent(0)).toBe('0%');
    expect(formatContextPercent(-0.2)).toBe('0%');
    expect(formatContextPercent(Number.NaN)).toBe('0%');
  });
});

describe('compactionOutcome', () => {
  it('calls a removed-head compaction done', () => {
    expect(compactionOutcome({ success: true, result: { beforeMessages: 9, afterMessages: 5 } })).toBe(
      'done',
    );
  });

  it('calls a successful no-op something else, since nothing was compacted', () => {
    expect(
      compactionOutcome({
        success: true,
        message: 'Nothing to compact for this session yet',
        result: { beforeMessages: 8, afterMessages: 8 },
      }),
    ).toBe('noop');
  });

  it('reports a rejected command as failed so the reason can be surfaced', () => {
    expect(compactionOutcome({ success: false, message: 'only leader or plain sessions' })).toBe('failed');
    expect(compactionOutcome(null)).toBe('failed');
    expect(compactionOutcome(undefined)).toBe('failed');
  });

  it('keeps a done reading when the reply carries no counts to compare', () => {
    expect(compactionOutcome({ success: true })).toBe('done');
    expect(compactionOutcome({ success: true, result: null })).toBe('done');
  });
});

describe('contextUsageTokenText', () => {
  it('reads the token pages\' abbreviation, not a run of ungrouped digits', () => {
    expect(contextUsageTokenText(5598)).toBe('5.60K');
    expect(contextUsageTokenText(5504)).toBe('5.50K');
    expect(contextUsageTokenText(200_000)).toBe('200.00K');
    expect(contextUsageTokenText(1_500_000)).toBe('1.50M');
  });

  it('keeps a number below the thousand line as the number it is', () => {
    expect(contextUsageTokenText(999)).toBe('999');
    expect(contextUsageTokenText(1000)).toBe('1.00K');
    expect(contextUsageTokenText(0)).toBe('0');
  });

  it('leaves an absent count absent, so the caller still says "not recorded yet"', () => {
    expect(contextUsageTokenText(null)).toBeNull();
    expect(contextUsageTokenText(undefined)).toBeNull();
  });
});
