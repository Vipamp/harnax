-- V39: drop the scheduled-task tables from this database
--
-- Release 2 moved the whole domain into the scheduler's own schema: `harnax_scheduler` now holds
-- `agent_task`, `agent_task_log` and `agent_task_execution`, built by that service's V2, and the
-- scheduler is the only reader and writer. In `harnax_admin` the three copies are dead: no entity, no
-- mapper and no SQL in this service names them (admin's AgentTaskController forwards to the scheduler),
-- and the test schema never created them, so nothing here can tell they are gone.
--
-- V1 is why they exist at all. It is a historical sequence and cannot be edited, so every fresh install
-- replays those three CREATE TABLE statements and ends with three empty tables in a database whose run
-- book says the task domain lives elsewhere — which is how the operator finds an empty `agent_task` in
-- the wrong schema. This migration converges a replayed install with one that was cut over by hand.
--
-- The cutover deliberately left this DROP to whoever runs it (design D8 cancelled the data migration, so
-- no reader survives the switch and there is no observation period to sit through). It stayed out of the
-- repository only as long as that was the one install being cleaned; an ad-hoc DROP does not repeat, and
-- the rollback the deployment doc describes is bounded by this step either way.
--
-- QRTZ_* is not in scope: the scheduler's own Flyway created those tables, and it pointed at this
-- database only during release 1, so a fresh install has none here.

DROP TABLE IF EXISTS `agent_task_execution`;
DROP TABLE IF EXISTS `agent_task_log`;
DROP TABLE IF EXISTS `agent_task`;
