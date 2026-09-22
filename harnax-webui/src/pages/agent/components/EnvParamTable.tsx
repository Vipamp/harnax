import { useIntl } from '@umijs/max';
import { Input, Select, Tag, Tooltip } from 'antd';
import React from 'react';
import { DownOutlined, EnvironmentOutlined, LockOutlined, RightOutlined } from '@ant-design/icons';

export type EnvVarOption = {
  id: number;
  envKey: string;
  displayValue: string;
  sensitive: boolean;
};

/** 面板本地编辑态：引用型只带 `envVarId`，`customInput` 表示值由人直接敲 */
export type EnvBindingRow = {
  envKey: string;
  envValue: string;
  envVarId?: number;
  customInput?: boolean;
};

interface EnvParamTableProps {
  entries: API.ToolEnvParamEntry[];
  bindings: EnvBindingRow[];
  envVarOptions: EnvVarOption[];
  collapsed: boolean;
  onToggleCollapsed: () => void;
  /** 只回传变化的那一行，写回何种 state 形状由调用方决定 */
  onRowChange: (envIndex: number, next: EnvBindingRow) => void;
  /**
   * 留空时是否灰显声明里的默认值。MCP 与 CLI 包会把默认值整份下发，留空确实拿得到它；
   * 内置工具的 `default_value` 到不了运行时，对它显示这一行就是在承诺一个不会发生的事。
   */
  showDefaultHint?: boolean;
}

/** 选项列表里「自定义输入」这一项的哨兵值：真实环境变量 id 从 1 开始 */
const CUSTOM_INPUT = -1;

const headerCell: React.CSSProperties = {
  padding: '6px 8px',
  textAlign: 'left',
  fontWeight: 500,
  color: 'var(--vip-text-secondary)',
};

/**
 * 环境参数填写表 —— 工具、MCP、CLI 三类配置共用的一张表。
 *
 * 一行一个声明出来的参数，值要么引用全局环境变量（只存指针，运行时按 id 现取），要么当场敲。
 */
