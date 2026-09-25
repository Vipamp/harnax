-- V47: channel — attribute rows that never got a tenant to the workspace that runs them
--
-- `channel.tenant_id` has been on the insert list from the start, so rows created while the caller sent
-- `X-Tenant-ID` already carry their workspace. What never happened is a request *without* that header:
-- `TenantContext.getTenantId()` was null and the row fell back to the DDL default, tenant 1, whatever
-- tenant its operator belonged to. `selectChannelList` now filters on the column, so those rows would
-- drop out of their own creator's list and stay visible in tenant 1's — the reverse of the intent.
--
-- The channel's agent is what says who really owns it: a channel exists to expose one agent, the runtime
-- runs it as that agent, and unlike `model`/`model_provider` (V44) the `creator` column is useless here
-- because `createChannel` never writes it and every row carries the literal 'system'.
--
-- Only the defaulted rows move. A row already naming another tenant was written by a request that did
-- carry a workspace header, so its attribution was deliberate and stays as it is.

UPDATE channel c
    JOIN agent a ON a.id = c.agent_id
SET c.tenant_id = a.tenant_id
WHERE c.tenant_id = 1
  AND a.tenant_id <> 1;
