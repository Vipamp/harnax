import { PageContainer } from '@ant-design/pro-components';
import {
  Empty,
  Input,
  message,
  Result,
  Switch,
  Table,
  Tabs,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useMemo, useState } from 'react';
import { useIntl } from '@umijs/max';
import { useIsMobile } from '@/utils/responsive';

import { getCliPlugins, toggleCliPlugin } from '@/services/ant-design-pro/cliPlugin';
import {
  CodeOutlined,
  BuildOutlined,
  KeyOutlined,
} from '@ant-design/icons';

const getLocalizedName = (item: any, locale: string): string => {
  if (locale.startsWith('zh')) {
    return item.displayNameZh?.trim() || item.displayName?.trim() || item.name;
  }
  return item.displayName?.trim() || item.name;
};

const CliPluginManagement: React.FC = () => {
  const intl = useIntl();
  const locale = intl.locale;
  const isMobile = useIsMobile();

  const [activeTab, setActiveTab] = useState<string>('system');
  const [plugins, setPlugins] = useState<any[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [keyword, setKeyword] = useState<string>('');
  const [messageApi, contextHolder] = message.useMessage();

  const loadPlugins = async () => {
    setLoading(true);
    try {
      const response = await getCliPlugins({ type: 'SYSTEM' });
      if (response?.code === 200 && response?.data) {
        setPlugins(response.data);
      }
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadPlugins();
  }, []);

  const handleToggle = async (record: any, checked: boolean) => {
    try {
      const response = await toggleCliPlugin(record.id, checked ? 1 : 0);
      if (response?.code === 200) {
        messageApi.success(intl.formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }));
        loadPlugins();
      } else {
        messageApi.error(response?.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
      }
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
    }
  };

  const filteredPlugins = useMemo(() => {
    if (!keyword.trim()) return plugins;
    const kw = keyword.toLowerCase();
    return plugins.filter(
      (item: any) =>
        (item.name && item.name.toLowerCase().includes(kw)) ||
        (item.displayName && item.displayName.toLowerCase().includes(kw)) ||
        (item.displayNameZh && item.displayNameZh.toLowerCase().includes(kw)) ||
        (item.description && item.description.toLowerCase().includes(kw)),
    );
  }, [plugins, keyword]);

  const columns: ColumnsType<any> = [
    {
      title: intl.formatMessage({ id: 'pages.cliPlugin.name', defaultMessage: 'Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 200,
      render: (_: any, record: any) => {
        const localizedName = getLocalizedName(record, locale);
        const showTechName = localizedName !== record.name;
        return (
          <div>
            <div style={{ fontWeight: 500, color: 'var(--vip-text-primary)' }}>
              <CodeOutlined style={{ marginRight: 6, color: '#4f6ef7' }} />
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
      title: intl.formatMessage({ id: 'pages.cliPlugin.description', defaultMessage: 'Description' }),
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
      title: intl.formatMessage({ id: 'pages.cliPlugin.version', defaultMessage: 'Version' }),
      dataIndex: 'version',
      key: 'version',
      width: 100,
      align: 'center',
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--vip-text-secondary)' }}>
          {text || '-'}
        </span>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.cliPlugin.healthCheck', defaultMessage: 'Health Check' }),
      dataIndex: 'healthCheck',
      key: 'healthCheck',
      width: 160,
      render: (text: string) => (
        <code style={{ fontSize: 12, color: 'var(--vip-text-secondary)', background: 'var(--vip-bg-layout)', padding: '2px 6px', borderRadius: 4 }}>
          {text || '-'}
        </code>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.cliPlugin.status', defaultMessage: 'Status' }),
      key: 'status',
      width: 100,
      align: 'center',
      render: (_: any, record: any) => (
        <Switch
          checked={record.status === 1}
          onChange={(checked) => handleToggle(record, checked)}
          checkedChildren={intl.formatMessage({ id: 'pages.cliPlugin.enabled', defaultMessage: 'On' })}
          unCheckedChildren={intl.formatMessage({ id: 'pages.cliPlugin.disabled', defaultMessage: 'Off' })}
        />
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <CodeOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.cliPlugin.title', defaultMessage: 'CLI Plugin Management' })}
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
            key: 'system',
            label: (
              <span>
                <BuildOutlined style={{ marginRight: 6 }} />
                {intl.formatMessage({ id: 'pages.cliPlugin.systemPlugins', defaultMessage: 'System Integrated' })}
              </span>
            ),
            children: (
              <div>
                <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
                  <Input.Search
                    placeholder={intl.formatMessage({ id: 'pages.cliPlugin.searchPlaceholder', defaultMessage: 'Search plugin name or description' })}
                    allowClear
                    value={keyword}
                    onChange={(e) => setKeyword(e.target.value)}
                    style={{ maxWidth: isMobile ? '100%' : 400, width: isMobile ? '100%' : undefined }}
                  />
                </div>
                <Table
                  columns={columns}
                  dataSource={filteredPlugins}
                  rowKey="id"
                  loading={loading}
                  pagination={false}
                  size={isMobile ? 'small' : 'middle'}
                  scroll={{ x: 'max-content' }}
                  locale={{
                    emptyText: (
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={intl.formatMessage({ id: 'pages.cliPlugin.noPlugins', defaultMessage: 'No plugins' })}
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
                {intl.formatMessage({ id: 'pages.cliPlugin.customPlugins', defaultMessage: 'Custom Plugins' })}
              </span>
            ),
            children: (
              <Result
                status="info"
                title={intl.formatMessage({ id: 'pages.cliPlugin.comingSoon', defaultMessage: 'Coming soon' })}
                style={{ marginTop: 80 }}
              />
            ),
          },
        ]}
      />
    </PageContainer>
  );
};

export default CliPluginManagement;
