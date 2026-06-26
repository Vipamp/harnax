-- =====================================================
-- Harnax Database Initialization Script
-- Creates all required databases and grants privileges
-- =====================================================

-- Create harnax database (main business data)
CREATE DATABASE IF NOT EXISTS `harnax` 
    DEFAULT CHARACTER SET utf8mb4 
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Create agentscope database (agent session state)
CREATE DATABASE IF NOT EXISTS `agentscope`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Router service database (api_call_log table, managed by Flyway)
CREATE DATABASE IF NOT EXISTS `harnax_router`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Create harnax user and grant privileges
GRANT ALL PRIVILEGES ON `harnax`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `agentscope`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax_router`.* TO 'harnax'@'%';
FLUSH PRIVILEGES;
