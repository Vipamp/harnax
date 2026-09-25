-- V48: statistics and logs — stop attributing a team lead's rows to agent 0
--
-- A team's lead is configured by the `team` row and no `agent` stands behind it (design D1), yet the
-- runtime keys such a run with the id 0 sentinel and wrote that literal into `token_stats`,
-- `tool_call_log` and `process_log`. All three columns are nullable, so the sentinel was the only thing
-- making a lead look like an agent: an agent-dimension read gets a bucket whose id resolves to no row
-- and whose name is missing, and `COUNT(DISTINCT agent_id)` counts one more agent than the workspace
-- has. `AgentSpec.attributableAgentId` now stores the absence instead.
--
-- Only 0 moves. `agent.id` is AUTO_INCREMENT, so no real agent is ever numbered 0 and a row that names
-- an agent keeps its attribution. The agent's own name is what a lead's rows lack either way —
-- `agent_name` is denormalized on `process_log` and stays as written.

UPDATE token_stats SET agent_id = NULL WHERE agent_id = 0;
UPDATE tool_call_log SET agent_id = NULL WHERE agent_id = 0;
UPDATE process_log SET agent_id = NULL WHERE agent_id = 0;
