import React from 'react';
import { Button, Input, Switch, Space } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

export interface ConfigEntry {
  key: string;
  value: string;
  secret: boolean;
}

interface ConfigEntriesEditorProps {
  value?: ConfigEntry[];
  onChange?: (value: ConfigEntry[]) => void;
  placeholder?: { key?: string; value?: string };
}

/**
 * 动态 Key-Value 配置编辑器，支持敏感值标记
 * 用于 MCP 服务的 Headers 和环境参数配置
 */
const ConfigEntriesEditor: React.FC<ConfigEntriesEditorProps> = ({
  value = [],
  onChange,
  placeholder,
}) => {
  const intl = useIntl();

  const handleAdd = () => {
    const newEntries = [...value, { key: '', value: '', secret: false }];
    onChange?.(newEntries);
  };

  const handleRemove = (index: number) => {
    const newEntries = value.filter((_, i) => i !== index);
    onChange?.(newEntries);
  };

  const handleChange = (index: number, field: keyof ConfigEntry, val: string | boolean) => {
    const newEntries = value.map((entry, i) =>
      i === index ? { ...entry, [field]: val } : entry,
    );
    onChange?.(newEntries);
  };

  return (
    <div>
      {value.map((entry, index) => (
        <Space key={index} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
          <Input
            placeholder={placeholder?.key || 'Key'}
            value={entry.key}
            onChange={(e) => handleChange(index, 'key', e.target.value)}
            style={{ width: 140 }}
          />
          <Input
            placeholder={placeholder?.value || 'Value'}
            value={entry.value}
            type={entry.secret ? 'password' : 'text'}
            onChange={(e) => handleChange(index, 'value', e.target.value)}
            style={{ width: 180 }}
          />
          <span style={{ fontSize: 12, color: 'var(--vip-text-secondary)' }}>
            {intl.formatMessage({ id: 'pages.mcp.config.secret', defaultMessage: '敏感' })}
          </span>
          <Switch
            size="small"
            checked={entry.secret}
            onChange={(checked) => handleChange(index, 'secret', checked)}
          />
          <Button
            type="text"
            danger
            size="small"
            icon={<DeleteOutlined />}
            onClick={() => handleRemove(index)}
          />
        </Space>
      ))}
      <Button
        type="dashed"
        onClick={handleAdd}
        block
        icon={<PlusOutlined />}
        size="small"
      >
        {intl.formatMessage({ id: 'pages.mcp.config.addEntry', defaultMessage: '添加配置项' })}
      </Button>
    </div>
  );
};

export default ConfigEntriesEditor;
