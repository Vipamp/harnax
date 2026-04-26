import React, { useMemo } from 'react';
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
            border: selectedProvider?.id === provider.id ? '2px solid #1890ff' : '1px solid #d9d9d9',
            background: selectedProvider?.id === provider.id ? '#e6f7ff' : '#fff',
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
              checkedChildren="启用"
              unCheckedChildren="禁用"
              style={{
                backgroundColor: provider.status === 1 ? '#4f6ef7' : '#d9d9d9',
              }}
            />
          </div>

          {/* 是否公开、创建时间、创建人和操作按钮 */}
          <div style={{ marginTop: '8px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            {provider.isPublic === 1 && (
              <Tag color="blue" style={{ marginLeft: 0 }}>公开</Tag>
            )}
            <Text type="secondary" style={{ fontSize: '12px' }}>
              {provider.createTime?.replace('T', ' ')}
            </Text>
            {provider.creator && (
              <Text type="secondary" style={{ fontSize: '11px' }}>{provider.creator}</Text>
            )}
            <div style={{ flex: 1 }} />
            <Space size={8}>
              <Tooltip title="测试">
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
                  <Tooltip title="编辑">
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
                    title="确定要删除此服务商吗？"
                    onConfirm={(e) => {
                      e?.stopPropagation();
                      onDelete(provider.id);
                    }}
                    onCancel={(e) => e?.stopPropagation()}
                  >
                    <Tooltip title="删除">
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
