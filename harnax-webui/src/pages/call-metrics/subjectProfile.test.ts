import { matchToolRow, profileTargetOf } from './subjectProfile';

/** The smallest row that satisfies the shape: the tests name the fields they depend on. */
const row = (over: Partial<API.CallMetricsRow>): API.CallMetricsRow => ({
  kind: '',
  subjectKey: '',
  toolName: '',
  calls: 1,
  successes: 1,
  errors: 0,
  denials: 0,
  interruptions: 0,
  successRate: 1,
  avgDurationMs: 0,
  p95Operator: '<=',
  p95Ms: 0,
  ...over,
});

/**
 * The subject column decides which registry read to fire, and the rows come from three different shapes:
 * `tool` carries kind plus a registry id, `agent` carries the agent id, `session` carries only the session id
 * string. A wrong branch here opens a drawer that describes a different object than the row clicked.
 */
describe('profileTargetOf', () => {
  it('reads an MCP row out of the MCP registry through the id the row carries', () => {
    expect(profileTargetOf(row({ kind: 'mcp', subjectKey: 'filesystem', subjectId: 77, toolName: 'fetch_url' }), 'tool')).toEqual({
      source: 'mcp',
      id: 77,
    });
  });

  it('reads a CLI row out of the package registry', () => {
    expect(profileTargetOf(row({ kind: 'cli', subjectKey: 'gh', subjectId: 88, toolName: 'gh' }), 'tool')).toEqual({
      source: 'cli',
      id: 88,
    });
  });

  it('refuses to ask an id the row does not carry instead of sending undefined down the wire', () => {
    // Server folds subject_id=0 to null, so a missing key is the real shape of a row that has no registry id.
    expect(profileTargetOf(row({ kind: 'mcp', subjectKey: 'filesystem', toolName: 'fetch_url' }), 'tool')).toEqual({ source: 'none' });
    expect(profileTargetOf(row({ kind: 'cli', subjectKey: 'gh', toolName: 'gh' }), 'tool')).toEqual({ source: 'none' });
  });

  it('matches a builtin name against the tool registry', () => {
    expect(profileTargetOf(row({ kind: 'builtin', subjectKey: 'send_email', toolName: 'send_email' }), 'tool')).toEqual({
      source: 'tool',
      toolName: 'send_email',
    });
  });

  it('says a shell or framework call has no profile, because no table has a row for it', () => {
    expect(profileTargetOf(row({ kind: 'shell', subjectKey: 'execute', toolName: 'execute' }), 'tool')).toEqual({ source: 'none' });
    expect(profileTargetOf(row({ kind: 'framework', subjectKey: 'read_file', toolName: 'read_file' }), 'tool')).toEqual({ source: 'none' });
  });

  it('keys an agent row on the agent id and falls back to the key the row is labelled with', () => {
    expect(profileTargetOf(row({ subjectKey: '1', subjectId: 1 }), 'agent')).toEqual({ source: 'agent', id: 1 });
    expect(profileTargetOf(row({ subjectKey: '42' }), 'agent')).toEqual({ source: 'agent', id: 42 });
  });

  it('refuses to build an agent id out of a key that is not one', () => {
    expect(profileTargetOf(row({ subjectKey: 'not-a-number' }), 'agent')).toEqual({ source: 'none' });
    expect(profileTargetOf(row({ subjectKey: '' }), 'agent')).toEqual({ source: 'none' });
  });

  it('keys a session row on the session id string, which is not a row id', () => {
    expect(profileTargetOf(row({ subjectKey: 'metrics-session' }), 'session')).toEqual({ source: 'session', sessionId: 'metrics-session' });
    expect(profileTargetOf(row({ subjectKey: '' }), 'session')).toEqual({ source: 'none' });
  });
});

/**
 * The tool registry has no read-by-name endpoint, so the drawer picks the row out of the list. A prefix match
 * would describe `send_email_v2` while the reader clicked `send_email`.
 */
describe('matchToolRow', () => {
  const tools: API.AgentToolItem[] = [
    { name: 'send_email', description: 'Send an email' },
    { name: 'send_email_v2', description: 'Newer sender' },
  ];

  it('takes the exact name only', () => {
    expect(matchToolRow(tools, 'send_email')?.description).toBe('Send an email');
    expect(matchToolRow(tools, 'send_email_v2')?.description).toBe('Newer sender');
  });

  it('answers nothing for a name the registry does not carry', () => {
    expect(matchToolRow(tools, 'memory_search')).toBeUndefined();
    expect(matchToolRow(undefined, 'send_email')).toBeUndefined();
    expect(matchToolRow([], 'send_email')).toBeUndefined();
  });
});
