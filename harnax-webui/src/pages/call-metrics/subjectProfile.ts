import { getAgentById } from '@/services/ant-design-pro/agent';
import { getCliDetail } from '@/services/ant-design-pro/cli';
import { getMcpServerById } from '@/services/ant-design-pro/mcp';
import { getSessionConfig } from '@/services/ant-design-pro/session';
import { getBuiltinTools } from '@/services/ant-design-pro/tool';

/**
 * Which registry row a subject has behind it.
 *
 * The metrics rows come from `tool_invocation_log`, which records an origin bucket and the ids the call
 * carried; only MCP servers, CLI packages, agents and sessions have a registry table to read. A `shell` or
 * `framework` row, and a builtin name the registry does not carry, therefore have no profile to show.
 */
export type ProfileTarget =
  | { source: 'agent'; id: number }
  | { source: 'mcp'; id: number }
  | { source: 'cli'; id: number }
  | { source: 'session'; sessionId: string }
  | { source: 'tool'; toolName: string }
  | { source: 'none' };

/** What the read answered, per source: the registry row or the absence of one. */
export type Profile =
  | { kind: 'none' }
  | { kind: 'agent'; data: API.AgentItem }
  | { kind: 'mcp'; data: API.McpServerItem }
  | { kind: 'cli'; data: API.CliDetail }
  | { kind: 'session'; data: API.SessionItem }
  | { kind: 'tool'; data: API.AgentToolItem };

const NOT_FOUND = 404;

/**
 * The subject column is keyed on the dimension the rows were grouped by: `tool` rows carry a `kind` and the
 * registry id, `agent` rows carry the agent id, `session` rows carry only the session id string.
 */
export function profileTargetOf(row: API.CallMetricsRow, groupBy: string): ProfileTarget {
  if (groupBy === 'agent') {
    const id = row.subjectId ?? Number(row.subjectKey);
    return Number.isFinite(id) && id > 0 ? { source: 'agent', id } : { source: 'none' };
  }
  if (groupBy === 'session') {
    return row.subjectKey ? { source: 'session', sessionId: row.subjectKey } : { source: 'none' };
  }
  if (row.kind === 'mcp' || row.kind === 'cli') {
    return row.subjectId != null ? { source: row.kind, id: row.subjectId } : { source: 'none' };
  }
  // `GET /api/admin/tools/{id}` is keyed on a Long, so a builtin name is matched out of the registry list.
  if (row.kind === 'builtin') {
    return row.toolName ? { source: 'tool', toolName: row.toolName } : { source: 'none' };
  }
  return { source: 'none' };
}

export function matchToolRow(
  rows: API.AgentToolItem[] | undefined,
  toolName: string,
): API.AgentToolItem | undefined {
  return (rows ?? []).find((row) => row.name === toolName);
}

/**
 * A 404 means the registry has no row for this subject — the agent or session was deleted while its calls
 * stay in the log — so it answers the same empty drawer as a `shell` row does. Any other non-200 is a read
 * that failed, and reporting that as "nothing registered" would tell the reader the wrong thing.
 */
function unwrap<T>(res: API.Result<T>): T | undefined {
  if (res.code === NOT_FOUND) return undefined;
  if (res.code !== 200) throw new Error(`profile read answered code ${res.code}`);
  return res.data ?? undefined;
}

export async function fetchProfile(target: ProfileTarget): Promise<Profile> {
  if (target.source === 'none') return { kind: 'none' };
  if (target.source === 'session') {
    const data = unwrap(await getSessionConfig(target.sessionId));
    return data ? { kind: 'session', data } : { kind: 'none' };
  }
  if (target.source === 'agent') {
    const data = unwrap((await getAgentById(target.id)) as API.Result<API.AgentItem>);
    return data ? { kind: 'agent', data } : { kind: 'none' };
  }
  if (target.source === 'mcp') {
    const data = unwrap((await getMcpServerById(target.id)) as API.Result<API.McpServerItem>);
    return data ? { kind: 'mcp', data } : { kind: 'none' };
  }
  if (target.source === 'cli') {
    const data = unwrap((await getCliDetail(target.id)) as API.Result<API.CliDetail>);
    return data ? { kind: 'cli', data } : { kind: 'none' };
  }
  const tools = unwrap((await getBuiltinTools()) as API.Result<API.AgentToolItem[]>);
  const data = matchToolRow(tools, target.toolName);
  return data ? { kind: 'tool', data } : { kind: 'none' };
}
