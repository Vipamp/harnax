--
-- schema-it.sql: what the Testcontainers MySQL of BaseSchedulerIT starts with, before Flyway runs.
--
-- Deliberately only one IT-private table now. The three business tables of the scheduled-task domain
-- (`agent_task`, `agent_task_log`, `agent_task_execution`) are NOT created here: since release 2 this module
-- owns them, and `V2__agent_task_domain.sql` creates them on the IT classpath through Flyway.
--
-- That is not a tidy-up, it is a correctness requirement. Testcontainers runs the init script *before* the
-- application boots, so a `CREATE TABLE IF NOT EXISTS` copy here would win the race and Flyway's own
-- `CREATE TABLE IF NOT EXISTS` in V2 would silently no-op over it. The two definitions are not identical —
-- release 2 widened `agent_task_log.session_id` from 64 to 128 characters for the four-segment id of
-- contract C1 — so a duplicated DDL would leave every IT running against the old, narrower column and the
-- truncation would show up as a data bug in whichever test happened to write a long session id. One
-- definition, one owner: the migration.
--
-- No seed rows either, in this file or in any other IT resource. A seeded `agent_task` would make "what one
-- reconcile round did" count somebody else's task, and the ITs assert exact numbers; each IT inserts what it
-- measures and deletes it again.
--

-- ============================================
-- IT-private: ClusterSingleFireIT's evidence table
-- ============================================
-- Written by ClusterSingleFireIT's job: the aggregate row count is the evidence that a clustered
-- scheduler fires each trigger once for the whole cluster rather than once per node.
CREATE TABLE IF NOT EXISTS `it_cluster_fire` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `instance_name` VARCHAR(190) NOT NULL,
    `fire_time`     BIGINT NOT NULL
) COMMENT='Cluster single-fire evidence';
