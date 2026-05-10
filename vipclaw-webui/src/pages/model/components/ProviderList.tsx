import React, { useMemo } from 'react';
import { useIntl } from '@umijs/max';
import { Card, Switch, Button, Space, Popconfirm, Tag, Typography, Tooltip } from 'antd';
import { EditOutlined, DeleteOutlined, ApiOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { getCurrentUserInfo, hasOperationPermission } from '@/utils/permissionUtil';

const { Text } = Typography;

interface ProviderListProps {
  providers: API.ModelProviderItem[];
  selectedProvider: API.ModelProviderItem | null;
  onSelect: (provider: API.ModelProviderItem) => void;
  onEdit: (provider: API.ModelProviderItem) => void;
  onToggle: (id: number, status: number) => void;
  onDelete: (id: number) => void;
  onConnectivityTest: (id: number) => void;
}

const ProviderList: React.FC<ProviderListProps> = ({
  providers,
  selectedProvider,
  onSelect,
  onEdit,
  onToggle,
  onDelete,
  onConnectivityTest,
}) => {
  const intl = useIntl();
  // 获取当前用户信息
  const { username: currentUser, isAdmin } = useMemo(() => getCurrentUserInfo(), []);

  const getProviderIcon = (type: string) => {
    const icons: Record<string, string> = {
      dashscope: '/icons/providers/alibabacloud.svg',
      openai: '/icons/providers/openai.svg',
      ollama: '/icons/providers/ollama.svg',
    };
    return icons[type];
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
      {providers.map((provider) => (
        <Card
          key={provider.id}
          hoverable
          size="small"
          style={{
            border: selectedProvider?.id === provider.id ? '2px solid #1890ff' : '1px solid var(--vip-border)',
            background: selectedProvider?.id === provider.id ? 'var(--vip-primary-light)' : 'var(--vip-bg-container)',
          }}
          styles={{
            body: { padding: '12px' }
          }}
          onClick={() => onSelect(provider)}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              {getProviderIcon(provider.type) ? (
                              <img
                                src={getProviderIcon(provider.type)}
                                alt={provider.type}
                                style={{ width: '24px', height: '24px' }}
                              />
                            ) : (
                              <span style={{ fontSize: '20px' }}>📦</span>
                            )}
              <div>
                <Text strong style={{ fontSize: '14px' }}>{provider.name}</Text>
                <br />
                <Text type="secondary" style={{ fontSize: '12px' }}>{provider.type}</Text>
              </div>
            </div>
            <Switch
              checked={provider.status === 1}
              onChange={() => {
                onToggle(provider.id, provider.status);
              }}
              checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
              unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
              style={{
                backgroundColor: provider.status === 1 ? 'var(--vip-primary)' : 'var(--vip-border)',
              }}
            />
          </div>

          {/* 是否公开和操作按钮 */}
          <div style={{ marginTop: '8px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            {provider.isPublic === 1 && (
              <Tag color="blue" style={{ marginLeft: 0 }}>{intl.formatMessage({ id: 'pages.model.public', defaultMessage: 'Public' })}</Tag>
            )}
            <div style={{ flex: 1 }} />
            <Space size={8}>
              <Tooltip title={intl.formatMessage({ id: 'pages.model.test', defaultMessage: 'Test' })}>
                <Button
                  type="link"
                  size="small"
                  icon={<ApiOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    onConnectivityTest(provider.id);
                  }}
                  style={{ padding: '4px' }}
                />
              </Tooltip>
              {hasOperationPermission(isAdmin, currentUser, provider.creator) && (
                <>
                  <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
                    <Button
                      type="link"
                      size="small"
                      icon={<EditOutlined />}
                      onClick={(e) => {
                        e.stopPropagation();
                        onEdit(provider);
                      }}
                      style={{ padding: '4px', color: '#1890ff' }}
                    />
                  </Tooltip>
                  <Popconfirm
                    title={intl.formatMessage({ id: 'pages.message.providerDeleteConfirm', defaultMessage: 'Are you sure to delete this provider?' })}
                    onConfirm={(e) => {
                      e?.stopPropagation();
                      onDelete(provider.id);
                    }}
                    onCancel={(e) => e?.stopPropagation()}
                  >
                    <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
                      <Button
                        type="link"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={(e) => e.stopPropagation()}
                        style={{ padding: '4px' }}
                      />
                    </Tooltip>
                  </Popconfirm>
                </>
              )}
            </Space>
          </div>
        </Card>
      ))}
    </div>
  );
};

export default ProviderList;
