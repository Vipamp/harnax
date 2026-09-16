-- =====================================================
-- Harnax Database Initialization Script
-- Creates all required databases and grants privileges
--
-- Database Architecture:
--   harnax_admin  - Admin + Channel-Service (business data, Flyway managed)
--   harnax        - Agent-Service (business data)
--   agentscope    - Agent-Service (agent session state)
--   harnax_router - Router (api_call_log, Flyway managed)
--   harnax_scheduler - Scheduler (Quartz cluster store + agent task domain, Flyway managed)
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

-- Scheduler service database (Quartz cluster store + agent task domain, Flyway managed by scheduler).
-- This is the scheduler's own database: spring.datasource.url defaults here, so Flyway creates the QRTZ_*
-- tables and the three agent_task tables in it on that service's first start. It was still harnax_admin
-- before release 2, which is why the two databases are both granted below.
CREATE DATABASE IF NOT EXISTS `harnax_scheduler`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Create harnax user and grant privileges
GRANT ALL PRIVILEGES ON `harnax_admin`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `agentscope`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax_router`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax_scheduler`.* TO 'harnax'@'%';
FLUSH PRIVILEGES;
