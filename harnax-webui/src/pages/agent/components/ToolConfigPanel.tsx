import { useIntl } from '@umijs/max';
import { Button, Input, Select, Space, Switch, Tag } from 'antd';
import React from 'react';
import { PlusOutlined, LockOutlined, DownOutlined, RightOutlined, EnvironmentOutlined } from '@ant-design/icons';

export type ToolConfigState = {
  toolId?: number;
  toolName?: string;
  needConfirm?: boolean;
  envEntries?: API.ToolEnvParamEntry[];
  envBindings?: { envKey: string; envValue: string; envVarId?: number; customInput?: boolean }[];
};

export type EnvVarOption = {
  id: number;
  envKey: string;
  displayValue: string;
  sensitive: boolean;
};

interface ToolConfigPanelProps {
  toolConfigs: ToolConfigState[];
  setToolConfigs: (configs: ToolConfigState[]) => void;
  tools: any[];
  envVarOptions: EnvVarOption[];
  collapsed: Set<number>;
  setCollapsed: (set: Set<number>) => void;
  locale?: string;
}

const ToolConfigPanel: React.FC<ToolConfigPanelProps> = ({
  toolConfigs,
  setToolConfigs,
  tools,
  envVarOptions,
  collapsed,
  setCollapsed,
  locale,
}) => {
  const intl = useIntl();

  // Group tools by type for OptGroup display
  const toolOptionsFor = (index: number) => {
    // 同一个工具选两次没有意义：后端 `distinctBy { it.toolId }` 只保留第一条，
    // 第二行填的环境变量覆盖值会静默丢掉。只挡别的行，本行自己已选的要留着，否则标题会渲染成裸 id。
    const selectable = tools.filter(
      (t: any) => !toolConfigs.some((other, otherIndex) => otherIndex !== index && other.toolId === t.id),
    );
    const label = (tool: any) =>
      (locale?.startsWith('zh') ? (tool.displayNameZh?.trim() || tool.displayName || tool.name) : (tool.displayName || tool.name));
    const groups: { label: string; options: { label: string; value: number }[] }[] = [];
    const builtinTools = selectable.filter((t: any) => t.type === 'BUILTIN');
    const customTools = selectable.filter((t: any) => t.type !== 'BUILTIN');
    if (builtinTools.length > 0) {
      groups.push({
        label: intl.formatMessage({ id: 'pages.agent.tool.groupBuiltin', defaultMessage: 'Built-in Tools' }),
        options: builtinTools.map((tool: any) => ({ label: label(tool), value: tool.id })),
      });
    }
    if (customTools.length > 0) {
      groups.push({
        label: intl.formatMessage({ id: 'pages.agent.tool.groupCustom', defaultMessage: 'Custom Tools' }),
        options: customTools.map((tool: any) => ({ label: label(tool), value: tool.id })),
      });
    }
    return groups;
  };

  const handleToolConfigChange = (index: number, field: string, value: any) => {
    const newConfigs = [...toolConfigs];
    (newConfigs[index] as any)[field] = value;
    if (field === 'toolId' && value) {
      const tool = tools.find((t: any) => t.id === value);
      if (tool) {
        newConfigs[index].toolName = tool.name;
        const envEntries: API.ToolEnvParamEntry[] = tool.envParams || [];
        newConfigs[index].envEntries = envEntries;
        if (envEntries.length > 0) {
          newConfigs[index].envBindings = envEntries.map((e: API.ToolEnvParamEntry) => ({
            envKey: e.envParamName,
            // 敏感项的 defaultValue 后端只给掩码（AgentToolServiceImpl.maskValue），照抄进来存的就是
            // `abc****wxyz` 这串字面量，还会随 spec 下发进 ToolEnvContext。留空才是诚实的默认值：
            // 要覆盖就选一个全局变量，或者自己填。与 McpConfigPanel 同一条规则。
            envValue: e.secret ? '' : e.defaultValue || '',
          }));
        } else {
          newConfigs[index].envBindings = [];
        }
      }
    }
    setToolConfigs(newConfigs);
  };

  const handleEnvBindingSelect = (index: number, envIndex: number, envVarId: number) => {
    const newConfigs = [...toolConfigs];
    const bindings = [...(newConfigs[index].envBindings || [])];
    if (envVarId === -1) {
      bindings[envIndex] = { ...bindings[envIndex], envVarId: undefined, envValue: '', customInput: true };
    } else {
      const selected = envVarOptions.find(opt => opt.id === envVarId);
      if (!selected) return;
      // 引用只落 envVarId，值由运行时按 id 现取：敏感项的 displayValue 是掩码，
      // 顺手存成 envValue 就等于把 `******` 写进快照，环境变量一旦被删，工具拿到的就是星号。
      bindings[envIndex] = { ...bindings[envIndex], envVarId: selected.id, envValue: '', customInput: undefined };
    }
    newConfigs[index].envBindings = bindings;
    setToolConfigs(newConfigs);
  };

  const handleCustomInputChange = (index: number, envIndex: number, value: string) => {
    const newConfigs = [...toolConfigs];
    const bindings = [...(newConfigs[index].envBindings || [])];
    bindings[envIndex] = { ...bindings[envIndex], envValue: value };
    newConfigs[index].envBindings = bindings;
    setToolConfigs(newConfigs);
  };

  const addToolConfig = () => setToolConfigs([...toolConfigs, {}]);
  const removeToolConfig = (index: number) => setToolConfigs(toolConfigs.filter((_, i) => i !== index));

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ color: 'var(--vip-text-secondary)', fontSize: '14px' }}>
          {intl.formatMessage({ id: 'pages.agent.toolConfigOptional', defaultMessage: 'Configure tools (optional, can be skipped)' })}
        </span>
        <Button type="dashed" icon={<PlusOutlined />} onClick={addToolConfig}>
          {intl.formatMessage({ id: 'pages.agent.tool.add', defaultMessage: 'Add Tool' })}
        </Button>
      </div>

      {toolConfigs.map((config, index) => (
        <div key={index} style={{ marginBottom: 16, padding: 12, border: '1px solid var(--vip-border)', borderRadius: 8, background: 'var(--vip-bg-layout)' }}>
          <Space style={{ display: 'flex', marginBottom: 8 }} align="baseline">
            <Select
              style={{ width: 200 }}
              placeholder={intl.formatMessage({ id: 'pages.agent.tool.select', defaultMessage: 'Select Tool' })}
              value={config.toolId}
              onChange={(value) => handleToolConfigChange(index, 'toolId', value)}
              options={toolOptionsFor(index)}
            />
            <span>
              {intl.formatMessage({ id: 'pages.agent.tool.needConfirm', defaultMessage: 'Need confirm' })}
              <Switch size="small" checked={config.needConfirm} onChange={(checked) => handleToolConfigChange(index, 'needConfirm', checked)} />
            </span>
            <Button type="text" danger onClick={() => removeToolConfig(index)}>
              {intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}
            </Button>
          </Space>

          {config.envEntries && config.envEntries.length > 0 && (
            <div style={{ marginTop: 8, paddingLeft: 12, borderLeft: '2px solid var(--vip-primary)' }}>
              <div
                style={{ cursor: 'pointer', userSelect: 'none', display: 'flex', alignItems: 'center', gap: 4, padding: '4px 0', color: 'var(--vip-text-secondary)', fontSize: 13 }}
                onClick={() => {
                  const next = new Set(collapsed);
                  next.has(index) ? next.delete(index) : next.add(index);
                  setCollapsed(next);
                }}
              >
                {collapsed.has(index) ? <RightOutlined style={{ fontSize: 10 }} /> : <DownOutlined style={{ fontSize: 10 }} />}
                <EnvironmentOutlined style={{ fontSize: 12, color: 'var(--vip-primary)' }} />
                <span style={{ fontWeight: 500 }}>{intl.formatMessage({ id: 'pages.agent.tool.envParamsCount', defaultMessage: 'Env Params' })} ({config.envEntries.length})</span>
              </div>
              {!collapsed.has(index) && (
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginTop: 4 }}>
                  <thead>
                    <tr style={{ background: 'var(--vip-bg-layout)', borderBottom: '1px solid var(--vip-border)' }}>
                      <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)', width: '30%' }}>
                        {intl.formatMessage({ id: 'pages.agent.tool.envParamName', defaultMessage: 'Param Name' })}
                      </th>
                      <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)', width: '35%' }}>
                        {intl.formatMessage({ id: 'pages.agent.tool.envVarName', defaultMessage: 'Env Variable' })}
                      </th>
                      <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)', width: '35%' }}>
                        {intl.formatMessage({ id: 'pages.agent.tool.envVarValue', defaultMessage: 'Value' })}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {(config.envBindings || []).map((binding, envIdx) => {
                      const envEntry = config.envEntries?.[envIdx];
                      const selectedEnvVar = binding.envVarId ? envVarOptions.find(opt => opt.id === binding.envVarId) : null;
                      return (
                        <tr key={envIdx} style={{ borderBottom: '1px solid var(--vip-border)' }}>
                          <td style={{ padding: '6px 8px', fontFamily: 'monospace', color: 'var(--vip-text-primary)' }}>
                            {binding.envKey}
                            {envEntry?.required && <Tag color="red" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', marginLeft: 4 }}>{intl.formatMessage({ id: 'pages.mcp.config.required', defaultMessage: '必填' })}</Tag>}
                            {envEntry?.secret && <Tag color="orange" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', marginLeft: 4 }}>{intl.formatMessage({ id: 'pages.mcp.config.secret', defaultMessage: '敏感' })}</Tag>}
                          </td>
                          <td style={{ padding: '6px 8px' }}>
                            <Select
                              size="small"
                              style={{ width: '100%' }}
                              value={binding.customInput ? -1 : binding.envVarId}
                              onChange={(val) => handleEnvBindingSelect(index, envIdx, val)}
                              placeholder={intl.formatMessage({ id: 'pages.agent.tool.selectEnvVar', defaultMessage: 'Select env variable' })}
                              allowClear
                              onClear={() => {
                                const newConfigs = [...toolConfigs];
                                const bindings = [...(newConfigs[index].envBindings || [])];
                                bindings[envIdx] = { ...bindings[envIdx], envVarId: undefined, envValue: '', customInput: undefined };
                                newConfigs[index].envBindings = bindings;
                                setToolConfigs(newConfigs);
                              }}
                              showSearch
                              filterOption={(input, option) =>
                                (option?.label as string)?.toLowerCase().includes(input.toLowerCase()) ?? false
                              }
                              options={[
                                ...envVarOptions.map(opt => ({ label: opt.envKey, value: opt.id })),
                                { label: `✏️ ${intl.formatMessage({ id: 'pages.agent.tool.customInput', defaultMessage: 'Custom' })}`, value: -1 },
                              ]}
                            />
                          </td>
                          <td style={{ padding: '6px 8px' }}>
                            {binding.customInput ? (
                              <Input
                                size="small"
                                value={binding.envValue}
                                onChange={(e) => handleCustomInputChange(index, envIdx, e.target.value)}
                                placeholder={intl.formatMessage({ id: 'pages.agent.tool.inputValue', defaultMessage: 'Enter value' })}
                                style={{ fontFamily: 'monospace' }}
                              />
                            ) : selectedEnvVar ? (
                              <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--vip-text-secondary)' }}>
                                {selectedEnvVar.sensitive ? (
                                  <span style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--vip-text-quaternary)' }}>
                                    <LockOutlined />
                                    {selectedEnvVar.displayValue}
                                  </span>
                                ) : (
                                  <span style={{ wordBreak: 'break-all' }}>{selectedEnvVar.displayValue}</span>
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
          )}
        </div>
      ))}
    </div>
  );
};

export default ToolConfigPanel;
