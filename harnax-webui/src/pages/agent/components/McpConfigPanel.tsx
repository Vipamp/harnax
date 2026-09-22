import { useIntl } from '@umijs/max';
import { Button, Select, Space } from 'antd';
import React from 'react';
import { MinusOutlined, PlusOutlined } from '@ant-design/icons';
import EnvParamTable, { EnvBindingRow, EnvVarOption } from './EnvParamTable';

export type McpConfigState = {
  mcpId?: number;
  mcpName?: string;
  envEntries?: API.ToolEnvParamEntry[];
  envBindings?: EnvBindingRow[];
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

    if (field === 'mcpId' && value) {
      const selectedMcp = mcpServers.find((mcp) => mcp.id === value);
      if (selectedMcp) {
        newConfigs[index].mcpName = selectedMcp.name;
        const envEntries = selectedMcp.envParams || [];
        newConfigs[index].envEntries = envEntries;
        // 只建行不带值：MCP 的声明默认值会随 spec 整份下发，留空就是「用它」。把默认值抄进
        // binding 反而把它冻在建 agent 那一刻的副本上，服务方日后改了默认值也追不过来。
        newConfigs[index].envBindings = envEntries.map((e) => ({
          envKey: e.envParamName,
          envValue: '',
        }));
      }
    }
    setMcpConfigs(newConfigs);
  };

  const writeRow = (index: number, envIndex: number, next: EnvBindingRow) => {
    const newConfigs = [...mcpConfigs];
    const bindings = [...(newConfigs[index].envBindings || [])];
    bindings[envIndex] = next;
    newConfigs[index] = { ...newConfigs[index], envBindings: bindings };
    setMcpConfigs(newConfigs);
  };

  const addMcpConfig = () => setMcpConfigs([...mcpConfigs, {}]);

  const removeMcpConfig = (index: number) => {
    if (mcpConfigs.length === 1) {
      return;
    }
    setMcpConfigs(mcpConfigs.filter((_, i) => i !== index));
  };

  const toggleCollapsed = (index: number) => {
    const next = new Set(collapsed);
    next.has(index) ? next.delete(index) : next.add(index);
    setCollapsed(next);
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
        <Space
          key={index}
          style={{ width: '100%', marginBottom: 16, padding: 16, border: '1px solid var(--vip-border)', borderRadius: '8px', background: 'var(--vip-bg-layout)' }}
          direction="vertical"
        >
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
            options={mcpServers
              // 同一个服务选两次没有意义：后端 `distinctBy { it.mcpId }` 只保留第一条，
              // 第二行填的环境变量覆盖值会静默丢掉。
              .filter((mcp) => !mcpConfigs.some((other, otherIndex) => otherIndex !== index && other.mcpId === mcp.id))
              .map((mcp) => ({ label: mcp.name, value: mcp.id }))}
          />

          {config.envEntries && config.envEntries.length > 0 && (
            <EnvParamTable
              entries={config.envEntries}
              bindings={config.envBindings || []}
              envVarOptions={envVarOptions}
              collapsed={collapsed.has(index)}
              onToggleCollapsed={() => toggleCollapsed(index)}
              onRowChange={(envIndex, next) => writeRow(index, envIndex, next)}
              showDefaultHint
            />
          )}
        </Space>
      ))}
    </div>
  );
};

export default McpConfigPanel;
