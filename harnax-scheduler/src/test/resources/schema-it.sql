--
-- schema-it.sql: what the Testcontainers MySQL of BaseSchedulerIT starts with, before Flyway runs.
--
-- Deliberately only one IT-private table. The three business tables of the scheduled-task domain
-- (`agent_task`, `agent_task_log`, `agent_task_execution`) are NOT created here: this module owns them, and
-- `db/migration/V1__init_schema.sql` creates them on the IT classpath through Flyway.
--
-- That is not a tidy-up, it is a correctness requirement. Testcontainers runs the init script *before* the
-- application boots, so a `CREATE TABLE IF NOT EXISTS` copy here would win the race and Flyway's own
-- `CREATE TABLE IF NOT EXISTS` in the baseline would silently no-op over it. The two definitions would not be
-- identical — the baseline has `agent_task_log.session_id` at 128 characters, the width the four-segment id of
-- contract C1 needs — so a duplicated DDL would leave every IT running against whichever narrower copy this
-- file happened to keep, and the truncation would show up as a data bug in whichever test wrote a long session
-- id. One definition, one owner: the migration.
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
