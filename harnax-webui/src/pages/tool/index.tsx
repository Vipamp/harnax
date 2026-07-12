import { PageContainer } from '@ant-design/pro-components';
import {
  Empty,
  Input,
  message,
  Popover,
  Result,
  Table,
  Tabs,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useMemo, useState } from 'react';
import { useIntl } from '@umijs/max';

import { getBuiltinTools } from '@/services/ant-design-pro/tool';
import {
  ToolOutlined,
  BuildOutlined,
  KeyOutlined,
  EnvironmentOutlined,
} from '@ant-design/icons';

// Get localized display name based on current locale
const getLocalizedToolName = (item: any, locale: string): string => {
  if (locale.startsWith('zh')) {
    return item.displayNameZh?.trim() || item.displayName?.trim() || item.name;
  }
  return item.displayName?.trim() || item.name;
};

const ToolManagement: React.FC = () => {
  const intl = useIntl();
  const locale = intl.locale;

  const [activeTab, setActiveTab] = useState<string>('toolbox');
  const [builtinTools, setBuiltinTools] = useState<any[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [keyword, setKeyword] = useState<string>('');
  const [messageApi, contextHolder] = message.useMessage();

  const loadBuiltinTools = async () => {
    setLoading(true);
    try {
      const response = await getBuiltinTools();
      if (response?.code === 200 && response?.data) {
        setBuiltinTools(response.data);
      }
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadBuiltinTools();
  }, []);

  // Front-end keyword filtering
  const filteredBuiltinTools = useMemo(() => {
    if (!keyword.trim()) return builtinTools;
    const kw = keyword.toLowerCase();
    return builtinTools.filter(
      (item: any) =>
        (item.name && item.name.toLowerCase().includes(kw)) ||
        (item.displayName && item.displayName.toLowerCase().includes(kw)) ||
        (item.displayNameZh && item.displayNameZh.toLowerCase().includes(kw)) ||
        (item.description && item.description.toLowerCase().includes(kw)),
    );
  }, [builtinTools, keyword]);

  const columns: ColumnsType<any> = [
    {
      title: intl.formatMessage({ id: 'pages.tool.name', defaultMessage: 'Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 200,
      render: (_: any, record: any) => {
        const localizedName = getLocalizedToolName(record, locale);
        const showTechName = localizedName !== record.name;
        return (
          <div>
            <div style={{ fontWeight: 500, color: 'var(--vip-text-primary)' }}>
              <BuildOutlined style={{ marginRight: 6, color: '#4f6ef7' }} />
              {localizedName}
            </div>
            {showTechName && (
              <div style={{ fontSize: 12, color: 'var(--vip-text-quaternary)', fontFamily: 'monospace', marginTop: 2 }}>
                {record.name}
              </div>
            )}
          </div>
        );
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.tool.description', defaultMessage: 'Description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => (
        <span style={{ color: 'var(--vip-text-secondary)', fontSize: 13 }}>
          {text || '-'}
        </span>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.tool.envParams', defaultMessage: 'Env Params' }),
      key: 'envParams',
      width: 120,
      align: 'center',
      render: (_: any, record: any) => {
        const envParams: any[] = record.envParams || [];
        const count = envParams.length || record.requiredEnvParamKeys?.length || 0;
        if (count <= 0) return <span style={{ color: 'var(--vip-text-quaternary)' }}>-</span>;

        const popoverContent = (
          <table style={{ borderCollapse: 'collapse', fontSize: 12, minWidth: 320 }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--vip-border)' }}>
                <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)' }}>
                  {intl.formatMessage({ id: 'pages.tool.envParamName', defaultMessage: 'Param Name' })}
                </th>
                <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 500, color: 'var(--vip-text-secondary)' }}>
                  {intl.formatMessage({ id: 'pages.tool.envParamDesc', defaultMessage: 'Description' })}
                </th>
                <th style={{ padding: '4px 8px', textAlign: 'center', fontWeight: 500, color: 'var(--vip-text-secondary)' }}>
                  {intl.formatMessage({ id: 'pages.tool.envParamRequired', defaultMessage: 'Required' })}
                </th>
                <th style={{ padding: '4px 8px', textAlign: 'center', fontWeight: 500, color: 'var(--vip-text-secondary)' }}>
                  {intl.formatMessage({ id: 'pages.tool.envParamSecret', defaultMessage: 'Sensitive' })}
                </th>
              </tr>
            </thead>
            <tbody>
              {envParams.map((param: any, idx: number) => (
                <tr key={idx} style={{ borderBottom: '1px solid var(--vip-border)' }}>
                  <td style={{ padding: '4px 8px', fontFamily: 'monospace', color: 'var(--vip-text-primary)' }}>{param.envParamName}</td>
                  <td style={{ padding: '4px 8px', color: 'var(--vip-text-secondary)', maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{param.description || '-'}</td>
                  <td style={{ padding: '4px 8px', textAlign: 'center' }}>{param.required ? <Tag color="red" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>Y</Tag> : <span style={{ color: 'var(--vip-text-quaternary)' }}>-</span>}</td>
                  <td style={{ padding: '4px 8px', textAlign: 'center' }}>{param.secret ? <Tag color="orange" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>Y</Tag> : <span style={{ color: 'var(--vip-text-quaternary)' }}>-</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        );

        return (
          <Popover content={popoverContent} trigger="hover" placement="bottomRight" overlayStyle={{ maxWidth: 520 }}>
            <Tag icon={<EnvironmentOutlined />} color="purple" style={{ cursor: 'pointer' }}>{count}</Tag>
          </Popover>
        );
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.tool.needConfirm', defaultMessage: 'Need Confirm' }),
      key: 'needConfirm',
      width: 120,
      align: 'center',
      render: (_: any, record: any) =>
        record.needConfirm === 1 ? (
          <Tag color="orange">{intl.formatMessage({ id: 'pages.tool.yes', defaultMessage: 'Yes' })}</Tag>
        ) : (
          <span style={{ color: 'var(--vip-text-quaternary)' }}>-</span>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.tool.required', defaultMessage: 'Required' }),
      key: 'isRequired',
      width: 100,
      align: 'center',
      render: (_: any, record: any) =>
        record.isRequired === 1 ? (
          <Tag color="red">{intl.formatMessage({ id: 'pages.tool.required', defaultMessage: 'Required' })}</Tag>
        ) : (
          <Tag>{intl.formatMessage({ id: 'pages.tool.optional', defaultMessage: 'Optional' })}</Tag>
        ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ToolOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.tool.title', defaultMessage: 'Tool Management' })}
          </span>
        ),
      }}
    >
      {contextHolder}
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'toolbox',
            label: (
              <span>
                <BuildOutlined style={{ marginRight: 6 }} />
                {intl.formatMessage({ id: 'pages.tool.marketplace', defaultMessage: 'Toolbox' })}
              </span>
            ),
            children: (
              <div>
                <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Input.Search
                    placeholder={intl.formatMessage({ id: 'pages.tool.searchPlaceholder', defaultMessage: 'Search tool name or description' })}
                    allowClear
                    value={keyword}
                    onChange={(e) => setKeyword(e.target.value)}
                    style={{ maxWidth: 400 }}
                  />
                </div>
                <Table
                  columns={columns}
                  dataSource={filteredBuiltinTools}
                  rowKey="id"
                  loading={loading}
                  pagination={false}
                  size="middle"
                  locale={{
                    emptyText: (
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={intl.formatMessage({ id: 'pages.tool.noTools', defaultMessage: 'No tools' })}
                      />
                    ),
                  }}
                />
              </div>
            ),
          },
          {
            key: 'custom',
            label: (
              <span>
                <KeyOutlined style={{ marginRight: 6 }} />
                {intl.formatMessage({ id: 'pages.tool.customTools', defaultMessage: 'Custom Tools' })}
              </span>
            ),
            children: (
              <Result
                status="info"
                title={intl.formatMessage({ id: 'pages.tool.comingSoon', defaultMessage: 'Coming soon' })}
                style={{ marginTop: 80 }}
              />
            ),
          },
        ]}
      />
    </PageContainer>
  );
};

export default ToolManagement;
