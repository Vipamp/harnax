import { useIntl } from '@umijs/max';
import { Button, Select, Space, Tag, Typography } from 'antd';
import React, { useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import EnvParamTable, { type EnvBindingRow, type EnvVarOption } from './EnvParamTable';
import { isEnvBindingFilled } from './envBinding';

const { Text } = Typography;

/** 已选 CLI 各自的参数填写记录，按 cliId 归拢：删掉再添加回来，填过的值还在 */
export type CliEnvBindings = Record<number, EnvBindingRow[]>;

export type CliConfigState = {
  cliId: number;
  cliName: string;
  envEntries: API.ToolEnvParamEntry[];
  envBindings: EnvBindingRow[];
};

interface CliConfigPanelProps {
  selectedCliIds: number[];
  setSelectedCliIds: (ids: number[]) => void;
  clis: API.CliItem[];
  envVarOptions: EnvVarOption[];
  bindings: CliEnvBindings;
  setBindings: (next: CliEnvBindings) => void;
}

/**
 * 一个 CLI 的参数行，以包声明为准逐条对齐已填的值。
 *
 * 不按存储记录的数量渲染：包升级后新增的参数会因为没有存储记录而永远不出现，
 * 删掉的参数则会一直留在表单里被人填。
 */
export function cliEnvRows(cli: API.CliItem, stored: EnvBindingRow[] | undefined): EnvBindingRow[] {
  return (cli.envParams || []).map((entry) => {
    const saved = stored?.find((row) => row.envKey === entry.envParamName);
    return saved ? { ...saved } : { envKey: entry.envParamName, envValue: '' };
  });
}

/** 勾选的 CLI 逐个摊开成校验与提交要用的行，没声明参数的 CLI 也在内（envBindings 为空） */
export function cliConfigRows(
  selectedCliIds: number[],
  clis: API.CliItem[],
  bindings: CliEnvBindings,
): CliConfigState[] {
  return selectedCliIds
    .map((id) => clis.find((cli) => cli.id === id))
    .filter((cli): cli is API.CliItem => cli != null)
    .map((cli) => ({
      cliId: cli.id!,
      cliName: cli.name,
      envEntries: cli.envParams || [],
      envBindings: cliEnvRows(cli, bindings[cli.id!]),
    }));
}

/**
 * 只把有值的行发给后端。
 *
 * 空行发过去会变成一条 `customValue: ""`，下发时虽被丢弃，但会占住 agent 快照里这个键位，
 * 让「留空 = 跟随包默认值」在数据上分不清。
 */
export function cliEnvPayload(rows: EnvBindingRow[]): API.EnvBinding[] {
  return rows
    .filter(isEnvBindingFilled)
    .map(({ customInput, envKey, envValue, envVarId }) => ({
      envKey,
      ...(customInput || !envVarId ? { customValue: envValue } : { envVarId }),
    }));
}

/**
 * Agent CLI 配置：一卡一个 CLI，卡上换选要装的包，卡内填它声明的参数。
 *
 * CLI 的技能来自包内 `SKILL.md`，加上这个 CLI 就等于带上那份技能，这里不再单独关联技能。
 */
const CliConfigPanel: React.FC<CliConfigPanelProps> = ({
  selectedCliIds,
  setSelectedCliIds,
  clis,
  envVarOptions,
  bindings,
  setBindings,
}) => {
  const intl = useIntl();
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set());

  const available = clis.flatMap((cli) => (cli.id == null ? [] : [{ id: cli.id, cli }]));
  const rows = cliConfigRows(selectedCliIds, clis, bindings);

  // 同一个包加两次没有意义：后端按 cliId 去重，第二张卡填的值会静默丢掉。
  // 只挡别的卡，本卡自己已选的要留着，否则标题会渲染成空白。
  const optionsFor = (index: number) =>
    available
      .filter((item) => !selectedCliIds.some((id, otherIndex) => otherIndex !== index && id === item.id))
      .map((item) => ({ label: item.cli.name, value: item.id, data: item.cli }));

  const nextAvailable = available.find((item) => !selectedCliIds.includes(item.id));

  const changeAt = (index: number, cliId: number) => {
    const next = [...selectedCliIds];
    next[index] = cliId;
    setSelectedCliIds(next);
  };

  const removeAt = (index: number) => setSelectedCliIds(selectedCliIds.filter((_, i) => i !== index));

  const writeRow = (cliId: number, envIndex: number, next: EnvBindingRow) => {
    const cli = clis.find((item) => item.id === cliId);
    if (!cli) return;
    const nextRows = cliEnvRows(cli, bindings[cliId]).map((row, i) => (i === envIndex ? next : row));
    setBindings({ ...bindings, [cliId]: nextRows });
  };

  const toggleCollapsed = (cliId: number) => {
    const next = new Set(collapsed);
    next.has(cliId) ? next.delete(cliId) : next.add(cliId);
    setCollapsed(next);
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ color: 'var(--vip-text-secondary)', fontSize: '14px' }}>
          {intl.formatMessage({
            id: 'pages.agent.cliConfigOptional',
            defaultMessage: 'Select CLI tools (optional). They will be installed into the sandbox automatically, and their associated skills will be loaded at runtime.',
          })}
        </span>
        <Button
          type="dashed"
          icon={<PlusOutlined />}
          disabled={!nextAvailable}
          onClick={() => nextAvailable && setSelectedCliIds([...selectedCliIds, nextAvailable.id])}
        >
          {intl.formatMessage({ id: 'pages.agent.cli.add', defaultMessage: 'Add CLI' })}
        </Button>
      </div>

      {rows.map((row, index) => {
        const cli = available.find((item) => item.id === row.cliId)?.cli;
        return (
          <div
            key={row.cliId}
            style={{ marginBottom: 16, padding: 12, border: '1px solid var(--vip-border)', borderRadius: 8, background: 'var(--vip-bg-layout)' }}
          >
            <Space style={{ display: 'flex', marginBottom: 8 }} align="center">
              <Select
                style={{ width: 240 }}
                placeholder={intl.formatMessage({ id: 'pages.agent.cliPlaceholder', defaultMessage: 'Select CLI tools' })}
                value={row.cliId}
                onChange={(value) => changeAt(index, value)}
                options={optionsFor(index)}
                optionRender={(option) => {
                  const optionCli = (option.data as { data?: API.CliItem })?.data;
                  if (!optionCli) return null;
                  return (
                    <div style={{ padding: '4px 0' }}>
                      <div style={{ fontWeight: 500, fontSize: '14px', color: 'var(--vip-text-primary)' }}>
                        {optionCli.name}
                        {optionCli.version && (
                          <span style={{ marginLeft: 8, fontWeight: 400, fontSize: '12px', color: 'var(--vip-text-tertiary)' }}>
                            {optionCli.version}
                          </span>
                        )}
                      </div>
                      {optionCli.description && (
                        <div style={{ fontSize: '12px', color: 'var(--vip-text-secondary)', marginTop: '2px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {optionCli.description}
                        </div>
                      )}
                    </div>
                  );
                }}
              />
              {cli?.skill?.skillName && (
                <Tag color="blue" style={{ fontSize: '11px' }}>
                  {cli.skill.skillName}
                </Tag>
              )}
              <Button type="text" danger onClick={() => removeAt(index)}>
                {intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}
              </Button>
            </Space>

            {row.envEntries.length > 0 ? (
              <EnvParamTable
                entries={row.envEntries}
                bindings={row.envBindings}
                envVarOptions={envVarOptions}
                collapsed={collapsed.has(row.cliId)}
                onToggleCollapsed={() => toggleCollapsed(row.cliId)}
                onRowChange={(envIndex, next) => writeRow(row.cliId, envIndex, next)}
                showDefaultHint
              />
            ) : (
              <Text type="secondary" style={{ fontSize: 12 }}>
                {intl.formatMessage({
                  id: 'pages.agent.cli.noEnvParams',
                  defaultMessage: 'This CLI needs no environment variables.',
                })}
              </Text>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default CliConfigPanel;
