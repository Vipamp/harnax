-- V30: drop the removed CUSTOM / HTTP tool types from the built-in harnax-cli skill body.
--
-- The tool management API is read-only now: tools are only builtin and are synced at admin
-- startup by BuiltinToolAutoRegistrar, so `tool update|delete|toggle` and the `--type` filter
-- no longer exist, and the tool tables no longer print a Type column.
-- Flyway history (V12 / V14) must never be edited, so the seeded skillmd is patched in place
-- with REPLACE. Every statement is idempotent: once the old fragment is gone REPLACE is a no-op.
-- Target rows: the 'harnax-cli' skill of the reserved 'builtin-cli-skills' repository, all tenants.

UPDATE skill s
JOIN skill_repository r ON s.repository_id = r.id
SET s.skillmd = REPLACE(
    s.skillmd,
    'harnax tool list [--keyword <keyword>] [--type BUILTIN|CUSTOM|HTTP] [--status <0|1>] [--page <n>] [--size <n>]',
    'harnax tool list [--keyword <keyword>] [--status <0|1>] [--page <n>] [--size <n>]'
)
WHERE r.name = 'builtin-cli-skills' AND s.name = 'harnax-cli';

UPDATE skill s
JOIN skill_repository r ON s.repository_id = r.id
SET s.skillmd = REPLACE(
    s.skillmd,
    'harnax tool available [--type BUILTIN|CUSTOM|HTTP]',
    'harnax tool available'
)
WHERE r.name = 'builtin-cli-skills' AND s.name = 'harnax-cli';

UPDATE skill s
JOIN skill_repository r ON s.repository_id = r.id
SET s.skillmd = REPLACE(
    s.skillmd,
    '输出表格：ID、Name、Type、Status、Read Only',
    '输出表格：ID、Name、Status、Read Only'
)
WHERE r.name = 'builtin-cli-skills' AND s.name = 'harnax-cli';

-- Remove the whole "更新/删除/切换" section (heading, fenced block and its trailing blank line).
UPDATE skill s
JOIN skill_repository r ON s.repository_id = r.id
SET s.skillmd = REPLACE(
    s.skillmd,
    '### 更新/删除/切换

```bash
harnax tool update <id> [--name <name>]
harnax tool delete <id>
harnax tool toggle <id>
```

',
    ''
)
WHERE r.name = 'builtin-cli-skills' AND s.name = 'harnax-cli';

UPDATE skill s
JOIN skill_repository r ON s.repository_id = r.id
SET s.skillmd = REPLACE(
    s.skillmd,
    '列出所有已启用的工具，可按类型过滤。',
    '列出所有已启用的工具。'
)
WHERE r.name = 'builtin-cli-skills' AND s.name = 'harnax-cli';

-- State up front that tools are builtin-only and read-only.
UPDATE skill s
JOIN skill_repository r ON s.repository_id = r.id
SET s.skillmd = REPLACE(
    s.skillmd,
    '## 工具管理

### 查询工具列表',
    '## 工具管理

工具全部为内置工具，由 admin 启动时按 `@Tool`/`@ToolMeta` 注解自动同步写库，CLI 与页面均不支持新增、修改、删除或启停工具。

### 查询工具列表'
)
WHERE r.name = 'builtin-cli-skills' AND s.name = 'harnax-cli';
