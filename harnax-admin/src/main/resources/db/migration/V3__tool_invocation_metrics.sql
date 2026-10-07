-- Forward increment for the tool / MCP / CLI call metrics, in the shape README.md documents for a database
-- that cannot be rebuilt: admin's baseline keeps the checksum the ledger already records, this file ends the
-- schema at the same shape a rebuilt database reaches, and the whole thing folds back into the baseline at
-- the next rebuild - which is also when tool_call_log actually disappears, its code chain having been
-- removed. mcp_call_log stays: it is the MCP authorization ledger and still written, not a metric source.
-- Until a rebuild, the runtime simply no longer writes to tool_call_log.
-- Both statements are IF NOT EXISTS because a rebuilt database has already created them by then, and this
-- file is replayed there too.
CREATE TABLE IF NOT EXISTS `tool_invocation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Invocation event ID',
  `tenant_id` bigint DEFAULT NULL COMMENT 'Owning tenant; NULL when the delivered spec named none, and no tenant-scoped read returns such a row',
  `agent_id` bigint DEFAULT NULL COMMENT 'Owning agent; NULL for a team lead, which has no agent row',
  `session_id` varchar(255) DEFAULT NULL COMMENT 'Session that produced the call',
  `user_id` bigint DEFAULT NULL COMMENT 'End user behind the call, NULL for channel sessions and service keys',
  `kind` varchar(16) NOT NULL COMMENT 'Origin (builtin: delivered tool, mcp: MCP server tool, cli: delivered CLI package run through the shell, shell: bare shell command, framework: harness built-in)',
  `tool_name` varchar(255) NOT NULL COMMENT 'Tool name as the model sees it; the matched command name when kind = cli',
  `mcp_id` bigint DEFAULT NULL COMMENT 'MCP server row, set only when kind = mcp',
  `cli_id` bigint DEFAULT NULL COMMENT 'CLI package row, set only when kind = cli',
  `outcome` varchar(16) NOT NULL COMMENT 'Terminal state (SUCCESS, ERROR, DENIED, INTERRUPTED)',
  `error_message` varchar(512) DEFAULT NULL COMMENT 'Failure reason, truncated',
  `args_json` text COMMENT 'Tool input as JSON, truncated; NULL when payload capture is off',
  `result_excerpt` text COMMENT 'Leading part of the tool result, truncated; NULL when payload capture is off',
  `duration_ms` bigint NOT NULL COMMENT 'End time minus start time',
  `start_time` datetime(3) DEFAULT NULL COMMENT 'Call start; milliseconds because a second-resolution column collapses two calls inside one second onto one instant',
  `end_time` datetime(3) DEFAULT NULL COMMENT 'Call end',
  `ts` datetime(3) NOT NULL COMMENT 'Recorded time, equal to end_time; aggregation and indexes key on it',
  PRIMARY KEY (`id`),
  KEY `idx_tool_invocation_log_tenant_ts` (`tenant_id`,`ts`),
  KEY `idx_tool_invocation_log_tenant_kind_ts` (`tenant_id`,`kind`,`ts`),
  KEY `idx_tool_invocation_log_mcp_ts` (`mcp_id`,`ts`),
  KEY `idx_tool_invocation_log_cli_ts` (`cli_id`,`ts`),
  KEY `idx_tool_invocation_log_session` (`session_id`),
  KEY `idx_tool_invocation_log_tool_name` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='One row per tool invocation, kept for a bounded window';
CREATE TABLE IF NOT EXISTS `tool_invocation_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Aggregate row ID',
  `stat_date` date NOT NULL COMMENT 'Day of the calls, taken from tool_invocation_log.ts',
  `tenant_id` bigint NOT NULL COMMENT 'Owning tenant; detail rows without one are not aggregated at all',
  `kind` varchar(16) NOT NULL COMMENT 'Origin bucket, same vocabulary as the detail table',
  `subject_id` bigint NOT NULL DEFAULT '0' COMMENT 'mcp_id when kind = mcp, cli_id when kind = cli, 0 otherwise; 0 rather than NULL because a unique index does not treat NULLs as equal, and NULL would make the upsert insert a second row for the same day',
  `tool_name` varchar(255) NOT NULL DEFAULT '' COMMENT 'Tool name as the model sees it; every kind carries it, so two tools of one MCP server are two rows on a day',
  `calls` int NOT NULL COMMENT 'Total invocations',
  `successes` int NOT NULL COMMENT 'Invocations ending SUCCESS',
  `errors` int NOT NULL COMMENT 'Invocations ending ERROR',
  `denials` int NOT NULL COMMENT 'Invocations ending DENIED',
  `interruptions` int NOT NULL COMMENT 'Invocations ending INTERRUPTED',
  `sum_duration_ms` bigint NOT NULL COMMENT 'Duration total; the mean is this divided by calls',
  `max_duration_ms` bigint NOT NULL COMMENT 'Longest single call of the day',
  `le_100ms` int NOT NULL DEFAULT '0' COMMENT 'Calls of at most 100 ms',
  `le_500ms` int NOT NULL DEFAULT '0' COMMENT 'Calls over 100 ms and at most 500 ms',
  `le_2s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 500 ms and at most 2 s',
  `le_10s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 2 s and at most 10 s',
  `le_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 10 s and at most 30 s',
  `gt_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 30 s',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_invocation_stats_day` (`stat_date`,`tenant_id`,`kind`,`subject_id`,`tool_name`),
  KEY `idx_tool_invocation_stats_tenant_date` (`tenant_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Daily rollup of tool_invocation_log, retained permanently';
