import { useIntl } from '@umijs/max';
import { Select, Tag } from 'antd';
import React from 'react';

interface CliConfigPanelProps {
  selectedCliIds: number[];
  setSelectedCliIds: (ids: number[]) => void;
  clis: API.CliItem[];
}

/**
 * Agent CLI selection panel — multi-select only.
 * CLI-skill association is managed on the CLI management page; skills bound to a
 * selected CLI are loaded automatically by the backend at agent runtime.
 */
const CliConfigPanel: React.FC<CliConfigPanelProps> = ({ selectedCliIds, setSelectedCliIds, clis }) => {
  const intl = useIntl();

  return (
    <div>
      <div style={{ marginBottom: 16, color: 'var(--vip-text-secondary)', fontSize: '14px' }}>
        {intl.formatMessage({
          id: 'pages.agent.cliConfigOptional',
          defaultMessage: 'Select CLI tools (optional). They will be installed into the sandbox automatically, and their associated skills will be loaded at runtime.',
        })}
      </div>
      <Select
        mode="multiple"
        allowClear
        style={{ width: '100%' }}
        placeholder={intl.formatMessage({ id: 'pages.agent.cliPlaceholder', defaultMessage: 'Select CLI tools' })}
        value={selectedCliIds}
        onChange={setSelectedCliIds}
        optionFilterProp="label"
        optionRender={(option) => {
          const cli = (option.data as { data?: API.CliItem })?.data;
          if (!cli) return null;
          return (
            <div style={{ padding: '4px 0' }}>
              <div style={{ fontWeight: 500, fontSize: '14px', color: 'var(--vip-text-primary)' }}>
                {cli.name}
                {cli.version && <span style={{ marginLeft: 8, fontWeight: 400, fontSize: '12px', color: 'var(--vip-text-tertiary)' }}>{cli.version}</span>}
              </div>
              {cli.description && (
                <div style={{ fontSize: '12px', color: 'var(--vip-text-secondary)', marginTop: '2px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {cli.description}
                </div>
              )}
              {cli.skillList && cli.skillList.length > 0 && (
                <div style={{ marginTop: '4px' }}>
                  {cli.skillList.map((s) => (
                    <Tag key={s.skillId} color="blue" style={{ fontSize: '11px' }}>
                      {s.skillName}
                    </Tag>
                  ))}
                </div>
              )}
            </div>
          );
        }}
        options={clis
          .filter((cli) => cli.id != null)
          .map((cli) => ({ label: cli.name, value: cli.id!, data: cli }))}
      />
    </div>
  );
};

export default CliConfigPanel;
