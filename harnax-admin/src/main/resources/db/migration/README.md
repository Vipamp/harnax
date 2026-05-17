# Flyway Database Migration

## Directory Structure

```
db/
├── schema.sql              # Initial database schema (executed once on first run)
└── migration/              # Incremental migration scripts (V1__, V2__, etc.)
    └── README.md           # This file
```

## How It Works

### Initial Schema (schema.sql)

- **Purpose**: Creates the initial database structure
- **Execution**: Runs only once when the database is first created
- **File**: `db/schema.sql`
- **Contains**: All initial table definitions from `docker/sql/init.sql`

### Incremental Migrations (migration/)

- **Purpose**: Apply schema changes after initial deployment
- **Execution**: Runs in version order (V1, V2, V3, ...)
- **Naming Convention**: `V{version}__{description}.sql`
  - Example: `V1__add_user_preferences.sql`
  - Example: `V2__modify_agent_table.sql`

## Creating New Migrations

When you need to modify the database schema:

1. **Create a new SQL file** in `migration/` directory
2. **Follow naming convention**: `V{next_version}__{description}.sql`
3. **Write standard SQL** (no DROP TABLE, use ALTER TABLE)

### Example

```sql
-- V1__add_user_preferences.sql
-- Add user preferences table

CREATE TABLE IF NOT EXISTS `user_preferences` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `theme` VARCHAR(20) DEFAULT 'light',
    `language` VARCHAR(10) DEFAULT 'en',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User preferences table';
```

## Configuration

Flyway is configured in `application.yml`:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    validate-on-migrate: true
    clean-disabled: true
```

## Workflow

### First Deployment (Fresh Database)

1. Flyway creates `flyway_schema_history` table
2. Executes `schema.sql` to create initial tables
3. Records baseline version in history table
4. Checks `migration/` for any scripts newer than baseline
5. Executes migration scripts in order

### Subsequent Deployments

1. Flyway checks `flyway_schema_history` for current version
2. Scans `migration/` for new scripts
3. Executes new migrations in version order
4. Updates history table

## Important Notes

- **Never modify** executed migration scripts
- **Never delete** migration scripts from history
- **Always increment** version numbers
- **Use ALTER TABLE** for schema changes, not DROP + CREATE
- **Test migrations** on a copy of production data before deploying

## Verification

Check migration status:

```bash
# Via application logs (on startup)
# Look for: "Flyway: Current version of schema `harnax`: X"

# Or query the history table
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC;
```

## Rollback

Flyway Community Edition does not support automatic rollback. For rollbacks:

1. Create a new migration script to reverse the change
2. Example: `V3__rollback_add_user_preferences.sql`
3. Or manually execute SQL to revert changes

## Best Practices

1. **One change per migration**: Keep migrations focused and small
2. **Descriptive names**: Make the purpose clear from the filename
3. **Add comments**: Explain why the change is needed
4. **Test thoroughly**: Verify migrations work on empty and populated databases
5. **Backup before deploying**: Always backup production database before migration
