import { claimDelegateCard, delegatedTasksOf, type DelegateCard } from './teamRun';

const card = (toolId: string, memberAgentId: number | string, extra: Partial<DelegateCard> = {}): DelegateCard => ({
  type: 'tool_call',
  toolName: 'team_delegate',
  toolId,
  content: JSON.stringify({ member_agent_id: memberAgentId, task: `任务-${toolId}` }),
  ...extra,
});

describe('claimDelegateCard', () => {
  it('claims the member cards in call order, one run each', () => {
    const segments = [card('c1', 7), card('c2', 9), card('c3', 7)];
    const claimed = new Set<string>();

    expect(claimDelegateCard(segments, 7, claimed)).toBe('c1');
    expect(claimDelegateCard(segments, 9, claimed)).toBe('c2');
    expect(claimDelegateCard(segments, 7, claimed)).toBe('c3');
    // Both cards of member 7 are taken: a third run has nothing left to nest in.
    expect(claimDelegateCard(segments, 7, claimed)).toBeUndefined();
  });

  it('leaves a run without a card of its own standalone', () => {
    const claimed = new Set<string>();
    const segments: DelegateCard[] = [
      { type: 'text', content: 'not a card' },
      card('c1', 7, { toolName: 'readFile' }),
    ];

    expect(claimDelegateCard(segments, 7, claimed)).toBeUndefined();
    expect(claimDelegateCard(segments, 42, claimed)).toBeUndefined();
    expect(claimed.size).toBe(0);
  });

  it('survives arguments that never parse', () => {
    const segments = [{ type: 'tool_call', toolName: 'team_delegate', toolId: 'c1', content: '{oops' }];

    expect(claimDelegateCard(segments, 7, new Set<string>())).toBeUndefined();
  });

  it('needs a tool id to be claimable at all', () => {
    const segments: DelegateCard[] = [{ type: 'tool_call', toolName: 'team_delegate', content: '{"member_agent_id":7}' }];

    expect(claimDelegateCard(segments, 7, new Set<string>())).toBeUndefined();
  });
});

describe('delegatedTasksOf', () => {
  it('reads the tasks a lead turn delegated, in call order', () => {
    const log = {
      role: 'ASSISTANT',
      toolUseLog: [
        { name: 'team_delegate', input: { member_agent_id: '7', task: '  先做这个\n第二行' } },
        { name: 'readFile', input: { path: '/tmp/x' } },
        { name: 'team_delegate', input: { member_agent_id: 9, task: '  后做这个  ' } },
        { name: 'team_delegate', input: { member_agent_id: 9 } },
      ],
    };

    expect(delegatedTasksOf(log)).toEqual([
      { memberAgentId: 7, task: '  先做这个\n第二行' },
      { memberAgentId: 9, task: '  后做这个  ' },
    ]);
  });

  it('ignores anything that is not a lead turn', () => {
    expect(delegatedTasksOf({ role: 'TOOL', toolUseLog: [{ name: 'team_delegate', input: {} }] })).toEqual([]);
    expect(delegatedTasksOf({ role: 'ASSISTANT' })).toEqual([]);
  });
});
