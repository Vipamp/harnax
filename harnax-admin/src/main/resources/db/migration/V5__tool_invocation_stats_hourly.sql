-- Fold `tool_invocation_stats` one hour at a time instead of one day at a time, so the metrics page can answer
-- an hour-wide window from the aggregate rather than from the detail rows.
--
-- The day rows are not carried over: a day row reinterpreted as `00:00` would be read back as the 00:00 hour of
-- that day holding a whole day's counts, and `selectUnrolledHours` would then call that hour already folded and
-- `deleteRolledOut` would release the detail rows behind it. Losing the aggregate is not losing data, because
-- `tool_invocation_log` still holds everything inside the retention window and the next rollup run re-folds each
-- of those hours from it; the window is bounded (90 days by default), so the rebuild is bounded too.
--
-- Only one grain is stored. Every measured column here is additive over hours - the four outcome counters, the
-- six duration buckets, `sum_duration_ms` - or monotone (`max_duration_ms`), so the day, week and month views
-- the page offers are sums over these rows rather than a second set of rows.
DELETE FROM `tool_invocation_stats`;

ALTER TABLE `tool_invocation_stats`
    DROP INDEX `uk_tool_invocation_stats_day`,
    DROP INDEX `idx_tool_invocation_stats_tenant_date`,
    CHANGE COLUMN `stat_date` `stat_hour` datetime NOT NULL COMMENT 'Hour the calls fall in, `tool_invocation_log.ts` truncated to the hour; minutes and seconds are always zero',
    ADD UNIQUE KEY `uk_tool_invocation_stats_hour` (`stat_hour`,`tenant_id`,`kind`,`subject_id`,`tool_name`),
    ADD KEY `idx_tool_invocation_stats_tenant_hour` (`tenant_id`,`stat_hour`),
    MODIFY COLUMN `subject_id` bigint NOT NULL DEFAULT '0' COMMENT 'mcp_id when kind = mcp, cli_id when kind = cli, 0 otherwise; 0 rather than NULL because a unique index does not treat NULLs as equal, and NULL would make the upsert insert a second row for the same hour',
    MODIFY COLUMN `tool_name` varchar(255) NOT NULL DEFAULT '' COMMENT 'Tool name as the model sees it; every kind carries it, so two tools of one MCP server are two rows in an hour',
    MODIFY COLUMN `max_duration_ms` bigint NOT NULL COMMENT 'Longest single call of the hour',
    COMMENT='Hourly rollup of tool_invocation_log; rows are kept permanently';
