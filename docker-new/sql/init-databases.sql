-- =====================================================
-- Harnax Database Initialization Script
-- Creates all required databases and grants privileges
--
-- Database Architecture:
--   harnax_admin  - Admin + Channel-Service (business data, Flyway managed)
--   harnax        - Agent-Service (business data)
--   agentscope    - Agent-Service (agent session state)
--   harnax_router - Router (api_call_log, Flyway managed)
-- =====================================================

-- Admin + Channel-Service business database (Flyway managed by admin)
CREATE DATABASE IF NOT EXISTS `harnax_admin`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Agent-Service business database
CREATE DATABASE IF NOT EXISTS `harnax`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Agent session state database
CREATE DATABASE IF NOT EXISTS `agentscope`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Router service database (api_call_log table, managed by Flyway)
CREATE DATABASE IF NOT EXISTS `harnax_router`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Create harnax user and grant privileges
GRANT ALL PRIVILEGES ON `harnax_admin`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `agentscope`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax_router`.* TO 'harnax'@'%';
FLUSH PRIVILEGES;
