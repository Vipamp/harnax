import React from 'react';
import { Button, Input, Switch, Space, Tooltip } from 'antd';
import { PlusOutlined, DeleteOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

export interface ToolEnvParamEntry {
  envParamName: string;
  required: boolean;
  secret: boolean;
  defaultValue: string;
}

interface ToolEnvEntriesEditorProps {
  value?: ToolEnvParamEntry[];
  onChange?: (value: ToolEnvParamEntry[]) => void;
}

/**
 * Tool environment parameter editor with name/required/secret/defaultValue fields.
 * Used for configuring environment parameters for tools.
 */
const ToolEnvEntriesEditor: React.FC<ToolEnvEntriesEditorProps> = ({
  value = [],
  onChange,
}) => {
  const intl = useIntl();

  const handleAdd = () => {
    const newEntries = [...value, { envParamName: '', required: false, secret: false, defaultValue: '' }];
    onChange?.(newEntries);
  };

  const handleRemove = (index: number) => {
    const newEntries = value.filter((_, i) => i !== index);
    onChange?.(newEntries);
  };

  const handleChange = (index: number, field: keyof ToolEnvParamEntry, val: string | boolean) => {
    const newEntries = value.map((entry, i) => {
      if (i !== index) return entry;
      const updated = { ...entry, [field]: val };
      // When switching required off, clear defaultValue requirement visual cue
      if (field === 'secret' && val === true) {
        // keep defaultValue as-is
      }
      return updated;
    });
    onChange?.(newEntries);
  };

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', alignItems: 'center', gap: 4 }}>
        <span style={{ fontSize: 12, color: 'var(--vip-text-secondary)' }}>
          {intl.formatMessage({ id: 'pages.tool.envParamsTip', defaultMessage: '环境参数用于配置工具或 MCP 服务运行时需要的外部变量，如 API Key、数据库地址等。标记为「必填」的参数必须填写默认值，「敏感」参数的值将以密码形式展示。' })}
        </span>
        <Tooltip title={intl.formatMessage({ id: 'pages.tool.envParamsTipTitle', defaultMessage: '环境参数在运行时注入到进程环境变量中，供工具或 MCP 服务读取使用。' })}>
          <QuestionCircleOutlined style={{ fontSize: 12, color: 'var(--vip-text-secondary)', cursor: 'pointer' }} />
        </Tooltip>
      </div>
      {value.map((entry, index) => (
        <div key={index} style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'flex-start', flexWrap: 'nowrap' }}>
          <Input
            placeholder={intl.formatMessage({ id: 'pages.tool.envNamePlaceholder', defaultMessage: 'ENV_NAME' })}
            value={entry.envParamName}
            onChange={(e) => handleChange(index, 'envParamName', e.target.value)}
            style={{ width: 130, flexShrink: 0 }}
          />
          <Input
            placeholder={intl.formatMessage({ id: 'pages.tool.envDefaultValue', defaultMessage: 'Default value' })}
            value={entry.defaultValue}
            type={entry.secret ? 'password' : 'text'}
            onChange={(e) => handleChange(index, 'defaultValue', e.target.value)}
            style={{ flex: 1, minWidth: 80 }}
          />
          <Tooltip title={intl.formatMessage({ id: 'pages.tool.envRequired', defaultMessage: 'Required' })}>
            <Space size={2}>
              <Switch
                size="small"
                checked={entry.required}
                onChange={(checked) => handleChange(index, 'required', checked)}
              />
              <span style={{ fontSize: 11, color: 'var(--vip-text-secondary)', whiteSpace: 'nowrap' }}>
                {intl.formatMessage({ id: 'pages.tool.envRequiredLabel', defaultMessage: 'Required' })}
              </span>
            </Space>
          </Tooltip>
          <Tooltip title={intl.formatMessage({ id: 'pages.tool.envSecret', defaultMessage: 'Sensitive' })}>
            <Space size={2}>
              <Switch
                size="small"
                checked={entry.secret}
                onChange={(checked) => handleChange(index, 'secret', checked)}
              />
              <span style={{ fontSize: 11, color: 'var(--vip-text-secondary)', whiteSpace: 'nowrap' }}>
                {intl.formatMessage({ id: 'pages.tool.envSecretLabel', defaultMessage: 'Secret' })}
              </span>
            </Space>
          </Tooltip>
          <Button
            type="text"
            danger
            size="small"
            icon={<DeleteOutlined />}
            onClick={() => handleRemove(index)}
            style={{ flexShrink: 0, marginTop: 2 }}
          />
        </div>
      ))}
      <Button
        type="dashed"
        onClick={handleAdd}
        block
        icon={<PlusOutlined />}
        size="small"
      >
        {intl.formatMessage({ id: 'pages.tool.addEnvParam', defaultMessage: 'Add Environment Parameter' })}
      </Button>
    </div>
  );
};

export default ToolEnvEntriesEditor;
