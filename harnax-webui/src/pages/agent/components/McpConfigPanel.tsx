import { useIntl } from '@umijs/max';
import { Button, Input, Select, Space, Switch, Tag } from 'antd';
import React from 'react';
import { PlusOutlined, MinusOutlined, LockOutlined, DownOutlined, RightOutlined, EnvironmentOutlined } from '@ant-design/icons';

export type McpConfigState = {
  mcpId?: number;
  mcpName?: string;
  enableSkip?: boolean;
  envEntries?: API.ToolEnvParamEntry[];
  envBindings?: { envKey: string; envValue: string; envVarId?: number; customInput?: boolean }[];
};

export type EnvVarOption = {
  id: number;
  envKey: string;
  displayValue: string;
  sensitive: boolean;
};

interface McpConfigPanelProps {
  mcpConfigs: McpConfigState[];
  setMcpConfigs: (configs: McpConfigState[]) => void;
  mcpServers: API.McpServerItem[];
  envVarOptions: EnvVarOption[];
  collapsed: Set<number>;
  setCollapsed: (set: Set<number>) => void;
}

const McpConfigPanel: React.FC<McpConfigPanelProps> = ({
  mcpConfigs,
  setMcpConfigs,
  mcpServers,
  envVarOptions,
  collapsed,
  setCollapsed,
}) => {
  const intl = useIntl();

  const handleMcpConfigChange = (index: number, field: string, value: any) => {
    const newConfigs = [...mcpConfigs];
    newConfigs[index] = { ...newConfigs[index], [field]: value };
    setMcpConfigs(newConfigs);

    if (field === 'mcpId' && value) {
      const selectedMcp = mcpServers.find(mcp => mcp.id === value);
      if (selectedMcp) {
        newConfigs[index].mcpName = selectedMcp.name;
        const envEntries = selectedMcp.envParams || [];
        newConfigs[index].envEntries = envEntries;
        if (envEntries.length > 0) {
          newConfigs[index].envBindings = envEntries.map(e => ({ envKey: e.envParamName, envValue: e.defaultValue || '' }));
        } else {
          newConfigs[index].envBindings = [];
        }
        setMcpConfigs(newConfigs);
      }
    }
  };

  const addMcpConfig = () => setMcpConfigs([...mcpConfigs, {}]);

  const removeMcpConfig = (index: number) => {
    if (mcpConfigs.length === 1) {
      return;
    }
    setMcpConfigs(mcpConfigs.filter((_, i) => i !== index));
  };

  const handleEnvBindingSelect = (index: number, envIdx: number, envVarId: number) => {
    const newConfigs = [...mcpConfigs];
    const bindings = [...(newConfigs[index].envBindings || [])];
    if (envVarId === -1) {
      bindings[envIdx] = { ...bindings[envIdx], envVarId: undefined, envValue: '', customInput: true };
    } else {
      const selected = envVarOptions.find(opt => opt.id === envVarId);
      if (!selected) return;
      bindings[envIdx] = { ...bindings[envIdx], envVarId: selected.id, envValue: selected.displayValue, customInput: undefined };
    }
    newConfigs[index].envBindings = bindings;
    setMcpConfigs(newConfigs);
  };

  const handleCustomInputChange = (index: number, envIdx: number, value: string) => {
    const newConfigs = [...mcpConfigs];
    const bindings = [...(newConfigs[index].envBindings || [])];
    bindings[envIdx] = { ...bindings[envIdx], envValue: value };
    newConfigs[index].envBindings = bindings;
    setMcpConfigs(newConfigs);
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ color: 'var(--vip-text-secondary)', fontSize: '14px' }}>
          {intl.formatMessage({ id: 'pages.agent.mcpOptional', defaultMessage: 'Configure MCP services (optional, can be skipped)' })}
        </span>
        <Button type="dashed" icon={<PlusOutlined />} onClick={addMcpConfig}>
          {intl.formatMessage({ id: 'pages.agent.addMcp', defaultMessage: 'Add MCP' })}
        </Button>
      </div>

      {mcpConfigs.map((config, index) => (
        <Space key={index} style={{ width: '100%', marginBottom: 16, padding: 16, border: '1px solid var(--vip-border)', borderRadius: '8px', background: 'var(--vip-bg-layout)' }} direction="vertical">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <span style={{ fontWeight: 500, color: 'var(--vip-text-primary)', fontSize: '14px' }}>
              {intl.formatMessage({ id: 'pages.agent.mcp', defaultMessage: 'MCP' })} #{index + 1}
            </span>
            {mcpConfigs.length > 1 && (
              <Button type="link" danger icon={<MinusOutlined />} onClick={() => removeMcpConfig(index)} size="small">
                {intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}
              </Button>
            )}
          </div>
          <Select
            placeholder={intl.formatMessage({ id: 'pages.agent.mcpPlaceholder', defaultMessage: 'Select MCP service (optional, can be skipped)' })}
            value={config.mcpId}
            onChange={(value) => handleMcpConfigChange(index, 'mcpId', value)}
            allowClear
            options={mcpServers.map(mcp => ({ label: mcp.name, value: mcp.id }))}
          />
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ color: 'var(--vip-text-primary)', fontSize: '14px' }}>
              {intl.formatMessage({ id: 'pages.agent.allowSkip', defaultMessage: 'Allow Skip' })}
            </span>
            <Switch size="small" checked={config.enableSkip} onChange={(checked) => handleMcpConfigChange(index, 'enableSkip', checked)} />
          </div>

          {/* MCP Env Bindings */}
          {config.envEntries && config.envEntries.length > 0 && (
            <div style={{ paddingLeft: 12, borderLeft: '2px solid var(--vip-primary)' }}>
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
                                const newConfigs = [...mcpConfigs];
                                const bindings = [...(newConfigs[index].envBindings || [])];
                                bindings[envIdx] = { ...bindings[envIdx], envVarId: undefined, envValue: '', customInput: undefined };
                                newConfigs[index].envBindings = bindings;
                                setMcpConfigs(newConfigs);
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
        </Space>
      ))}
    </div>
  );
};

export default McpConfigPanel;