const EnvParamTable: React.FC<EnvParamTableProps> = ({
  entries,
  bindings,
  envVarOptions,
  collapsed,
  onToggleCollapsed,
  onRowChange,
  showDefaultHint,
}) => {
  const intl = useIntl();

  const pickReference = (envIndex: number, envVarId: number) => {
    const current = bindings[envIndex];
    if (envVarId === CUSTOM_INPUT) {
      onRowChange(envIndex, { ...current, envVarId: undefined, envValue: '', customInput: true });
      return;
    }
    const selected = envVarOptions.find((opt) => opt.id === envVarId);
    if (!selected) return;
    // 引用只落 envVarId：敏感变量的 displayValue 是掩码，顺手存成 envValue 等于把 `******` 写进
    // 快照，变量一旦被删，运行时拿到的就是这串星号。
    onRowChange(envIndex, { ...current, envVarId: selected.id, envValue: '', customInput: undefined });
  };

  return (
    <div style={{ marginTop: 8, paddingLeft: 12, borderLeft: '2px solid var(--vip-primary)' }}>
      <div
        style={{
          cursor: 'pointer',
          userSelect: 'none',
          display: 'flex',
          alignItems: 'center',
          gap: 4,
          padding: '4px 0',
          color: 'var(--vip-text-secondary)',
          fontSize: 13,
        }}
        onClick={onToggleCollapsed}
      >
        {collapsed ? <RightOutlined style={{ fontSize: 10 }} /> : <DownOutlined style={{ fontSize: 10 }} />}
        <EnvironmentOutlined style={{ fontSize: 12, color: 'var(--vip-primary)' }} />
        <span style={{ fontWeight: 500 }}>
          {intl.formatMessage({ id: 'pages.agent.tool.envParamsCount', defaultMessage: 'Env Params' })} ({entries.length})
        </span>
      </div>
      {collapsed ? null : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginTop: 4 }}>
          <thead>
            <tr style={{ background: 'var(--vip-bg-layout)', borderBottom: '1px solid var(--vip-border)' }}>
              <th style={{ ...headerCell, width: '30%' }}>
                {intl.formatMessage({ id: 'pages.agent.tool.envParamName', defaultMessage: 'Param Name' })}
              </th>
              <th style={{ ...headerCell, width: '35%' }}>
                {intl.formatMessage({ id: 'pages.agent.tool.envVarName', defaultMessage: 'Env Variable' })}
              </th>
              <th style={{ ...headerCell, width: '35%' }}>
                {intl.formatMessage({ id: 'pages.agent.tool.envVarValue', defaultMessage: 'Value' })}
              </th>
            </tr>
          </thead>
          <tbody>
            {bindings.map((binding, envIndex) => {
              const entry = entries[envIndex];
              const referenced = binding.envVarId ? envVarOptions.find((opt) => opt.id === binding.envVarId) : null;
              const hintedDefault = showDefaultHint && !binding.customInput && !referenced ? entry?.defaultValue : undefined;
              return (
                <tr key={envIndex} style={{ borderBottom: '1px solid var(--vip-border)' }}>
                  <td style={{ padding: '6px 8px', fontFamily: 'monospace', color: 'var(--vip-text-primary)' }}>
                    <Tooltip title={entry?.description}>
                      <span>{binding.envKey}</span>
                    </Tooltip>
                    {entry?.required && (
                      <Tag color="red" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', marginLeft: 4 }}>
                        {intl.formatMessage({ id: 'pages.mcp.config.required', defaultMessage: '必填' })}
                      </Tag>
                    )}
                    {entry?.secret && (
                      <Tag color="orange" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', marginLeft: 4 }}>
                        {intl.formatMessage({ id: 'pages.mcp.config.secret', defaultMessage: '敏感' })}
                      </Tag>
                    )}
                  </td>
                  <td style={{ padding: '6px 8px' }}>
                    <Select
                      size="small"
                      style={{ width: '100%' }}
                      value={binding.customInput ? CUSTOM_INPUT : binding.envVarId}
                      onChange={(val) => pickReference(envIndex, val)}
                      placeholder={intl.formatMessage({ id: 'pages.agent.tool.selectEnvVar', defaultMessage: 'Select env variable' })}
                      allowClear
                      onClear={() =>
                        onRowChange(envIndex, { ...binding, envVarId: undefined, envValue: '', customInput: undefined })
                      }
                      showSearch
                      filterOption={(input, option) =>
                        (option?.label as string)?.toLowerCase().includes(input.toLowerCase()) ?? false
                      }
                      options={[
                        ...envVarOptions.map((opt) => ({ label: opt.envKey, value: opt.id })),
                        {
                          label: `✏️ ${intl.formatMessage({ id: 'pages.agent.tool.customInput', defaultMessage: 'Custom' })}`,
                          value: CUSTOM_INPUT,
                        },
                      ]}
                    />
                  </td>
                  <td style={{ padding: '6px 8px' }}>
                    {binding.customInput ? (
                      <Input
                        size="small"
                        // 敏感参数的值就是给别人看的口令，明文回填在共享输入框里等于默认让人当面输
                        type={entry?.secret ? 'password' : 'text'}
                        value={binding.envValue}
                        onChange={(e) => onRowChange(envIndex, { ...binding, envValue: e.target.value })}
                        placeholder={intl.formatMessage({ id: 'pages.agent.tool.inputValue', defaultMessage: 'Enter value' })}
                        style={{ fontFamily: 'monospace' }}
                      />
                    ) : referenced ? (
                      <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--vip-text-secondary)' }}>
                        {referenced.sensitive ? (
                          <span style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--vip-text-quaternary)' }}>
                            <LockOutlined />
                            {referenced.displayValue}
                          </span>
                        ) : (
                          <span style={{ wordBreak: 'break-all' }}>{referenced.displayValue}</span>
                        )}
                      </span>
                    ) : hintedDefault ? (
                      <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--vip-text-quaternary)' }}>
                        {intl.formatMessage(
                          { id: 'pages.agent.envParam.defaultHint', defaultMessage: 'Package default: {value}' },
                          { value: hintedDefault },
                        )}
                      </span>
                    ) : (
                      <span style={{ color: 'var(--vip-text-quaternary)' }}>-</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
};

export default EnvParamTable;
