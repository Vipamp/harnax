-- Forward increment, for the same reason V2, V3 and V5 are increments rather than baseline edits: this
-- database holds a model provider API key that only a human can re-enter, so it cannot be rebuilt as part
-- of a schema change.
-- A conversation's own memory layer is now a buffer with no automatic writer on the far side: the runtime
-- merges it into a candidate and files it here, and the owner's long-term `MEMORY.md` changes only when a
-- person approves one. So this table is the queue those approvals run over, and `base_version` is the
-- precondition the approval applies the candidate against — the merge is offered against the exact version
-- of the owner's text it read, and a layer that moved in the meantime is refused rather than overwritten by
-- whoever decides first.
-- One row per conversation, not per object: `sources` names every layer file the merge took material from
-- together with the bytes it read there, which is what lets the approval clear exactly those and leave
-- anything that took a write since.
CREATE TABLE IF NOT EXISTS `memory_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Candidate ID',
  `tenant_id` bigint NOT NULL COMMENT 'Tenant resolved server-side from the session, never taken from the request body',
  `user_id` bigint NOT NULL COMMENT 'Owner whose memory bucket this merges into, read from the session row; the queue belongs to that person, not to their workspace',
  `agent_name` varchar(100) NOT NULL COMMENT 'Agent bucket segment the runtime keyed the layer by - agent names, not IDs, address the memory objects',
  `session_id` varchar(100) NOT NULL COMMENT 'Conversation whose own layer was merged - as wide as session.session_id, because a team child id is that root id with a prefix and a suffix on it',
  `merged_md` mediumtext NOT NULL COMMENT 'The complete new MEMORY.md the merge produced, as the reviewer reads it',
  `base_md` mediumtext COMMENT 'The owner''s text the merge read, null when the owner had none yet',
  `base_version` bigint NOT NULL COMMENT 'Version of that object the merge read; 0 means it did not exist, so the approval is a create',
  `sources` mediumtext NOT NULL COMMENT 'JSON array of {path, content}: every conversation-layer object the merge read, with the bytes it read there',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  `reviewed_by` varchar(100) DEFAULT NULL COMMENT 'Reviewer username, once decided',
  `reviewed_at` datetime DEFAULT NULL COMMENT 'Review time, once decided',
  `reject_reason` varchar(512) DEFAULT NULL COMMENT 'Why the owner rejected it',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_memory_draft_tenant_status` (`tenant_id`,`status`),
  KEY `idx_memory_draft_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Conversation memory merges waiting for their owner to approve them';
