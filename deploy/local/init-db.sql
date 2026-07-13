-- Harnax Local Development Database Initialization
-- This script runs automatically when MySQL container starts for the first time

-- Create databases
CREATE DATABASE IF NOT EXISTS `harnax_admin`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `harnax`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `agentscope`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `harnax_router`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- Grant privileges to harnax user
GRANT ALL PRIVILEGES ON `harnax_admin`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `agentscope`.* TO 'harnax'@'%';
GRANT ALL PRIVILEGES ON `harnax_router`.* TO 'harnax'@'%';
FLUSH PRIVILEGES;
