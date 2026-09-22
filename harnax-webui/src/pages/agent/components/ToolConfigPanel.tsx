import { useIntl } from '@umijs/max';
import { Button, Select, Space, Switch } from 'antd';
import React from 'react';
import { PlusOutlined } from '@ant-design/icons';
import EnvParamTable, { EnvBindingRow, EnvVarOption } from './EnvParamTable';

export type ToolConfigState = {
  toolId?: number;
  toolName?: string;
  needConfirm?: boolean;
  envEntries?: API.ToolEnvParamEntry[];
  envBindings?: EnvBindingRow[];
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

  const toolOptionsFor = (index: number) => {
    // 同一个工具选两次没有意义：后端 `distinctBy { it.toolId }` 只保留第一条，
    // 第二行填的环境变量覆盖值会静默丢掉。只挡别的行，本行自己已选的要留着，否则标题会渲染成裸 id。
    const selectable = tools.filter(
      (t: any) => !toolConfigs.some((other, otherIndex) => otherIndex !== index && other.toolId === t.id),
    );
    const label = (tool: any) =>
      (locale?.startsWith('zh') ? (tool.displayNameZh?.trim() || tool.displayName || tool.name) : (tool.displayName || tool.name));
    // 工具现在只剩内置一种来源，不再按类型分组
    return selectable.map((tool: any) => ({ label: label(tool), value: tool.id }));
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

  const writeRow = (index: number, envIndex: number, next: EnvBindingRow) => {
    const newConfigs = [...toolConfigs];
    const bindings = [...(newConfigs[index].envBindings || [])];
    bindings[envIndex] = next;
    newConfigs[index] = { ...newConfigs[index], envBindings: bindings };
    setToolConfigs(newConfigs);
  };

  const addToolConfig = () => setToolConfigs([...toolConfigs, {}]);
  const removeToolConfig = (index: number) => setToolConfigs(toolConfigs.filter((_, i) => i !== index));

  const toggleCollapsed = (index: number) => {
    const next = new Set(collapsed);
    next.has(index) ? next.delete(index) : next.add(index);
    setCollapsed(next);
  };

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
            <EnvParamTable
              entries={config.envEntries}
              bindings={config.envBindings || []}
              envVarOptions={envVarOptions}
              collapsed={collapsed.has(index)}
              onToggleCollapsed={() => toggleCollapsed(index)}
              onRowChange={(envIndex, next) => writeRow(index, envIndex, next)}
            />
          )}
        </div>
      ))}
    </div>
  );
};

export default ToolConfigPanel;
