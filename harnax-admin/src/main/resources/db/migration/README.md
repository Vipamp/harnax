# Flyway Schema Baseline

## Directory Structure

```
db/
└── migration/
    ├── V1__init_schema.sql   # the module's whole schema, in final form
    └── README.md             # this file
```

Each service module owns exactly one baseline, applied against its own database and recorded in its
own history table:

| Module | Baseline | Database | History table |
| --- | --- | --- | --- |
| `harnax-admin` | `V1__init_schema.sql` | `harnax_admin` | `flyway_schema_history` |
| `harnax-scheduler` | `V1__init_schema.sql` | `harnax_scheduler` | `flyway_schema_history_scheduler` |
| `harnax-session-router` | `V1__create_session_router_tables.sql` | `harnax_router` | `flyway_schema_history` (cluster profile only — `local` mode uses `db/sqlite-init.sql`) |

The baseline states the schema as it is: every `CREATE TABLE` with its final columns, indexes, unique
keys and comments, followed by the initial data the system needs to boot. Admin's initial data is the
default tenant, its `admin` account, that account's tenant membership, and the built-in CLI skill
repository row.

## Changing the Schema

One path: edit this baseline in place, then recreate the database. Columns, indexes, keys, comments
and the seed rows all go straight into the final `CREATE TABLE` / `INSERT` form — the file holds no
`ALTER` deltas and no data-repair statements, and this directory holds nothing to stack on top of it.
This is what every `harnax-deploy` environment does, and it is why the schema history is not carried
forward: a fresh install and a rebuilt install end at the same shape because they run the same single
script.

A database already built from an earlier form of this file will not accept the edited baseline: its
history row names a script whose checksum no longer matches, so startup fails on validate. Editing
the schema and rebuilding the database are therefore one action, never two.

Along with editing the baseline:

1. Regenerate `harnax-entity/src/test/resources/schema-test.sql`. Its DDL block is copied from this
   baseline rather than hand-maintained; `SchemaBaselineDriftIT` compares the fixture against the
   schema Flyway actually built and fails on any drift.
2. Re-run the mapper tests (`mvn -o -pl harnax-entity -am test`) and the drift guard
   (`mvn -o -pl harnax-admin -am -Pintegration-test verify`).

## Adopting the Baseline on an Existing Database

A database whose ledger already carries rows cannot simply switch to this baseline: those rows name
scripts that are no longer on the classpath, and the recorded checksum no longer matches the file on
disk. Drop the schema and let the baseline rebuild it.

`harnax-admin` alone runs Flyway with `repair-on-migrate: true`, so it would realign the checksum and
clear the unresolved history rows instead of failing — but that leaves the schema unverified against
the baseline by anything other than the drift guard, so a rebuild is still the required action.
`harnax-scheduler` and `harnax-session-router` have no repair configured and fail outright.

## Configuration

`harnax-admin/src/main/resources/application.yml`:

```yaml
spring:
  flyway:
    enabled: ${FLYWAY_ENABLED:true}
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    validate-on-migrate: true
    repair-on-migrate: true
    clean-disabled: ${FLYWAY_CLEAN_DISABLED:true}
    sql-migration-prefixes: V
    repeatable-sql-migration-prefixes: R
```

`baseline-on-migrate` with `baseline-version: 0` matters for the database created by
`harnax-deploy/sql/init-databases.sql`: that script creates the schema, so Flyway finds a non-empty schema
only when it is also brand new, and baselining at 0 makes it apply this baseline rather than mark the
schema as already migrated.

## Notes

- The baseline is written so that a fresh install and a rebuilt install converge: nothing in it depends
  on rows that only a replayed history would have produced. Data-repair statements — backfills,
  renames, re-attribution — have no place in it for exactly that reason, since a new schema has no rows
  to repair.
- Keep `DROP` and `DROP TABLE` out of the baseline body; tables are written `CREATE TABLE IF NOT EXISTS`.
  A rebuild starts from an empty schema either way, so the clause costs nothing and keeps the script
  re-runnable against a schema that was partially created. The one existing exception is the 11 `QRTZ_*`
  tables in `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql`, which keep the bare
  `CREATE TABLE` of Quartz's own script.
- Verify a change against the running stack before relying on it; startup logs the applied version:
  `Current version of schema`.
