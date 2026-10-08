-- Releases the orphaned `tool_call_log`: its read and write side is gone, and the events it recorded are
-- answered by `tool_invocation_log` now. The delete arrives as a forward increment because V1 is applied on
-- the live schema and therefore byte-frozen — its CREATE TABLE block cannot be edited there without breaking
-- Flyway's checksum, so the only declarative way to remove the table is a later version that drops it.
-- A rebuilt database ends at the same shape either way: it either never had the table, or V1 creates it and
-- this file drops it.
-- IF EXISTS is what makes both of those true, and it also means the file keeps doing its job after the
-- baseline fold-back deletes this block from V1 and this file together.
DROP TABLE IF EXISTS `tool_call_log`;
