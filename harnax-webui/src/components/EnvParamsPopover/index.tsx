import { EnvironmentOutlined } from '@ant-design/icons';
import { Popover, Tag } from 'antd';
import { useIntl } from '@umijs/max';
import React from 'react';

const cell = { padding: '4px 8px' } as const;
const headCell = { ...cell, fontWeight: 500, color: 'var(--vip-text-secondary)' } as const;
const flagTag = { fontSize: 10, lineHeight: '16px', padding: '0 4px' } as const;

type EnvParamsPopoverProps = {
  entries?: API.ToolEnvParamEntry[];
  /**
   * Count to show when the caller has no declarations to render, only how many params exist.
   */
  fallbackCount?: number;
};

/**
 * Env parameter declarations as a count badge plus a hover table — the shape the tool list uses.
 */
const EnvParamsPopover: React.FC<EnvParamsPopoverProps> = ({ entries, fallbackCount = 0 }) => {
  const intl = useIntl();
  const params = entries ?? [];
  const count = params.length || fallbackCount;
  if (count <= 0)
    return (
      <span style={{ color: 'var(--vip-text-quaternary)' }}>
        -
      </span>
    );

  const th = (label: string, align: 'left' | 'center') => (
    <th style={{ ...headCell, textAlign: align }}>{label}</th>
  );
  const flag = (on: boolean | undefined, color: string) =>
    on ? (
      <Tag color={color} style={flagTag}>
        Y
      </Tag>
    ) : (
      <span style={{ color: 'var(--vip-text-quaternary)' }}>-</span>
    );

  const popoverContent = (
    <table style={{ borderCollapse: 'collapse', fontSize: 12, minWidth: 380 }}>
      <thead>
        <tr style={{ borderBottom: '1px solid var(--vip-border)' }}>
          {th(intl.formatMessage({ id: 'pages.tool.envParamName', defaultMessage: 'Param Name' }), 'left')}
          {th(intl.formatMessage({ id: 'pages.tool.envParamDesc', defaultMessage: 'Description' }), 'left')}
          {th(intl.formatMessage({ id: 'pages.tool.envParamRequired', defaultMessage: 'Required' }), 'center')}
          {th(intl.formatMessage({ id: 'pages.tool.envParamSecret', defaultMessage: 'Sensitive' }), 'center')}
          {th(intl.formatMessage({ id: 'pages.tool.envParamDefault', defaultMessage: 'Default' }), 'left')}
        </tr>
      </thead>
      <tbody>
        {params.map((param) => (
          <tr key={param.envParamName} style={{ borderBottom: '1px solid var(--vip-border)' }}>
            <td style={{ ...cell, fontFamily: 'monospace', color: 'var(--vip-text-primary)' }}>{param.envParamName}</td>
            <td
              style={{
                ...cell,
                color: 'var(--vip-text-secondary)',
                maxWidth: 180,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {param.description || '-'}
            </td>
            <td style={{ ...cell, textAlign: 'center' }}>{flag(param.required, 'red')}</td>
            <td style={{ ...cell, textAlign: 'center' }}>{flag(param.secret, 'orange')}</td>
            <td
              style={{
                ...cell,
                fontFamily: 'monospace',
                color: 'var(--vip-text-secondary)',
                maxWidth: 140,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
              title={param.defaultValue}
            >
              {param.defaultValue || '-'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );

  return (
    <Popover
      content={popoverContent}
      trigger="hover"
      placement="bottomRight"
      overlayStyle={{ maxWidth: 560 }}
    >
      <Tag icon={<EnvironmentOutlined />} color="purple" style={{ cursor: 'pointer' }}>
        {count}
      </Tag>
    </Popover>
  );
};

export default EnvParamsPopover;
