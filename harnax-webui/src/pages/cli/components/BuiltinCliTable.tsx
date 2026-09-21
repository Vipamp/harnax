import { Alert, Empty, message, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useState } from 'react';
import { useIntl } from '@umijs/max';
import { CodeOutlined } from '@ant-design/icons';
import { getCliPlugins } from '@/services/ant-design-pro/cliPlugin';

const { Text } = Typography;

const getLocalizedName = (item: API.CliPluginItem, locale: string): string => {
  if (locale.startsWith('zh')) {
    return item.displayNameZh?.trim() || item.displayName?.trim() || item.name;
  }
  return item.displayName?.trim() || item.name;
};

const BuiltinCliTable: React.FC = () => {
  const intl = useIntl();
  const locale = intl.locale;

  const [builtinClis, setBuiltinClis] = useState<API.CliPluginItem[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [messageApi, contextHolder] = message.useMessage();

  useEffect(() => {
    let ignore = false;
    const loadFailed = intl.formatMessage({ id: 'pages.message.loadFailed', defaultMessage: 'Failed to load data' });
    setLoading(true);
    getCliPlugins({ type: 'SYSTEM' })
      .then((response) => {
        if (ignore) return;
        if (response?.code === 200) setBuiltinClis(response.data || []);
        else messageApi.error(response?.message || loadFailed);
      })
      .catch(() => {
        if (!ignore) messageApi.error(loadFailed);
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, []);

  const columns: ColumnsType<API.CliPluginItem> = [
    {
      title: intl.formatMessage({ id: 'pages.cli.name', defaultMessage: 'Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 220,
      render: (_: any, record) => {
        const localizedName = getLocalizedName(record, locale);
        const showTechName = localizedName !== record.name;
        return (
          <div>
            <Text strong style={{ fontFamily: 'monospace' }}>
              <CodeOutlined style={{ marginRight: 6, color: 'var(--vip-primary)' }} />
              {localizedName}
            </Text>
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
      title: intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.healthCheck', defaultMessage: 'Health Check' }),
      dataIndex: 'healthCheck',
      key: 'healthCheck',
      width: 220,
      render: (text: string) =>
        text ? <Text code>{text}</Text> : <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      align: 'center',
      render: (val: number) => (
        <Tag color={val === 1 ? 'success' : 'default'}>
          {intl.formatMessage({
            id: val === 1 ? 'pages.common.enabled' : 'pages.common.disabled',
            defaultMessage: val === 1 ? 'Enabled' : 'Disabled',
          })}
        </Tag>
      ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      {contextHolder}
      <Alert
        type="info"
        showIcon
        message={intl.formatMessage({
          id: 'pages.cli.builtinHint',
          defaultMessage:
            'Built-in CLIs ship with the platform image and are available in every agent sandbox — no per-agent binding is needed. The status shown here is the platform registry record and is read-only.',
        })}
      />
      <Table
        columns={columns}
        dataSource={builtinClis}
        rowKey="id"
        loading={loading}
        pagination={false}
        scroll={{ x: 'max-content' }}
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({ id: 'pages.cli.noBuiltin', defaultMessage: 'No built-in CLIs' })}
            />
          ),
        }}
      />
    </Space>
  );
};

export default BuiltinCliTable;
