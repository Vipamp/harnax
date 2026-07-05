-- Drop channel_session table: redundant with channel table.
-- Agent creation now reads from channel table directly via session_id.
DROP TABLE IF EXISTS `channel_session`;
